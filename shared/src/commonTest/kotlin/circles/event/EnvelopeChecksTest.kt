package circles.event

import circles.crypto.AEAD_NONCE_BYTES
import circles.encoding.ByteString
import circles.encoding.CborBytes
import circles.encoding.CborEncoder
import circles.encoding.CborInt
import circles.encoding.CborMap
import circles.encoding.CborText
import circles.encoding.Parsed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private const val PAYLOAD_OVERHEAD = 6

class EnvelopeChecksTest {
    // The vector pins the byte encoding with v = 0, which is only an integer there; a checkable envelope needs v = 1.
    private val ok = EnvelopeVectors.withFakeSig.copy(v = PAYLOAD_VERSION_SEALED)

    private fun code(envelope: Envelope): RejectionCode? = EnvelopeChecks.check(envelope)?.code

    @Test
    fun acceptsAWellFormedEnvelope() {
        assertNull(EnvelopeChecks.check(ok))
    }

    @Test
    fun refusesOtherPayloadVersions() {
        assertEquals(RejectionCode.UNSUPPORTED_VERSION, code(ok.copy(v = 2)))
        assertEquals(RejectionCode.UNSUPPORTED_VERSION, code(ok.copy(v = -1)))
    }

    @Test
    fun refusesNegativeCounters() {
        assertEquals(RejectionCode.MALFORMED, code(ok.copy(day = -1)))
        assertEquals(RejectionCode.MALFORMED, code(ok.copy(ctr = -1)))
        assertEquals(RejectionCode.MALFORMED, code(ok.copy(epoch = -1)))
    }

    @Test
    fun refusesWrongSizedSignatureAndNonEmptyNonce() {
        assertEquals(RejectionCode.MALFORMED, code(ok.copy(sig = ByteString.fromHex("00")!!)))
        assertEquals(RejectionCode.MALFORMED, code(ok.copy(sig = ByteString.EMPTY)))
        assertEquals(RejectionCode.MALFORMED, code(ok.copy(nonce = ByteString.of(ByteArray(24)))))
    }

    /** The payload {"k": <n bytes>} encodes to 6 + n bytes when 256 <= n < 65536 (map head, key, 3-byte length). */
    private fun payloadOfEncodedSize(total: Int): ByteString =
        ByteString.of(CborEncoder.encode(CborMap.of("k" to CborBytes(ByteString.of(ByteArray(total - PAYLOAD_OVERHEAD))))))

    @Test
    fun enforcesTheEncryptedSizeLimitAtTheBoundary() {
        val atLimit = payloadOfEncodedSize(MAX_ENCRYPTED_BYTES)
        assertEquals(MAX_ENCRYPTED_BYTES, atLimit.size)
        val sealed = ok.copy(epoch = FIRST_EPOCH, nonce = ByteString.of(ByteArray(AEAD_NONCE_BYTES)))
        assertNull(EnvelopeChecks.check(sealed.copy(encrypted = atLimit)))
        assertEquals(RejectionCode.TOO_LARGE, code(sealed.copy(encrypted = payloadOfEncodedSize(MAX_ENCRYPTED_BYTES + 1))))
    }

    @Test
    fun refusesOversizedPublic() {
        val big = CborMap.of("x" to CborText("a".repeat(MAX_PUBLIC_BYTES)))
        assertEquals(RejectionCode.TOO_LARGE, code(ok.copy(public = big)))
    }
}
