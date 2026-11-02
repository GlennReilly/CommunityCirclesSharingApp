package circles.event

import circles.encoding.ByteString
import circles.encoding.CborInt
import circles.encoding.CborMap
import circles.encoding.Parsed
import circles.policy.CircleId
import circles.policy.MemberId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals

class EnvelopeTest {
    private val base = EnvelopeVectors.withFakeSig

    private fun hex(bytes: ByteArray): String = ByteString.of(bytes).toHex()

    @Test
    fun signingBytesMatchTheReferenceVector() {
        assertEquals(EnvelopeVectors.SIGNING_HEX, hex(EnvelopeVectors.unsigned.signingBytes()))
    }

    @Test
    fun signingBytesIgnoreTheSignatureButCanonicalBytesIncludeIt() {
        assertContentEquals(EnvelopeVectors.unsigned.signingBytes(), base.signingBytes())
        assertEquals(EnvelopeVectors.CANONICAL_HEX, hex(base.canonicalBytes()))
        assertNotEquals(hex(base.signingBytes()), hex(base.canonicalBytes()))
    }

    @Test
    fun everySignedFieldChangesTheSigningBytes() {
        val original = hex(base.signingBytes())
        val variants = listOf(
            base.copy(v = 1),
            base.copy(circle = CircleId("12".repeat(16))),
            base.copy(author = MemberId("23".repeat(16))),
            base.copy(device = DeviceId("34".repeat(16))),
            base.copy(type = "circle.creat"),
            base.copy(day = 20001),
            base.copy(ctr = 1),
            base.copy(epoch = 1),
            base.copy(public = CborMap.EMPTY),
            base.copy(public = CborMap(base.public.entries + ("extra" to CborInt(1)))),
            base.copy(nonce = ByteString.fromHex("00")!!),
            base.copy(encrypted = ByteString.fromHex("a0")!!),
        )
        variants.forEach { assertNotEquals(original, hex(it.signingBytes()), "changing $it must change the bytes") }
    }

    @Test
    fun dtoRoundTripKeepsTheEnvelopeAndItsCanonicalBytes() {
        val back = base.toDto().toEnvelope()
        assertIs<Parsed.Ok<Envelope>>(back)
        assertEquals(base, back.value)
        assertContentEquals(base.canonicalBytes(), back.value.canonicalBytes())
    }

    @Test
    fun dtoRefusesBadIdsAndBadEncodings() {
        val dto = base.toDto()
        assertIs<Parsed.Invalid>(dto.copy(circle = "ABC").toEnvelope())
        assertIs<Parsed.Invalid>(dto.copy(author = "22".repeat(15)).toEnvelope())
        assertIs<Parsed.Invalid>(dto.copy(device = "g".repeat(32)).toEnvelope())
        assertIs<Parsed.Invalid>(dto.copy(sig = "not base64!").toEnvelope())
        assertIs<Parsed.Invalid>(dto.copy(nonce = "a b").toEnvelope())
        assertIs<Parsed.Invalid>(dto.copy(type = "x".repeat(MAX_TYPE_CHARS + 1)).toEnvelope())
    }

    @Test
    fun dtoRefusesPublicThatIsNotCanonicalCborMap() {
        val unsortedKeys = ByteString.fromHex("a2616201616102")!!.toBase64Url()
        val notAMap = ByteString.fromHex("83010203")!!.toBase64Url()
        val trailing = ByteString.fromHex("a000")!!.toBase64Url()
        val dto = base.toDto()
        listOf(unsortedKeys, notAMap, trailing, "!!").forEach {
            assertIs<Parsed.Invalid>(dto.copy(public = it).toEnvelope(), "public $it must be refused")
        }
        assertIs<Parsed.Ok<Envelope>>(dto.copy(public = ByteString.fromHex("a0")!!.toBase64Url()).toEnvelope())
    }

    @Test
    fun domainMessageJoinsTheDomainAndTheParts() {
        val message = domainMessage("d/", byteArrayOf(1), byteArrayOf(2, 3))
        assertContentEquals(byteArrayOf('d'.code.toByte(), '/'.code.toByte(), 1, 2, 3), message)
    }
}
