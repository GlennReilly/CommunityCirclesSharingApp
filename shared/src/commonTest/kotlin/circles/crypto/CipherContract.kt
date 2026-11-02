package circles.crypto

import circles.encoding.ByteString
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Behaviour every [Cipher] implementation must have, plus fixtures made by PyNaCl (tools/vectors/cipher_vectors.py). */
abstract class CipherContract {
    protected abstract suspend fun newCipher(): Cipher

    private var keyCounter = 0
    private val message = "lend me the ladder".encodeToByteArray()

    private fun key(): ByteString {
        keyCounter++
        return ByteString.of(ByteArray(AEAD_KEY_BYTES) { (it * 7 + keyCounter * 31).toByte() })
    }

    private fun plaintext(result: DecryptResult): ByteArray = assertIs<DecryptResult.Success>(result).plaintext

    private fun flipFirstByteAt(value: ByteString, index: Int): ByteString =
        ByteString.of(value.toByteArray().also { it[index] = (it[index].toInt() xor 1).toByte() })

    @Test
    fun encryptThenDecryptRoundTrips() = runTest {
        val cipher = newCipher()
        val k = key()
        assertContentEquals(message, plaintext(cipher.decrypt(k, cipher.encrypt(k, message))))
    }

    @Test
    fun emptyPlaintextRoundTrips() = runTest {
        val cipher = newCipher()
        val k = key()
        val sealed = cipher.encrypt(k, ByteArray(0))
        assertEquals(AEAD_NONCE_BYTES + AEAD_TAG_BYTES, sealed.size)
        assertEquals(0, plaintext(cipher.decrypt(k, sealed)).size)
    }

    @Test
    fun usesAFreshNonceEveryTime() = runTest {
        val cipher = newCipher()
        val k = key()
        assertNotEquals(cipher.encrypt(k, message), cipher.encrypt(k, message))
    }

    @Test
    fun ciphertextHidesThePlaintext() = runTest {
        val cipher = newCipher()
        val sealed = cipher.encrypt(key(), message).toHex()
        assertTrue(!sealed.contains(ByteString.of(message).toHex()))
    }

    @Test
    fun decryptFailsForWrongKeyTamperingTruncationAndWrongAssociatedData() = runTest {
        val cipher = newCipher()
        val k = key()
        val aad = byteArrayOf(1, 2, 3)
        val sealed = cipher.encrypt(k, message, aad)
        assertEquals(DecryptResult.Failure, cipher.decrypt(key(), sealed, aad))
        assertEquals(DecryptResult.Failure, cipher.decrypt(k, sealed, byteArrayOf(9)))
        assertEquals(DecryptResult.Failure, cipher.decrypt(k, flipFirstByteAt(sealed, 0), aad))
        assertEquals(DecryptResult.Failure, cipher.decrypt(k, flipFirstByteAt(sealed, sealed.size - 1), aad))
        assertEquals(DecryptResult.Failure, cipher.decrypt(k, ByteString.of(sealed.toByteArray().copyOf(10)), aad))
        assertEquals(DecryptResult.Failure, cipher.decrypt(ByteString.of(ByteArray(5)), sealed, aad))
        assertContentEquals(message, plaintext(cipher.decrypt(k, sealed, aad)))
    }

    // Independent vector: draft-irtf-cfrg-xchacha-03 appendix A.3.1.
    @Test
    fun decryptsTheXChaChaDraftVector() = runTest {
        val cipher = newCipher()
        val k = ByteString.fromHex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")!!
        val nonce = "404142434445464748494a4b4c4d4e4f5051525354555657"
        val aad = ByteString.fromHex("50515253c0c1c2c3c4c5c6c7")!!.toByteArray()
        val ciphertext = "bd6d179d3e83d43b9576579493c0e939572a1700252bfaccbed2902c21396cbb731c7f1b0b4aa6440bf3a82f4eda7e39ae64c6708c54c216cb96b72e1213b4522f8c9ba40db5d945b11b69b982c1bb9e3f3fac2bc369488f76b2383565d3fff921f9664c97637da9768812f615c68b13b52e"
        val tag = "c0875924c1c7987947deafd8780acf49"
        val sealed = ByteString.fromHex(nonce + ciphertext + tag)!!
        val expected = "Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it."
        assertEquals(expected, plaintext(cipher.decrypt(k, sealed, aad)).decodeToString())
    }

    @Test
    fun sealedBoxRoundTripsAndHasTheExpectedOverhead() = runTest {
        val cipher = newCipher()
        val pair = cipher.generateBoxKeyPair()
        val sealed = cipher.sealBox(pair.publicKey, message)
        assertEquals(message.size + SEALED_BOX_OVERHEAD_BYTES, sealed.size)
        assertContentEquals(message, plaintext(cipher.openSealedBox(pair, sealed)))
    }

    @Test
    fun sealedBoxOpensOnlyForTheRightRecipientAndUntampered() = runTest {
        val cipher = newCipher()
        val pair = cipher.generateBoxKeyPair()
        val other = cipher.generateBoxKeyPair()
        val sealed = cipher.sealBox(pair.publicKey, message)
        assertEquals(DecryptResult.Failure, cipher.openSealedBox(other, sealed))
        assertEquals(DecryptResult.Failure, cipher.openSealedBox(pair, flipFirstByteAt(sealed, sealed.size - 1)))
        assertEquals(DecryptResult.Failure, cipher.openSealedBox(pair, ByteString.of(ByteArray(10))))
    }

    @Test
    fun sealedBoxIsRandomisedPerCall() = runTest {
        val cipher = newCipher()
        val pair = cipher.generateBoxKeyPair()
        assertNotEquals(cipher.sealBox(pair.publicKey, message), cipher.sealBox(pair.publicKey, message))
    }

    @Test
    fun opensTheLibsodiumFixtures() = runTest {
        val cipher = newCipher()
        val aeadKey = ByteString.fromHex("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f")!!
        val aad = "circle:epoch:1".encodeToByteArray()
        val aead = ByteString.fromHex("404142434445464748494a4b4c4d4e4f5051525354555657b1496a13b8c01273f6d4e1d1ddbc11faf79ac6aa7a2d27f30456dd26607640fc73c192719278bea4a2a5cf7e4693f0ab6e")!!
        assertEquals("epoch key for the knitting circle", plaintext(cipher.decrypt(aeadKey, aead, aad)).decodeToString())
        assertEquals(DecryptResult.Failure, cipher.decrypt(aeadKey, aead))

        val box = BoxKeyPair(
            ByteString.fromHex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")!!,
            ByteString.fromHex("493e82fc74464a59268817623d2053c5eb8e2cc4a988b4fee179ec6b010d531d")!!,
        )
        val sealed = ByteString.fromHex("45b802e06d078b578e188a4d450f4b100fa3607603f0c610114b089044ee4b22651aa8863e44870a5a29290cee3fddef0d8c11b6d64efdd00e32f8df0a490b9ce6fd")!!
        assertEquals("wrapped circle key", plaintext(cipher.openSealedBox(box, sealed)).decodeToString())
    }
}
