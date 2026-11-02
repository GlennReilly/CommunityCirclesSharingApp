package circles.event

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
    private val ok = EnvelopeVectors.withFakeSig

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
        assertNull(EnvelopeChecks.check(ok.copy(encrypted = atLimit)))
        assertEquals(RejectionCode.TOO_LARGE, code(ok.copy(encrypted = payloadOfEncodedSize(MAX_ENCRYPTED_BYTES + 1))))
    }

    @Test
    fun refusesOversizedPublic() {
        val big = CborMap.of("x" to CborText("a".repeat(MAX_PUBLIC_BYTES)))
        assertEquals(RejectionCode.TOO_LARGE, code(ok.copy(public = big)))
    }

    @Test
    fun payloadIsEmptyMapWhenNothingIsEncrypted() {
        assertEquals(Parsed.Ok(CborMap.EMPTY), EnvelopeChecks.payload(ok))
    }

    @Test
    fun payloadMustBeCanonicalCborMap() {
        val good = ByteString.of(CborEncoder.encode(CborMap.of("note" to CborText("hi"))))
        assertEquals(Parsed.Ok(CborMap.of("note" to CborText("hi"))), EnvelopeChecks.payload(ok.copy(encrypted = good)))
        listOf("83010203", "a2616201616102", "ff").forEach {
            val envelope = ok.copy(encrypted = ByteString.fromHex(it)!!)
            assertIs<Parsed.Invalid>(EnvelopeChecks.payload(envelope), "payload $it")
            assertEquals(RejectionCode.INVALID_PAYLOAD, code(envelope))
        }
        assertIs<Parsed.Ok<*>>(EnvelopeChecks.payload(ok.copy(encrypted = ByteString.of(CborEncoder.encode(CborMap.of("n" to CborInt(1)))))))
    }
}
