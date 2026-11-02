package circles.event

import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.encoding.ByteString
import circles.encoding.Parsed
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Blob sealing (ADR 0026), on every Crypto/Cipher pair. */
abstract class BlobSealingContract {
    protected abstract suspend fun newCrypto(): Crypto
    protected abstract suspend fun newCipher(): Cipher

    private val photo = ByteArray(5_000) { (it * 31).toByte() }

    @Test
    fun aBlobRoundTripsAndItsIdIsTheHashOfTheCiphertext() = runTest {
        val crypto = newCrypto()
        val cipher = newCipher()
        val circle = newCircleId(crypto)
        val key = KeyRing.newCircleKey(crypto)
        val sealed = checkNotNull(BlobSealing.seal(crypto, cipher, key, circle, photo))
        assertEquals(64, sealed.hash.length)
        assertTrue(BlobSealing.matchesHash(crypto, sealed.hash, sealed.bytes))
        assertFalse(BlobSealing.matchesHash(crypto, "00".repeat(32), sealed.bytes))
        assertContentEquals(photo, assertIs<Parsed.Ok<ByteArray>>(BlobSealing.open(cipher, key, circle, sealed.bytes)).value)
    }

    @Test
    fun sealingTwiceGivesDifferentBlobs() = runTest {
        val crypto = newCrypto()
        val cipher = newCipher()
        val circle = newCircleId(crypto)
        val key = KeyRing.newCircleKey(crypto)
        assertNotEquals(BlobSealing.seal(crypto, cipher, key, circle, photo)!!.hash, BlobSealing.seal(crypto, cipher, key, circle, photo)!!.hash)
    }

    @Test
    fun aBlobDoesNotOpenWithTheWrongKeyInAnotherCircleOrAfterTampering() = runTest {
        val crypto = newCrypto()
        val cipher = newCipher()
        val circle = newCircleId(crypto)
        val key = KeyRing.newCircleKey(crypto)
        val sealed = checkNotNull(BlobSealing.seal(crypto, cipher, key, circle, photo)).bytes
        assertIs<Parsed.Invalid>(BlobSealing.open(cipher, KeyRing.newCircleKey(crypto), circle, sealed))
        assertIs<Parsed.Invalid>(BlobSealing.open(cipher, key, newCircleId(crypto), sealed))
        val flipped = ByteString.of(sealed.toByteArray().also { it[40] = (it[40].toInt() xor 1).toByte() })
        assertIs<Parsed.Invalid>(BlobSealing.open(cipher, key, circle, flipped))
    }

    @Test
    fun theLargestPlaintextFitsAndOneMoreByteDoesNot() = runTest {
        val crypto = newCrypto()
        val cipher = newCipher()
        val circle = newCircleId(crypto)
        val key = KeyRing.newCircleKey(crypto)
        val biggest = checkNotNull(BlobSealing.seal(crypto, cipher, key, circle, ByteArray(MAX_BLOB_PLAINTEXT_BYTES)))
        assertEquals(circles.api.MAX_BLOB_BYTES, biggest.bytes.size)
        assertNull(BlobSealing.seal(crypto, cipher, key, circle, ByteArray(MAX_BLOB_PLAINTEXT_BYTES + 1)))
    }
}
