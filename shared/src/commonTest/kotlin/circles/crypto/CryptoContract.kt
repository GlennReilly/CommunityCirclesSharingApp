package circles.crypto

import circles.encoding.ByteString
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Behaviour every [Crypto] implementation must have; run against the JVM and JS implementations. */
abstract class CryptoContract {
    protected abstract suspend fun newCrypto(): Crypto

    private fun hex(text: String): ByteString = ByteString.fromHex(text)!!

    private data class Rfc8032(val seed: String, val publicKey: String, val message: String, val signature: String)

    // RFC 8032 section 7.1, tests 1 and 2.
    private val rfc8032 = listOf(
        Rfc8032(
            "9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
            "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a",
            "",
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        ),
        Rfc8032(
            "4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
            "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c",
            "72",
            "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00",
        ),
    )

    @Test
    fun signsExactlyAsRfc8032() = runTest {
        val crypto = newCrypto()
        rfc8032.forEach {
            assertEquals(hex(it.signature), crypto.sign(hex(it.seed), hex(it.message).toByteArray()))
        }
    }

    @Test
    fun verifiesRfc8032Signatures() = runTest {
        val crypto = newCrypto()
        rfc8032.forEach {
            assertTrue(crypto.verify(hex(it.publicKey), hex(it.message).toByteArray(), hex(it.signature)))
        }
    }

    @Test
    fun verifyRefusesTamperedMessageSignatureAndKey() = runTest {
        val crypto = newCrypto()
        val vector = rfc8032[1]
        val message = hex(vector.message).toByteArray()
        val signature = hex(vector.signature)
        assertFalse(crypto.verify(hex(vector.publicKey), byteArrayOf(0x73), signature))
        val flipped = signature.toByteArray().also { it[0] = (it[0].toInt() xor 1).toByte() }
        assertFalse(crypto.verify(hex(vector.publicKey), message, ByteString.of(flipped)))
        assertFalse(crypto.verify(hex(rfc8032[0].publicKey), message, signature))
    }

    @Test
    fun verifyNeverThrowsOnMalformedInput() = runTest {
        val crypto = newCrypto()
        val vector = rfc8032[0]
        val message = ByteArray(0)
        assertFalse(crypto.verify(ByteString.EMPTY, message, hex(vector.signature)))
        assertFalse(crypto.verify(hex(vector.publicKey), message, ByteString.EMPTY))
        assertFalse(crypto.verify(hex(vector.publicKey), message, ByteString.of(ByteArray(ED25519_SIGNATURE_BYTES - 1))))
        assertFalse(crypto.verify(ByteString.of(ByteArray(ED25519_KEY_BYTES - 1)), message, hex(vector.signature)))
        assertFalse(crypto.verify(ByteString.of(ByteArray(ED25519_KEY_BYTES) { 0xff.toByte() }), message, hex(vector.signature)))
        assertFalse(crypto.verify(ByteString.of(ByteArray(ED25519_KEY_BYTES)), message, ByteString.of(ByteArray(ED25519_SIGNATURE_BYTES))))
    }

    @Test
    fun generatedKeyPairsSignAndVerify() = runTest {
        val crypto = newCrypto()
        val pair = crypto.generateSigningKeyPair()
        assertEquals(ED25519_KEY_BYTES, pair.secretSeed.size)
        assertEquals(ED25519_KEY_BYTES, pair.publicKey.size)
        val signature = crypto.sign(pair.secretSeed, "hello".encodeToByteArray())
        assertEquals(ED25519_SIGNATURE_BYTES, signature.size)
        assertTrue(crypto.verify(pair.publicKey, "hello".encodeToByteArray(), signature))
        assertFalse(crypto.verify(pair.publicKey, "hellp".encodeToByteArray(), signature))
        assertNotEquals(pair.publicKey, crypto.generateSigningKeyPair().publicKey)
    }

    @Test
    fun signingIsDeterministic() = runTest {
        val crypto = newCrypto()
        val pair = crypto.generateSigningKeyPair()
        val message = byteArrayOf(1, 2, 3)
        assertEquals(crypto.sign(pair.secretSeed, message), crypto.sign(pair.secretSeed, message))
    }

    @Test
    fun hashIsBlake2b256() = runTest {
        val crypto = newCrypto()
        assertEquals("0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8", crypto.hash256(ByteArray(0)).toHex())
        assertEquals("bddd813c634239723171ef3fee98579b94964e3bb1cb3e427262c8c068d52319", crypto.hash256("abc".encodeToByteArray()).toHex())
        assertEquals(HASH_BYTES, crypto.hash256(ByteArray(1000)).size)
    }

    @Test
    fun randomBytesHaveTheRequestedLengthAndDiffer() = runTest {
        val crypto = newCrypto()
        assertEquals(24, crypto.randomBytes(24).size)
        assertEquals(0, crypto.randomBytes(0).size)
        assertNotEquals(crypto.randomBytes(16), crypto.randomBytes(16))
        assertContentEquals(ByteArray(0), crypto.randomBytes(0).toByteArray())
    }
}
