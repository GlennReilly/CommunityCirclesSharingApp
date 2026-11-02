package circles.event

import circles.crypto.ED25519_SIGNATURE_BYTES
import circles.encoding.CborDecoder
import circles.encoding.CborMap
import circles.encoding.Parsed

/** Size limits from spec 3.1 and ADR 0014. */
const val MAX_ENCRYPTED_BYTES = 64 * 1024
const val MAX_PUBLIC_BYTES = 16 * 1024

/** Checks that need no state: shape, sizes and the payload version. Signature and role checks come later. */
object EnvelopeChecks {
    fun check(envelope: Envelope): Rejection? {
        if (envelope.v != PAYLOAD_VERSION_PLAINTEXT) {
            return Rejection(RejectionCode.UNSUPPORTED_VERSION, "only v=$PAYLOAD_VERSION_PLAINTEXT (plaintext development format) is accepted")
        }
        if (envelope.day < 0 || envelope.ctr < 0 || envelope.epoch < 0) {
            return Rejection(RejectionCode.MALFORMED, "day, ctr and epoch must not be negative")
        }
        if (envelope.sig.size != ED25519_SIGNATURE_BYTES) {
            return Rejection(RejectionCode.MALFORMED, "sig must be $ED25519_SIGNATURE_BYTES bytes")
        }
        if (!envelope.nonce.isEmpty()) {
            return Rejection(RejectionCode.MALFORMED, "nonce must be empty in the plaintext format")
        }
        if (envelope.encrypted.size > MAX_ENCRYPTED_BYTES) {
            return Rejection(RejectionCode.TOO_LARGE, "encrypted is larger than $MAX_ENCRYPTED_BYTES bytes")
        }
        if (envelope.publicBytes().size > MAX_PUBLIC_BYTES) {
            return Rejection(RejectionCode.TOO_LARGE, "public is larger than $MAX_PUBLIC_BYTES bytes")
        }
        return (payload(envelope) as? Parsed.Invalid)?.let { Rejection(RejectionCode.INVALID_PAYLOAD, it.reason) }
    }

    /** In the plaintext format `encrypted` holds the canonical CBOR of the payload map; empty means no payload. */
    fun payload(envelope: Envelope): Parsed<CborMap> {
        if (envelope.encrypted.isEmpty()) return Parsed.Ok(CborMap.EMPTY)
        return when (val decoded = CborDecoder.decode(envelope.encrypted.toByteArray())) {
            is Parsed.Ok -> (decoded.value as? CborMap)?.let { Parsed.Ok(it) } ?: Parsed.Invalid("payload must be a CBOR map")
            is Parsed.Invalid -> Parsed.Invalid("payload is not canonical CBOR: ${decoded.reason}")
        }
    }
}
