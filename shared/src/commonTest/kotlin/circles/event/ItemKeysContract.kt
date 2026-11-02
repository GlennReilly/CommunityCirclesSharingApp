package circles.event

import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.encoding.ByteString
import circles.encoding.CborMap
import circles.encoding.CborText
import circles.encoding.Parsed
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** Item detail keys (spec 5): sealing a detail, and wrapping its key to a borrower's devices. */
abstract class ItemKeysContract {
    protected abstract suspend fun newCrypto(): Crypto
    protected abstract suspend fun newCipher(): Cipher

    private val detail = CborMap.of("address" to CborText("12 Example St, Elermore Vale"))
    private val item = "ab".repeat(16)

    @Test
    fun theDetailOpensWithItsKeyAndNotWithoutIt() = runTest {
        val crypto = newCrypto()
        val cipher = newCipher()
        val circle = newCircleId(crypto)
        val key = ItemKeys.newKey(crypto)
        val sealed = ItemKeys.sealDetail(cipher, key, circle, item, detail)
        assertFalse(sealed.toHex().contains(ByteString.of("Elermore".encodeToByteArray()).toHex()))
        assertEquals(detail, assertIs<Parsed.Ok<CborMap>>(ItemKeys.openDetail(cipher, key, circle, item, sealed)).value)
        assertIs<Parsed.Invalid>(ItemKeys.openDetail(cipher, ItemKeys.newKey(crypto), circle, item, sealed))
    }

    @Test
    fun theDetailCannotBeMovedToAnotherItemOrCircle() = runTest {
        val crypto = newCrypto()
        val cipher = newCipher()
        val circle = newCircleId(crypto)
        val key = ItemKeys.newKey(crypto)
        val sealed = ItemKeys.sealDetail(cipher, key, circle, item, detail)
        assertIs<Parsed.Invalid>(ItemKeys.openDetail(cipher, key, circle, "cd".repeat(16), sealed))
        assertIs<Parsed.Invalid>(ItemKeys.openDetail(cipher, key, newCircleId(crypto), item, sealed))
        val flipped = ByteString.of(sealed.toByteArray().also { it[sealed.size - 1] = (it[sealed.size - 1].toInt() xor 1).toByte() })
        assertIs<Parsed.Invalid>(ItemKeys.openDetail(cipher, key, circle, item, flipped))
    }

    @Test
    fun theKeyIsWrappedToEachOfTheBorrowersDevicesAndNoOneElses() = runTest {
        val crypto = newCrypto()
        val cipher = newCipher()
        val circle = newCircleId(crypto)
        val key = ItemKeys.newKey(crypto)
        val phone = DeviceIdentity.generate(crypto, cipher = cipher)
        val laptop = DeviceIdentity.generate(crypto, cipher, phone.member)
        val other = DeviceIdentity.generate(crypto, cipher = cipher)
        val wraps = ItemKeys.wrapToDevices(cipher, listOf(phone.registration, laptop.registration), circle, item, key)
        assertEquals(2, wraps.size)
        assertEquals(key, assertIs<Parsed.Ok<ByteString>>(ItemKeys.unwrap(cipher, phone.boxKeys, circle, item, wraps[0])).value)
        assertEquals(key, assertIs<Parsed.Ok<ByteString>>(ItemKeys.unwrap(cipher, laptop.boxKeys, circle, item, wraps[1])).value)
        assertIs<Parsed.Invalid>(ItemKeys.unwrap(cipher, other.boxKeys, circle, item, wraps[0]))
        assertIs<Parsed.Invalid>(ItemKeys.unwrap(cipher, phone.boxKeys, circle, item, wraps[1]))
    }

    @Test
    fun aWrapCannotBeReplayedForAnotherItemOrCircle() = runTest {
        val crypto = newCrypto()
        val cipher = newCipher()
        val circle = newCircleId(crypto)
        val key = ItemKeys.newKey(crypto)
        val device = DeviceIdentity.generate(crypto, cipher = cipher)
        val wrapped = ItemKeys.wrap(cipher, device.boxPub, circle, item, key)
        assertIs<Parsed.Invalid>(ItemKeys.unwrap(cipher, device.boxKeys, circle, "cd".repeat(16), wrapped))
        assertIs<Parsed.Invalid>(ItemKeys.unwrap(cipher, device.boxKeys, newCircleId(crypto), item, wrapped))
        assertIs<Parsed.Ok<ByteString>>(ItemKeys.unwrap(cipher, device.boxKeys, circle, item, wrapped))
    }
}
