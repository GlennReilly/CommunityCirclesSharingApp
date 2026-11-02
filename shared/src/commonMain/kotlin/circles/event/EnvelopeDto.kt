package circles.event

import circles.encoding.ByteString
import circles.encoding.CborDecoder
import circles.encoding.CborMap
import circles.encoding.Parsed
import circles.policy.CircleId
import circles.policy.MemberId
import kotlinx.serialization.Serializable

/** Longest event type name accepted on the wire. */
const val MAX_TYPE_CHARS = 64

/**
 * The JSON form of an [Envelope] (ADR 0012): byte fields are base64url without padding, and `public` is the
 * base64url of its canonical CBOR, carried verbatim.
 */
@Serializable
data class EnvelopeDto(
    val v: Int,
    val circle: String,
    val author: String,
    val device: String,
    val type: String,
    val day: Int,
    val ctr: Long,
    val epoch: Int,
    val public: String,
    val nonce: String,
    val encrypted: String,
    val sig: String,
) {
    /** Rebuilds the envelope, or says why the JSON is not a well-formed one. Nothing is trusted yet. */
    fun toEnvelope(): Parsed<Envelope> {
        if (!isValidRandomId(circle) || !isValidRandomId(author) || !isValidRandomId(device)) {
            return Parsed.Invalid("circle, author and device must be 32 lowercase hex characters")
        }
        if (type.length > MAX_TYPE_CHARS) return Parsed.Invalid("type is too long")
        val publicBytes = ByteString.fromBase64Url(public) ?: return Parsed.Invalid("public is not base64url")
        val publicMap = when (val decoded = CborDecoder.decode(publicBytes.toByteArray())) {
            is Parsed.Ok -> decoded.value as? CborMap ?: return Parsed.Invalid("public must be a CBOR map")
            is Parsed.Invalid -> return Parsed.Invalid("public is not canonical CBOR: ${decoded.reason}")
        }
        val nonceBytes = ByteString.fromBase64Url(nonce) ?: return Parsed.Invalid("nonce is not base64url")
        val encryptedBytes = ByteString.fromBase64Url(encrypted) ?: return Parsed.Invalid("encrypted is not base64url")
        val sigBytes = ByteString.fromBase64Url(sig) ?: return Parsed.Invalid("sig is not base64url")
        return Parsed.Ok(
            Envelope(v, CircleId(circle), MemberId(author), DeviceId(device), type, day, ctr, epoch, publicMap, nonceBytes, encryptedBytes, sigBytes),
        )
    }
}

fun Envelope.toDto(): EnvelopeDto =
    EnvelopeDto(
        v = v,
        circle = circle.v,
        author = author.v,
        device = device.v,
        type = type,
        day = day,
        ctr = ctr,
        epoch = epoch,
        public = ByteString.of(publicBytes()).toBase64Url(),
        nonce = nonce.toBase64Url(),
        encrypted = encrypted.toBase64Url(),
        sig = sig.toBase64Url(),
    )
