package circles.event

import circles.crypto.BoxKeyPair
import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.crypto.DecryptResult
import circles.crypto.HASH_BYTES
import circles.encoding.ByteString
import circles.encoding.CborBytes
import circles.encoding.CborDecoder
import circles.encoding.CborEncoder
import circles.encoding.CborInt
import circles.encoding.CborMap
import circles.encoding.CborText
import circles.encoding.Parsed
import circles.policy.CircleId
import circles.recovery.CombineResult
import circles.recovery.Shamir
import circles.recovery.SplitResult

private const val KEY_CIRCLE = "circle"
private const val KEY_EPOCH = "epoch"
private const val KEY_SHARE = "share"
private const val KEY_CHECK = "check"

/** One opened recovery share and the hash of the circle key it belongs to. */
class RecoveryShare(val share: ByteString, val check: ByteString)

/**
 * `recovery.share` (spec 3.3, ADR 0020): the circle key of an epoch is split k-of-n and one share is sealed to each
 * steward device. `public` has the same shape as `key.wrap` (`{device, epoch, wrapped}`); only the sealed content differs.
 */
object RecoveryShares {
    /** Splits [key]; the result pairs each steward device with its sealed share. Null if the parameters are invalid. */
    fun split(
        crypto: Crypto,
        cipher: Cipher,
        circle: CircleId,
        epoch: Int,
        key: ByteString,
        stewards: List<DeviceRegistration>,
        threshold: Int,
    ): List<Pair<DeviceRegistration, ByteString>>? {
        val split = Shamir.split(key, threshold, stewards.size, crypto::randomBytes) as? SplitResult.Success ?: return null
        val check = crypto.hash256(key.toByteArray())
        return stewards.zip(split.shares).map { (device, share) ->
            val content = CborMap.of(
                KEY_CIRCLE to CborText(circle.v),
                KEY_EPOCH to CborInt(epoch.toLong()),
                KEY_SHARE to CborBytes(share),
                KEY_CHECK to CborBytes(check),
            )
            device to cipher.sealBox(device.boxPub, CborEncoder.encode(content))
        }
    }

    /** Opens a share sealed to this device; invalid if it is for another circle or epoch than the event names. */
    fun open(cipher: Cipher, box: BoxKeyPair, circle: CircleId, epoch: Int, wrapped: ByteString): Parsed<RecoveryShare> {
        val plain = (cipher.openSealedBox(box, wrapped) as? DecryptResult.Success)?.plaintext ?: return Parsed.Invalid("share cannot be opened by this device")
        val map = (CborDecoder.decode(plain) as? Parsed.Ok)?.value as? CborMap ?: return Parsed.Invalid("share is not a CBOR map")
        if (map.entries.keys != setOf(KEY_CIRCLE, KEY_EPOCH, KEY_SHARE, KEY_CHECK)) return Parsed.Invalid("share has the wrong fields")
        if ((map[KEY_CIRCLE] as? CborText)?.value != circle.v) return Parsed.Invalid("share is for another circle")
        if ((map[KEY_EPOCH] as? CborInt)?.value != epoch.toLong()) return Parsed.Invalid("share is for another epoch")
        val share = (map[KEY_SHARE] as? CborBytes)?.value ?: return Parsed.Invalid("share is missing")
        val check = (map[KEY_CHECK] as? CborBytes)?.value?.takeIf { it.size == HASH_BYTES } ?: return Parsed.Invalid("share check has the wrong length")
        return Parsed.Ok(RecoveryShare(share, check))
    }

    /** Rebuilds the circle key from stewards' opened shares; invalid unless enough right shares of one key are given. */
    fun recover(crypto: Crypto, shares: List<RecoveryShare>): Parsed<ByteString> {
        val check = shares.firstOrNull()?.check ?: return Parsed.Invalid("no shares")
        if (shares.any { it.check != check }) return Parsed.Invalid("shares belong to different keys")
        return when (val result = Shamir.combineVerified(shares.map { it.share }, check, crypto)) {
            is CombineResult.Success -> Parsed.Ok(result.secret)
            is CombineResult.Failure -> Parsed.Invalid("cannot recover the key: ${result.reason}")
        }
    }
}
