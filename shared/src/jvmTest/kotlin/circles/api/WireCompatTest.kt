package circles.api

import circles.event.EnvelopeDto
import circles.export.ExportBlobRef
import circles.export.ExportManifest
import circles.keys.StoredIdentity
import circles.keys.WrappedSeed
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The hand-written wire JSON and kotlinx (which the server uses) agree on every shape the browser sends or reads (ADR 0054). */
class WireCompatTest {
    private fun envelope(extra: String = "") = EnvelopeDto(1, "a".repeat(32), "b".repeat(32), "c".repeat(32), "item.upsert$extra", 20_000, 9_007_199_254_740_993L, 3, "cHVi", "bm9uY2U", "ZW5j", "c2ln")
    private val wrapped = WrappedSeed(1, "argon2id", 65_536, 3, 1, "c2FsdA", "c2VhbGVk")

    @Test
    fun whatTheBrowserWritesIsByteForByteWhatKotlinxWrites() {
        val e = envelope(" \"quoted\" \\ \n é €")
        assertEquals(ApiJson.encodeToString(EnvelopeDto.serializer(), e), Wire.encode(e))
        assertEquals(ApiJson.encodeToString(SingleEventRequest.serializer(), SingleEventRequest(e)), Wire.encode(SingleEventRequest(e)))
        assertEquals(ApiJson.encodeToString(PushRequest.serializer(), PushRequest(listOf(e, envelope()))), Wire.encode(PushRequest(listOf(e, envelope()))))
        assertEquals(ApiJson.encodeToString(PushRequest.serializer(), PushRequest(emptyList())), Wire.encode(PushRequest(emptyList())))
        assertEquals(ApiJson.encodeToString(ChallengeRequest.serializer(), ChallengeRequest("d")), Wire.encode(ChallengeRequest("d")))
        assertEquals(ApiJson.encodeToString(SessionRequest.serializer(), SessionRequest("d", "n", "s")), Wire.encode(SessionRequest("d", "n", "s")))
        for (id in listOf(StoredIdentity("m", "d", wrapped), StoredIdentity("m", "d", wrapped, "c", 15, true), StoredIdentity("m", "d", wrapped, null, 0, false), StoredIdentity("m", "d", wrapped, "c", null, false, circles.keys.PasskeyWrap("Y3JlZA", "c2FsdA", "c2VhbGVk")))) {
            // kotlinx omits fields that equal their defaults only when encodeDefaults is off (it is), and so does Wire.
            assertEquals(ApiJson.encodeToString(StoredIdentity.serializer(), id), Wire.encode(id))
        }
        val manifest = ExportManifest("circles-export/1", "a".repeat(32), 3, 3L, "e".repeat(64), listOf(ExportBlobRef("b".repeat(64), 12), ExportBlobRef("c".repeat(64), 1L shl 40)))
        assertEquals(ApiJson.encodeToString(ExportManifest.serializer(), manifest), Wire.encode(manifest))
    }

    @Test
    fun everyControlCharacterIsEscapedTheSameWay() {
        val text = (0..160).map { it.toChar() }.joinToString("")
        assertEquals(ApiJson.encodeToString(ChallengeRequest.serializer(), ChallengeRequest(text)), Wire.encode(ChallengeRequest(text)))
    }

    @Test
    fun whatKotlinxWritesTheBrowserReadsBack() {
        val e = envelope(" é")
        assertEquals(e, Wire.decodeEnvelope(ApiJson.encodeToString(EnvelopeDto.serializer(), e)))
        assertEquals(ChallengeResponse("nn"), Wire.decodeChallenge(ApiJson.encodeToString(ChallengeResponse.serializer(), ChallengeResponse("nn"))))
        assertEquals(SessionResponse("tok", 900), Wire.decodeSession(ApiJson.encodeToString(SessionResponse.serializer(), SessionResponse("tok", 900))))
        val results = listOf(
            PushResult("i".repeat(64), PushStatus.ACCEPTED, 12), PushResult(null, PushStatus.REJECTED, reason = "bad_signature", detail = "d"),
            PushResult("j", PushStatus.DUPLICATE, 3), PushResult(status = PushStatus.REJECTED),
        )
        assertEquals(results, Wire.decodePushResults(ApiJson.encodeToString(ListSerializer(PushResult.serializer()), results)))
        for (r in results) assertEquals(r, Wire.decodePushResult(ApiJson.encodeToString(PushResult.serializer(), r)))
        val pull = PullResponse(listOf(StoredEvent(1, e), StoredEvent(2, envelope())), 2, true)
        assertEquals(pull, Wire.decodePull(ApiJson.encodeToString(PullResponse.serializer(), pull)))
        assertEquals(PullResponse(emptyList(), 0, false), Wire.decodePull(ApiJson.encodeToString(PullResponse.serializer(), PullResponse(emptyList(), 0, false))))
        for (id in listOf(StoredIdentity("m", "d", wrapped), StoredIdentity("m", "d", wrapped, "c", 15, true), StoredIdentity("m", "d", wrapped, passkey = circles.keys.PasskeyWrap("Y3JlZA", "c2FsdA", "c2VhbGVk")))) {
            assertEquals(id, Wire.decodeStoredIdentity(ApiJson.encodeToString(StoredIdentity.serializer(), id)))
        }
        // An explicit null is read as absent, as kotlinx does with explicitNulls off.
        assertEquals(StoredIdentity("m", "d", wrapped), Wire.decodeStoredIdentity(Wire.encode(StoredIdentity("m", "d", wrapped)).dropLast(1) + ""","circle":null,"autoLockMinutes":null}"""))
    }

    @Test
    fun theBrowserRefusesWhatKotlinxRefuses() {
        val good = ApiJson.encodeToString(EnvelopeDto.serializer(), envelope())
        assertNotNull(Wire.decodeEnvelope(good))
        for (bad in listOf(
            good.dropLast(1) + ""","extra":1}""", good.replace("\"sig\":\"c2ln\"", "\"sig\":5"), good.replace("\"v\":1,", ""), good.replace("\"day\":20000", "\"day\":2.5"),
            good.replace("\"epoch\":3", "\"epoch\":99999999999"), "[]", "null", good + " x", "",
        )) {
            assertNull(Wire.decodeEnvelope(bad), bad)
            val kotlinxAccepts = runCatching { ApiJson.decodeFromString(EnvelopeDto.serializer(), bad) }.isSuccess
            assertEquals(false, kotlinxAccepts, "kotlinx also refuses: $bad")
        }
        assertNull(Wire.decodePushResult("""{"status":"maybe"}"""))
        assertNull(Wire.decodePushResult("""{"status":"accepted","serverSeq":"7"}"""))
        assertNull(Wire.decodeSession("""{"token":"t"}"""))
        assertNull(Wire.decodePull("""{"events":[{"serverSeq":1}],"next":1,"hasMore":false}"""))
        assertNull(Wire.decodeStoredIdentity("""{"member":"m","device":"d"}"""))
        assertNull(Wire.decodeChallenge("""{"nonce":"n","other":1}"""))
    }
}
