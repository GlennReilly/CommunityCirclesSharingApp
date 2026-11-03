package circles.crypto

import circles.encoding.ByteString
import kotlinx.coroutines.await
import kotlinx.coroutines.test.runTest
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * ADR 0035: the browser's noble implementations must agree with libsodium (the independent oracle, shipped nowhere) on
 * every operation we use, in both directions, on random and edge-case inputs, and must fail on the same tampering.
 */
class NobleVsLibsodiumTest {
    private val crypto = JsCrypto()
    private val cipher = JsCipher()

    // Sizes around the block boundaries of BLAKE2b (128), ChaCha (64) and Poly1305 (16), plus large.
    private val sizes = listOf(0, 1, 15, 16, 17, 63, 64, 65, 127, 128, 129, 255, 1000, 4096, 100_000)

    private fun ByteArray.u8(): Uint8Array {
        val view = unsafeCast<Int8Array>()
        return Uint8Array(view.buffer, view.byteOffset, view.length)
    }

    private fun Uint8Array.bytes(): ByteArray = Int8Array(buffer, byteOffset, length).unsafeCast<ByteArray>().copyOf()

    private fun random(n: Int): ByteArray = crypto.randomBytes(n).toByteArray()

    private suspend fun oracle() = Sodium.also { it.ready.await() }

    @Test
    fun blake2b256AgreesOnEverySize() = runTest {
        val sodium = oracle()
        sizes.forEach { n ->
            val message = random(n)
            assertContentEquals(sodium.crypto_generichash(32, message.u8()).bytes(), crypto.hash256(message).toByteArray(), "size $n")
        }
    }

    @Test
    fun ed25519AgreesAndSignaturesCrossVerify() = runTest {
        val sodium = oracle()
        repeat(15) {
            val pair = crypto.generateSigningKeyPair()
            val reference = sodium.crypto_sign_seed_keypair(pair.secretSeed.toByteArray().u8())
            assertContentEquals(reference.publicKey.bytes(), pair.publicKey.toByteArray(), "the same public key from a seed")
            sizes.take(10).forEach { n ->
                val message = random(n)
                val ours = crypto.sign(pair.secretSeed, message)
                val theirs = sodium.crypto_sign_detached(message.u8(), reference.privateKey)
                assertContentEquals(theirs.bytes(), ours.toByteArray(), "Ed25519 is deterministic, so the signatures are identical (size $n)")
                assertTrue(sodium.crypto_sign_verify_detached(ours.toByteArray().u8(), message.u8(), pair.publicKey.toByteArray().u8()), "libsodium accepts ours")
                assertTrue(crypto.verify(pair.publicKey, message, ByteString.of(theirs.bytes())), "we accept libsodium's")
            }
        }
    }

    @Test
    fun badSignaturesAreRefusedByBoth() = runTest {
        val sodium = oracle()
        val pair = crypto.generateSigningKeyPair()
        val other = crypto.generateSigningKeyPair()
        val message = random(100)
        val signature = crypto.sign(pair.secretSeed, message).toByteArray()
        val flipped = signature.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        val badMessage = message.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        listOf(
            Triple(message, flipped, pair.publicKey),
            Triple(badMessage, signature, pair.publicKey),
            Triple(message, signature, other.publicKey),
        ).forEach { (m, s, k) ->
            assertFalse(crypto.verify(k, m, ByteString.of(s)))
            assertFalse(sodium.crypto_sign_verify_detached(s.u8(), m.u8(), k.toByteArray().u8()))
        }
    }

    @Test
    fun aNonCanonicalSignatureIsRefusedByBoth() = runTest {
        val sodium = oracle()
        val pair = crypto.generateSigningKeyPair()
        val message = random(50)
        // Adding the group order L to S keeps the equation true but makes S non-canonical (S >= L): RFC 8032 refuses it.
        val l = byteArrayOf(
            0xed.toByte(), 0xd3.toByte(), 0xf5.toByte(), 0x5c, 0x1a, 0x63, 0x12, 0x58, 0xd6.toByte(), 0x9c.toByte(), 0xf7.toByte(), 0xa2.toByte(),
            0xde.toByte(), 0xf9.toByte(), 0xde.toByte(), 0x14, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x10,
        )
        val signature = crypto.sign(pair.secretSeed, message).toByteArray()
        val bad = signature.copyOf()
        var carry = 0
        for (i in 0 until 32) {
            val sum = (bad[32 + i].toInt() and 0xFF) + (l[i].toInt() and 0xFF) + carry
            bad[32 + i] = sum.toByte()
            carry = sum shr 8
        }
        if (carry == 0) { // S + L still fits in 256 bits, so the forgery is well formed
            assertFalse(crypto.verify(pair.publicKey, message, ByteString.of(bad)), "noble must refuse a non-canonical S")
            assertFalse(sodium.crypto_sign_verify_detached(bad.u8(), message.u8(), pair.publicKey.toByteArray().u8()), "and so does libsodium")
        }
    }

    @Test
    fun xchacha20Poly1305OpensEachOthersCiphertextWithAndWithoutAssociatedData() = runTest {
        val sodium = oracle()
        val key = random(32)
        sizes.forEach { n ->
            listOf(ByteArray(0), random(7), random(100)).forEach { aad ->
                val plain = random(n)
                val ours = cipher.encrypt(ByteString.of(key), plain, aad).toByteArray()
                val nonce = ours.copyOfRange(0, 24)
                val opened = sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(null, ours.copyOfRange(24, ours.size).u8(), aad.u8(), nonce.u8(), key.u8())
                assertContentEquals(plain, opened.bytes(), "libsodium opens ours (size $n)")

                val n2 = random(24)
                val theirs = sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(plain.u8(), aad.u8(), null, n2.u8(), key.u8()).bytes()
                val back = cipher.decrypt(ByteString.of(key), ByteString.of(n2 + theirs), aad)
                assertContentEquals(plain, assertIs<DecryptResult.Success>(back).plaintext, "we open libsodium's (size $n)")
            }
        }
    }

