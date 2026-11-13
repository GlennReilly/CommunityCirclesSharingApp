package circles.sync

import circles.api.API_PREFIX
import circles.api.ApiJson
import circles.api.ChallengeResponse
import circles.api.MAX_PUSH_EVENTS
import circles.api.PullResponse
import circles.api.PushRequest
import circles.api.PushResult
import circles.api.PushStatus
import circles.api.SessionResponse
import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.event.DeviceIdentity
import circles.event.EventAuthor
import circles.event.InviteKey
import circles.event.newCircleId
import circles.policy.CircleId
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val DAY = 20_000

/** How `HttpSyncApi` talks to the server and reads its answers, against a scripted transport, on every Crypto/Cipher pair. */
abstract class HttpSyncApiContract {
    protected abstract suspend fun newCrypto(): Crypto
    protected abstract suspend fun newCipher(): Cipher

    private class Call(val method: String, val path: String, val token: String?, val body: String?)

    private class Script(val crypto: Crypto) {
        val calls = mutableListOf<Call>()
        /** Answers for non-login calls, taken in order; the last one repeats. */
        val answers = ArrayDeque<HttpReply?>()
        var tokens = 0
        var loginStatus = 200

        val transport = HttpTransport { method, path, body, token ->
            calls += Call(method, path, token, body)
            when {
                path.endsWith("/auth/challenge") ->
                    if (loginStatus != 200) HttpReply(loginStatus, "{}") else HttpReply(200, ApiJson.encodeToString(ChallengeResponse.serializer(), ChallengeResponse(crypto.randomBytes(16).toBase64Url())))
                path.endsWith("/auth/session") -> HttpReply(200, ApiJson.encodeToString(SessionResponse.serializer(), SessionResponse("token-${++tokens}", 3600)))
                else -> if (answers.size > 1) answers.removeFirst() else answers.firstOrNull()
            }
        }
    }

    private class World(val crypto: Crypto, cipher: Cipher) {
        val script = Script(crypto)
        val me = DeviceIdentity.generate(crypto, cipher)
        val author = EventAuthor(crypto, me, cipher)
        val circle: CircleId = newCircleId(crypto)
        val api = HttpSyncApi(script.transport, crypto, me)
        fun invite() = author.invite(circle, InviteKey(crypto.generateSigningKeyPair()), DAY + 1, 1, DAY)
        fun accepted(count: Int) = HttpReply(200, ApiJson.encodeToString(ListSerializer(PushResult.serializer()), List(count) { PushResult("a".repeat(32), PushStatus.ACCEPTED, it + 1L) }))
        val emptyPull = HttpReply(200, ApiJson.encodeToString(PullResponse.serializer(), PullResponse(emptyList(), 0, false)))
    }

    private suspend fun world() = World(newCrypto(), newCipher())

    @Test fun loggingInHappensOnceAndTheTokenIsReused() = runTest {
        val w = world()
        w.script.answers += w.emptyPull
        w.api.pull(w.circle, 0, 10)
        w.api.pull(w.circle, 0, 10)
        assertEquals(1, w.script.calls.count { it.path.endsWith("/auth/session") })
        assertTrue(w.script.calls.filter { it.path.contains("/events") }.all { it.token == "token-1" })
    }

    @Test fun anExpiredTokenIsRenewedOnceAndTheCallRepeated() = runTest {
        val w = world()
        w.script.answers += HttpReply(401, "{}")
        w.script.answers += w.emptyPull
        val outcome = w.api.pull(w.circle, 0, 10)
        assertIs<ApiOutcome.Ok<PullResponse>>(outcome)
        assertEquals(2, w.script.tokens)
    }

    @Test fun aSecondUnauthorizedAnswerGivesUp() = runTest {
        val w = world()
        w.script.answers += HttpReply(401, "{}")
        assertEquals(ApiOutcome.Unauthorized, w.api.pull(w.circle, 0, 10))
        assertEquals(2, w.script.tokens)
    }

    @Test fun aLoginTheServerRefusesIsUnauthorized() = runTest {
        val w = world()
        w.script.loginStatus = 401
        assertEquals(ApiOutcome.Unauthorized, w.api.pull(w.circle, 0, 10))
        w.script.loginStatus = 403
        assertEquals(ApiOutcome.Unauthorized, w.api.pull(w.circle, 0, 10))
    }

    @Test fun noConnectionIsOffline() = runTest {
        val w = world()
        w.script.answers += null
        assertEquals(ApiOutcome.Offline, w.api.pull(w.circle, 0, 10))
        assertEquals(ApiOutcome.Offline, HttpSyncApi({ _, _, _, _ -> null }, w.crypto, w.me).pull(w.circle, 0, 10))
    }

