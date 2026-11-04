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
private const val KEY_HASH = "keyHash"
private const val KEY_SET = "set"

/** Domain of the key commitment (ADR 0045). */
const val KEY_COMMITMENT_DOMAIN = "circles/key-commitment/v1"
const val KEY_COMMITMENT_BYTES = 32
private const val EPOCH_BYTES = 4
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

/**
 * `public` of `key.wrap`: whom the key is for, the epoch, and the sealed key. The wrap that starts epoch 1 also carries
 * the [keyHash] commitment to that epoch's key (ADR 0045); later wraps of the epoch may omit it.
 */
data class KeyWrapTerms(
    val device: DeviceId,
    val epoch: Int,
    val wrapped: ByteString,
    val keyHash: ByteString? = null,
    /** Only on `recovery.share` (ADR 0065): names the set of shares this one belongs to (32 lowercase hex characters). */
    val set: String? = null,
) {
    fun toPublic(): CborMap = CborMap(
        buildMap {
            put(KEY_DEVICE, CborText(device.v))
            put(KEY_EPOCH, CborInt(epoch.toLong()))
            put(KEY_WRAPPED, CborBytes(wrapped))
            keyHash?.let { put(KEY_HASH, CborBytes(it)) }
            set?.let { put(KEY_SET, CborText(it)) }
        },
    )
}

/** `public` of `key.rotate`: the new epoch and the commitment to its key (ADR 0045). */
data class KeyRotateTerms(val epoch: Int, val keyHash: ByteString?)

/** Strict readers and the wrapping format for circle keys (ADR 0021). */
object CircleKeys {
    /** The commitment to [key] as the key of [epoch] of [circle] (ADR 0045): public, and says nothing about the key. */
    fun commitment(crypto: Crypto, circle: CircleId, epoch: Int, key: ByteString): ByteString {
        val epochBytes = ByteArray(EPOCH_BYTES) { index -> (epoch shr (8 * (EPOCH_BYTES - 1 - index))).toByte() }
        return crypto.hash256(KEY_COMMITMENT_DOMAIN.encodeToByteArray() + circle.v.encodeToByteArray() + epochBytes + key.toByteArray())
    }

    private fun hashOf(map: CborMap): Parsed<ByteString?> {
        val raw = map[KEY_HASH] ?: return Parsed.Ok(null)
        return (raw as? CborBytes)?.value?.takeIf { it.size == KEY_COMMITMENT_BYTES }?.let { Parsed.Ok(it) } ?: Parsed.Invalid("keyHash must be $KEY_COMMITMENT_BYTES bytes")
    }

    fun keyWrap(map: CborMap): Parsed<KeyWrapTerms> {
        val required = setOf(KEY_DEVICE, KEY_EPOCH, KEY_WRAPPED)
        val keys = map.entries.keys
        if (!keys.containsAll(required) || !(required + KEY_HASH + KEY_SET).containsAll(keys)) return Parsed.Invalid("expected the keys [device, epoch, wrapped] and optionally keyHash and set")
        val device = (map[KEY_DEVICE] as? CborText)?.value?.takeIf { isValidRandomId(it) } ?: return Parsed.Invalid("device must be 32 lowercase hex characters")
        val epoch = epochOf(map) ?: return Parsed.Invalid("epoch must be at least $FIRST_EPOCH")
        val wrapped = (map[KEY_WRAPPED] as? CborBytes)?.value?.takeIf { it.size in 1..MAX_WRAPPED_BYTES } ?: return Parsed.Invalid("wrapped must be 1 to $MAX_WRAPPED_BYTES bytes")
        val hash = when (val h = hashOf(map)) {
            is Parsed.Ok -> h.value
            is Parsed.Invalid -> return h
        }
        val set = when (val raw = map[KEY_SET]) {
            null -> null
            is CborText -> raw.value.takeIf { isValidRandomId(it) } ?: return Parsed.Invalid("set must be 32 lowercase hex characters")
            else -> return Parsed.Invalid("set must be 32 lowercase hex characters")
        }
        return Parsed.Ok(KeyWrapTerms(DeviceId(device), epoch, wrapped, hash, set))
    }

    /** `public` of `key.rotate`: the new epoch, and its key commitment (ADR 0045). */
    fun keyRotateTerms(map: CborMap): Parsed<KeyRotateTerms> {
        if (map.entries.keys != setOf(KEY_EPOCH) && map.entries.keys != setOf(KEY_EPOCH, KEY_HASH)) return Parsed.Invalid("expected the key [epoch] and optionally keyHash")
        val epoch = epochOf(map) ?: return Parsed.Invalid("epoch must be at least $FIRST_EPOCH")
        return when (val hash = hashOf(map)) {
            is Parsed.Ok -> Parsed.Ok(KeyRotateTerms(epoch, hash.value))
            is Parsed.Invalid -> hash
        }
    }

    /** The new epoch alone, for callers that do not care about the commitment. */
    fun keyRotate(map: CborMap): Parsed<Int> = when (val terms = keyRotateTerms(map)) {
        is Parsed.Ok -> Parsed.Ok(terms.value.epoch)
        is Parsed.Invalid -> terms
    }

    fun rotatePublic(epoch: Int, keyHash: ByteString? = null): CborMap =
        CborMap(buildMap { put(KEY_EPOCH, CborInt(epoch.toLong())); keyHash?.let { put(KEY_HASH, CborBytes(it)) } })

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

/** Seals and opens the secret part of an event (ADR 0021). */
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

    /** The payload map of an event; invalid if the key is missing, wrong, or the event was altered. */
    fun open(cipher: Cipher, keys: KeyRing, envelope: Envelope): Parsed<CborMap> {
        if (envelope.v != PAYLOAD_VERSION_SEALED) return Parsed.Invalid("unsupported format version ${envelope.v}")
        if (envelope.encrypted.isEmpty()) return Parsed.Ok(CborMap.EMPTY)
        val key = keys[envelope.epoch] ?: return Parsed.Invalid("no circle key for epoch ${envelope.epoch}")
        val joined = ByteString.of(envelope.nonce.toByteArray() + envelope.encrypted.toByteArray())
        val plain = (cipher.decrypt(key, joined, envelope.associatedData()) as? DecryptResult.Success)?.plaintext
            ?: return Parsed.Invalid("payload does not decrypt")
        return (CborDecoder.decode(plain) as? Parsed.Ok)?.value?.let { it as? CborMap }?.let { Parsed.Ok(it) }
            ?: Parsed.Invalid("payload is not a CBOR map")
    }
}