    @Test
    fun xchacha20Poly1305RefusesTheSameTamperingAsLibsodium() = runTest {
        val sodium = oracle()
        val key = random(32)
        val aad = random(5)
        val ours = cipher.encrypt(ByteString.of(key), random(80), aad).toByteArray()
        val nonce = ours.copyOfRange(0, 24)
        val body = ours.copyOfRange(24, ours.size)
        fun libsodiumOpens(n: ByteArray, b: ByteArray, a: ByteArray, k: ByteArray): Boolean =
            try {
                sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(null, b.u8(), a.u8(), n.u8(), k.u8())
                true
            } catch (_: Throwable) {
                false
            }
        val cases = listOf(
            Triple(ByteString.of(nonce + body.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }), aad, key), // body
            Triple(ByteString.of(nonce + body.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }), aad, key), // tag
            Triple(ByteString.of(nonce.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() } + body), aad, key), // nonce
            Triple(ByteString.of(nonce + body), random(5), key), // associated data
            Triple(ByteString.of(nonce + body), aad, random(32)), // key
        )
        cases.forEach { (sealed, a, k) ->
            val bytes = sealed.toByteArray()
            assertEquals(DecryptResult.Failure, cipher.decrypt(ByteString.of(k), sealed, a))
            assertFalse(libsodiumOpens(bytes.copyOfRange(0, 24), bytes.copyOfRange(24, bytes.size), a, k))
        }
    }

    @Test
    fun sealedBoxesOpenInBothDirections() = runTest {
        val sodium = oracle()
        sizes.forEach { n ->
            val plain = random(n)
            // Ours to libsodium.
            val ours = cipher.generateBoxKeyPair()
            val sealedByUs = cipher.sealBox(ours.publicKey, plain).toByteArray()
            assertEquals(n + SEALED_BOX_OVERHEAD_BYTES, sealedByUs.size)
            val opened = sodium.crypto_box_seal_open(sealedByUs.u8(), ours.publicKey.toByteArray().u8(), ours.secretKey.toByteArray().u8())
            assertContentEquals(plain, opened.bytes(), "libsodium opens our sealed box (size $n)")
            // Libsodium to ours.
            val theirs = sodium.crypto_box_keypair()
            val sealedByThem = sodium.crypto_box_seal(plain.u8(), theirs.publicKey).bytes()
            val back = cipher.openSealedBox(BoxKeyPair(ByteString.of(theirs.privateKey.bytes()), ByteString.of(theirs.publicKey.bytes())), ByteString.of(sealedByThem))
            assertContentEquals(plain, assertIs<DecryptResult.Success>(back).plaintext, "we open libsodium's sealed box (size $n)")
        }
    }

    @Test
    fun sealedBoxesFailTheSameWayForTheWrongKeyAndTampering() = runTest {
        val sodium = oracle()
        val mine = cipher.generateBoxKeyPair()
        val other = cipher.generateBoxKeyPair()
        val sealed = cipher.sealBox(mine.publicKey, random(60)).toByteArray()
        fun libsodiumOpens(c: ByteArray, pk: ByteString, sk: ByteString): Boolean =
            try {
                sodium.crypto_box_seal_open(c.u8(), pk.toByteArray().u8(), sk.toByteArray().u8())
                true
            } catch (_: Throwable) {
                false
            }
        val tamperedEphemeral = sealed.copyOf().also { it[4] = (it[4].toInt() xor 1).toByte() }
        val tamperedBody = sealed.copyOf().also { it[40] = (it[40].toInt() xor 1).toByte() }
        val tamperedTag = sealed.copyOf().also { it[sealed.size - 1] = (it[sealed.size - 1].toInt() xor 1).toByte() }
        listOf(
            Triple(sealed, other.publicKey, other.secretKey),
            Triple(tamperedEphemeral, mine.publicKey, mine.secretKey),
            Triple(tamperedBody, mine.publicKey, mine.secretKey),
            Triple(tamperedTag, mine.publicKey, mine.secretKey),
            Triple(sealed.copyOf(40), mine.publicKey, mine.secretKey),
        ).forEach { (c, pk, sk) ->
            assertEquals(DecryptResult.Failure, cipher.openSealedBox(BoxKeyPair(sk, pk), ByteString.of(c)))
            assertFalse(libsodiumOpens(c, pk, sk))
        }
    }

    @Test
    fun aLowOrderEphemeralKeyIsRefusedNotOpened() = runTest {
        val mine = cipher.generateBoxKeyPair()
        // An all-zero ephemeral public key (a low-order point) must give a failure, never a result.
        val sealed = ByteArray(32) + ByteArray(40) { it.toByte() }
        assertEquals(DecryptResult.Failure, cipher.openSealedBox(mine, ByteString.of(sealed)))
    }

    @Test
    fun boxKeysAreRealX25519KeysThatLibsodiumAccepts() = runTest {
        val sodium = oracle()
        repeat(20) {
            val pair = cipher.generateBoxKeyPair()
            assertEquals(32, pair.publicKey.size)
            // libsodium can only open a box to this public key if the secret really belongs to it.
            val sealed = sodium.crypto_box_seal("probe".encodeToByteArray().u8(), pair.publicKey.toByteArray().u8()).bytes()
            val opened = cipher.openSealedBox(pair, ByteString.of(sealed))
            assertEquals("probe", assertIs<DecryptResult.Success>(opened).plaintext.decodeToString())
        }
    }
}
