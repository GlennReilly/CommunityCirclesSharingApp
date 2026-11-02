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
import circles.encoding.CborInt
import circles.encoding.CborMap
import circles.encoding.CborText
import circles.encoding.Parsed
import circles.policy.CircleId

/** The first circle key epoch; epoch 0 means "no circle key" (ADR 0021). */
const val FIRST_EPOCH = 1

private const val KEY_CIRCLE = "circle"
private const val KEY_EPOCH = "epoch"
private const val KEY_KEY = "key"
private const val KEY_DEVICE = "device"
private const val KEY_WRAPPED = "wrapped"
private const val MAX_WRAPPED_BYTES = 1024

/** The circle keys a device holds, by epoch. Immutable: [with] returns a new ring. */
class KeyRing private constructor(private val keys: Map<Int, ByteString>) {
    /** The newest epoch held, or 0 when the ring is empty. */
    val latestEpoch: Int get() = keys.keys.maxOrNull() ?: 0

    operator fun get(epoch: Int): ByteString? = keys[epoch]

    fun with(epoch: Int, key: ByteString): KeyRing {
        require(epoch >= FIRST_EPOCH && key.size == AEAD_KEY_BYTES) { "a circle key is $AEAD_KEY_BYTES bytes at epoch >= $FIRST_EPOCH" }
        return KeyRing(keys + (epoch to key))
    }

    companion object {
        val EMPTY: KeyRing = KeyRing(emptyMap())

        fun newCircleKey(crypto: Crypto): ByteString = crypto.randomBytes(AEAD_KEY_BYTES)
    }
}

/** `public` of `key.wrap`: whom the key is for, the epoch, and the sealed key. */
data class KeyWrapTerms(val device: DeviceId, val epoch: Int, val wrapped: ByteString) {
    fun toPublic(): CborMap =
        CborMap.of(KEY_DEVICE to CborText(device.v), KEY_EPOCH to CborInt(epoch.toLong()), KEY_WRAPPED to CborBytes(wrapped))
}

/** Strict readers and the wrapping format for circle keys (ADR 0021). */
object CircleKeys {
    fun keyWrap(map: CborMap): Parsed<KeyWrapTerms> {
        if (map.entries.keys != setOf(KEY_DEVICE, KEY_EPOCH, KEY_WRAPPED)) return Parsed.Invalid("expected exactly the keys [device, epoch, wrapped]")
        val device = (map[KEY_DEVICE] as? CborText)?.value?.takeIf { isValidRandomId(it) } ?: return Parsed.Invalid("device must be 32 lowercase hex characters")
        val epoch = epochOf(map) ?: return Parsed.Invalid("epoch must be at least $FIRST_EPOCH")
        val wrapped = (map[KEY_WRAPPED] as? CborBytes)?.value?.takeIf { it.size in 1..MAX_WRAPPED_BYTES } ?: return Parsed.Invalid("wrapped must be 1 to $MAX_WRAPPED_BYTES bytes")
        return Parsed.Ok(KeyWrapTerms(DeviceId(device), epoch, wrapped))
    }

    /** `public` of `key.rotate` is just the new epoch. */
    fun keyRotate(map: CborMap): Parsed<Int> {
        if (map.entries.keys != setOf(KEY_EPOCH)) return Parsed.Invalid("expected exactly the key [epoch]")
        return epochOf(map)?.let { Parsed.Ok(it) } ?: Parsed.Invalid("epoch must be at least $FIRST_EPOCH")
    }

    fun rotatePublic(epoch: Int): CborMap = CborMap.of(KEY_EPOCH to CborInt(epoch.toLong()))

    /** Seals [key] to a device's box key, bound to the circle and epoch so a wrap cannot be replayed as another epoch's. */
    fun wrap(cipher: Cipher, recipientBoxPub: ByteString, circle: CircleId, epoch: Int, key: ByteString): ByteString {
        val payload = CborMap.of(KEY_CIRCLE to CborText(circle.v), KEY_EPOCH to CborInt(epoch.toLong()), KEY_KEY to CborBytes(key))
        return cipher.sealBox(recipientBoxPub, CborEncoder.encode(payload))
    }

    /** Opens a wrap; invalid if it is not for this device, or names another circle or epoch than the event does. */
    fun unwrap(cipher: Cipher, box: BoxKeyPair, circle: CircleId, epoch: Int, wrapped: ByteString): Parsed<ByteString> {
        val plain = (cipher.openSealedBox(box, wrapped) as? DecryptResult.Success)?.plaintext ?: return Parsed.Invalid("wrapped key cannot be opened by this device")
        val map = (CborDecoder.decode(plain) as? Parsed.Ok)?.value as? CborMap ?: return Parsed.Invalid("wrapped key is not a CBOR map")
        if (map.entries.keys != setOf(KEY_CIRCLE, KEY_EPOCH, KEY_KEY)) return Parsed.Invalid("wrapped key has the wrong fields")
        if ((map[KEY_CIRCLE] as? CborText)?.value != circle.v) return Parsed.Invalid("wrapped key is for another circle")
        if ((map[KEY_EPOCH] as? CborInt)?.value != epoch.toLong()) return Parsed.Invalid("wrapped key is for another epoch")
        val key = (map[KEY_KEY] as? CborBytes)?.value?.takeIf { it.size == AEAD_KEY_BYTES } ?: return Parsed.Invalid("wrapped key has the wrong length")
        return Parsed.Ok(key)
    }

    private fun epochOf(map: CborMap): Int? = (map[KEY_EPOCH] as? CborInt)?.value?.takeIf { it in FIRST_EPOCH..Int.MAX_VALUE }?.toInt()
}

/** Seals and opens the secret part of a `v = 1` event (ADR 0021). */
object PayloadSealing {
    /** Returns [unsealed] with `v = 1`, its nonce and its ciphertext filled in; [unsealed.epoch] selects the key. */
    fun seal(cipher: Cipher, key: ByteString, unsealed: Envelope, payload: CborMap): Envelope {
        val base = unsealed.copy(v = PAYLOAD_VERSION_SEALED, nonce = ByteString.EMPTY, encrypted = ByteString.EMPTY)
        if (payload.entries.isEmpty()) return base
        val sealed = cipher.encrypt(key, CborEncoder.encode(payload), base.associatedData()).toByteArray()
        return base.copy(
            nonce = ByteString.of(sealed.copyOfRange(0, circles.crypto.AEAD_NONCE_BYTES)),
            encrypted = ByteString.of(sealed.copyOfRange(circles.crypto.AEAD_NONCE_BYTES, sealed.size)),
        )
    }

    /** The payload map of an event, whichever format it uses; invalid if the key is missing, wrong, or the event was altered. */
    fun open(cipher: Cipher, keys: KeyRing, envelope: Envelope): Parsed<CborMap> {
        if (envelope.v == PAYLOAD_VERSION_PLAINTEXT) return EnvelopeChecks.payload(envelope)
        if (envelope.encrypted.isEmpty()) return Parsed.Ok(CborMap.EMPTY)
        val key = keys[envelope.epoch] ?: return Parsed.Invalid("no circle key for epoch ${envelope.epoch}")
        val joined = ByteString.of(envelope.nonce.toByteArray() + envelope.encrypted.toByteArray())
        val plain = (cipher.decrypt(key, joined, envelope.associatedData()) as? DecryptResult.Success)?.plaintext
            ?: return Parsed.Invalid("payload does not decrypt")
        return (CborDecoder.decode(plain) as? Parsed.Ok)?.value?.let { it as? CborMap }?.let { Parsed.Ok(it) }
            ?: Parsed.Invalid("payload is not a CBOR map")
    }
}