    @Test fun busyAnswersCarryRetryAfter() = runTest {
        val w = world()
        w.script.answers += HttpReply(429, "{}", retryAfter = " 30 ")
        assertEquals(ApiOutcome.Busy(30), w.api.pull(w.circle, 0, 10))
        w.script.answers.clear(); w.script.answers += HttpReply(503, "{}")
        assertEquals(ApiOutcome.Busy(null), w.api.pull(w.circle, 0, 10))
        w.script.answers.clear(); w.script.answers += HttpReply(429, "{}", retryAfter = "Wed, 21 Oct 2026 07:28:00 GMT")
        assertEquals(ApiOutcome.Busy(null), w.api.pull(w.circle, 0, 10))
    }

    @Test fun otherRefusalsAreFinal() = runTest {
        val w = world()
        w.script.answers += HttpReply(403, "{}")
        assertEquals(ApiOutcome.Refused(403, "The server did not say why."), w.api.pull(w.circle, 0, 10))
    }

    @Test fun anAnswerThatIsNotJsonIsRefusedNotACrash() = runTest {
        val w = world()
        w.script.answers += HttpReply(200, "<html>proxy error</html>")
        assertIs<ApiOutcome.Refused>(w.api.pull(w.circle, 0, 10))
        w.script.answers.clear(); w.script.answers += HttpReply(200, """{"events":[],"next":0,"hasMore":false,"extra":1}""")
        assertIs<ApiOutcome.Refused>(w.api.pull(w.circle, 0, 10))
    }

    @Test fun pullAsksForTheRightPage() = runTest {
        val w = world()
        w.script.answers += w.emptyPull
        w.api.pull(w.circle, 42, 500)
        assertEquals("$API_PREFIX/circles/${w.circle.v}/events?after=42&limit=500", w.script.calls.last().path)
        assertEquals("GET", w.script.calls.last().method)
    }

    @Test fun createAndJoinUseTheirPublicEndpointsWithoutALogin() = runTest {
        val w = world()
        val invite = w.invite()
        val create = w.author.circleCreate(w.circle, DAY)
        val cipher = newCipher()
        val joiner = EventAuthor(w.crypto, DeviceIdentity.generate(w.crypto, cipher), cipher)
        val join = joiner.join(w.circle, invite.eventId(w.crypto), InviteKey(w.crypto.generateSigningKeyPair()), DAY)
        w.script.answers += HttpReply(201, ApiJson.encodeToString(PushResult.serializer(), PushResult("x", PushStatus.ACCEPTED, 1)))
        val outcome = w.api.push(w.circle, listOf(create, join))
        assertEquals(2, (outcome as ApiOutcome.Ok).value.size)
        assertEquals(listOf("$API_PREFIX/circles", "$API_PREFIX/circles/${w.circle.v}/join"), w.script.calls.map { it.path })
        assertTrue(w.script.calls.all { it.token == null })
    }

    @Test fun aRefusedCreateBecomesARejectedResultForThatEvent() = runTest {
        val w = world()
        val create = w.author.circleCreate(w.circle, DAY)
        w.script.answers += HttpReply(403, """{"title":"Circle creation is disabled","status":403}""")
        val results = (w.api.push(w.circle, listOf(create)) as ApiOutcome.Ok).value
        assertEquals(PushStatus.REJECTED, results.single().status)
        assertEquals("http_403", results.single().reason)
        assertEquals(create.eventId(w.crypto).v, results.single().id)
    }

    @Test fun ordinaryEventsAreBatchedAndSplitAtTheServersLimit() = runTest {
        val w = world()
        val events = List(MAX_PUSH_EVENTS + 5) { w.invite() }
        w.script.answers += w.accepted(MAX_PUSH_EVENTS)
        w.script.answers += w.accepted(5)
        val outcome = w.api.push(w.circle, events)
        assertEquals(MAX_PUSH_EVENTS + 5, (outcome as ApiOutcome.Ok).value.size)
        val pushes = w.script.calls.filter { it.path.endsWith("/events") && it.method == "POST" }
        assertEquals(2, pushes.size)
        assertEquals(MAX_PUSH_EVENTS, ApiJson.decodeFromString(PushRequest.serializer(), pushes[0].body!!).events.size)
    }

    @Test fun orderIsKeptAcrossPublicAndAuthenticatedEvents() = runTest {
        val w = world()
        val a = w.invite()
        val create = w.author.circleCreate(w.circle, DAY)
        val b = w.invite()
        w.script.answers += w.accepted(1)
        w.script.answers += HttpReply(201, ApiJson.encodeToString(PushResult.serializer(), PushResult("x", PushStatus.ACCEPTED, 1)))
        w.script.answers += w.accepted(1)
        w.api.push(w.circle, listOf(a, create, b))
        val sent = w.script.calls.filter { it.method == "POST" && !it.path.contains("/auth/") }.map { it.path.substringAfter(API_PREFIX) }
        assertEquals(listOf("/circles/${w.circle.v}/events", "/circles", "/circles/${w.circle.v}/events"), sent)
    }

    @Test fun aFailureAfterPartialProgressIsReportedAsAFailure() = runTest {
        val w = world()
        val events = listOf(w.invite(), w.author.circleCreate(w.circle, DAY))
        w.script.answers += w.accepted(1)
        w.script.answers += null
        assertEquals(ApiOutcome.Offline, w.api.push(w.circle, events))
    }
}
