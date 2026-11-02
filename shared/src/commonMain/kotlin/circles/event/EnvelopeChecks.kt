package circles.event

import circles.crypto.AEAD_NONCE_BYTES
import circles.crypto.AEAD_TAG_BYTES
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
        if (envelope.v != PAYLOAD_VERSION_PLAINTEXT && envelope.v != PAYLOAD_VERSION_SEALED) {
            return Rejection(RejectionCode.UNSUPPORTED_VERSION, "v must be $PAYLOAD_VERSION_PLAINTEXT (development) or $PAYLOAD_VERSION_SEALED (sealed)")
        }
        if (envelope.day < 0 || envelope.ctr < 0 || envelope.epoch < 0) {
            return Rejection(RejectionCode.MALFORMED, "day, ctr and epoch must not be negative")
        }
        if (envelope.sig.size != ED25519_SIGNATURE_BYTES) {
            return Rejection(RejectionCode.MALFORMED, "sig must be $ED25519_SIGNATURE_BYTES bytes")
        }
        sealedShapeProblem(envelope)?.let { return it }
        if (envelope.encrypted.size > MAX_ENCRYPTED_BYTES) {
            return Rejection(RejectionCode.TOO_LARGE, "encrypted is larger than $MAX_ENCRYPTED_BYTES bytes")
        }
        if (envelope.publicBytes().size > MAX_PUBLIC_BYTES) {
            return Rejection(RejectionCode.TOO_LARGE, "public is larger than $MAX_PUBLIC_BYTES bytes")
        }
        if (envelope.v == PAYLOAD_VERSION_SEALED) return null // the server cannot read a sealed payload
        return (payload(envelope) as? Parsed.Invalid)?.let { Rejection(RejectionCode.INVALID_PAYLOAD, it.reason) }
    }

    /** Nonce and ciphertext shape: plaintext format has no nonce; sealed has both or neither, with room for the tag. */
    private fun sealedShapeProblem(envelope: Envelope): Rejection? {
        if (envelope.v == PAYLOAD_VERSION_PLAINTEXT) {
            return if (envelope.nonce.isEmpty()) null else Rejection(RejectionCode.MALFORMED, "nonce must be empty in the plaintext format")
        }
        val sealed = !envelope.encrypted.isEmpty()
        return when {
            sealed && envelope.nonce.size != AEAD_NONCE_BYTES -> Rejection(RejectionCode.MALFORMED, "nonce must be $AEAD_NONCE_BYTES bytes")
            sealed && envelope.encrypted.size < AEAD_TAG_BYTES -> Rejection(RejectionCode.MALFORMED, "encrypted is shorter than its tag")
            sealed && envelope.epoch < FIRST_EPOCH -> Rejection(RejectionCode.MALFORMED, "a sealed payload needs a circle key epoch of at least $FIRST_EPOCH")
            !sealed && !envelope.nonce.isEmpty() -> Rejection(RejectionCode.MALFORMED, "nonce must be empty when encrypted is empty")
            else -> null
        }
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
