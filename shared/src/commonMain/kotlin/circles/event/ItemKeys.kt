package circles.event

import circles.crypto.AEAD_KEY_BYTES
import circles.crypto.BoxKeyPair
import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.crypto.DecryptResult
import circles.encoding.ByteString
import circles.encoding.CborBytes
import circles.encoding.CborDecoder
import circles.encoding.CborEncoder
import circles.encoding.CborMap
import circles.encoding.CborText
import circles.encoding.Parsed
import circles.policy.CircleId

/** Domain string of the associated data that binds an item's detail to its circle and item id. */
const val ITEM_DETAIL_AAD_DOMAIN = "circles/item-detail/v1"

private const val KEY_CIRCLE = "circle"
private const val KEY_ITEM = "item"
private const val KEY_KEY = "key"

/**
 * Item detail keys (spec 5): each item has its own random 256-bit key. `item.detail` is encrypted with it, and
 * `loan.respond` (approval) wraps it to each of the borrower's devices. So a borrower learns the sensitive detail
 * (for example an address) only for items they were approved for, and a member who leaves the circle's key epochs
 * does not automatically lose or gain item access. [itemId] is the item's id as text; item events arrive in a later milestone.
 */
object ItemKeys {
    fun newKey(crypto: Crypto): ByteString = crypto.randomBytes(AEAD_KEY_BYTES)

    private fun aad(circle: CircleId, itemId: String): ByteArray = ITEM_DETAIL_AAD_DOMAIN.encodeToByteArray() + circle.v.encodeToByteArray() + itemId.encodeToByteArray()

    /** Encrypts the sensitive fields of an item; the result is `nonce || ciphertext || tag`. */
    fun sealDetail(cipher: Cipher, itemKey: ByteString, circle: CircleId, itemId: String, detail: CborMap): ByteString =
        cipher.encrypt(itemKey, CborEncoder.encode(detail), aad(circle, itemId))

    /** Invalid if the key is wrong, or the detail was moved from another item or circle, or was altered. */
    fun openDetail(cipher: Cipher, itemKey: ByteString, circle: CircleId, itemId: String, sealed: ByteString): Parsed<CborMap> {
        val plain = (cipher.decrypt(itemKey, sealed, aad(circle, itemId)) as? DecryptResult.Success)?.plaintext ?: return Parsed.Invalid("item detail does not decrypt")
        return (CborDecoder.decode(plain) as? Parsed.Ok)?.value?.let { it as? CborMap }?.let { Parsed.Ok(it) } ?: Parsed.Invalid("item detail is not a CBOR map")
    }

    /** Seals [itemKey] to one device's box key, bound to the circle and item so it cannot be replayed for another item. */
    fun wrap(cipher: Cipher, recipientBoxPub: ByteString, circle: CircleId, itemId: String, itemKey: ByteString): ByteString {
        val content = CborMap.of(KEY_CIRCLE to CborText(circle.v), KEY_ITEM to CborText(itemId), KEY_KEY to CborBytes(itemKey))
        return cipher.sealBox(recipientBoxPub, CborEncoder.encode(content))
    }

    /** One sealed key per recipient device, in the same order. */
    fun wrapToDevices(cipher: Cipher, devices: List<DeviceRegistration>, circle: CircleId, itemId: String, itemKey: ByteString): List<ByteString> =
        devices.map { wrap(cipher, it.boxPub, circle, itemId, itemKey) }

    fun unwrap(cipher: Cipher, box: BoxKeyPair, circle: CircleId, itemId: String, wrapped: ByteString): Parsed<ByteString> {
        val plain = (cipher.openSealedBox(box, wrapped) as? DecryptResult.Success)?.plaintext ?: return Parsed.Invalid("item key cannot be opened by this device")
        val map = (CborDecoder.decode(plain) as? Parsed.Ok)?.value as? CborMap ?: return Parsed.Invalid("item key is not a CBOR map")
        if (map.entries.keys != setOf(KEY_CIRCLE, KEY_ITEM, KEY_KEY)) return Parsed.Invalid("item key has the wrong fields")
        if ((map[KEY_CIRCLE] as? CborText)?.value != circle.v) return Parsed.Invalid("item key is for another circle")
        if ((map[KEY_ITEM] as? CborText)?.value != itemId) return Parsed.Invalid("item key is for another item")
        val key = (map[KEY_KEY] as? CborBytes)?.value?.takeIf { it.size == AEAD_KEY_BYTES } ?: return Parsed.Invalid("item key has the wrong length")
        return Parsed.Ok(key)
    }
}
