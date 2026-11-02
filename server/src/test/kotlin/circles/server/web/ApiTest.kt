package circles.server.web

import circles.api.API_PREFIX
import circles.api.ApiJson
import circles.api.MAX_PUSH_EVENTS
import circles.api.Problem
import circles.api.PushStatus
import circles.api.VersionResponse
import circles.event.DeviceIdentity
import circles.event.newCircleId
import circles.server.domain.Store
import circles.server.support.Person
import circles.server.support.TODAY
import circles.server.support.TestServer
import circles.server.support.apiTest
import circles.server.support.newInviteKey
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ApiTest {
    private val circlesPath = "$API_PREFIX/circles"

    private fun problem(body: String): Problem = ApiJson.decodeFromString(Problem.serializer(), body)

    // ---- probes ----

    @Test
    fun probesAnswerWithoutAuthentication() = apiTest {
        assertEquals("ok", api.raw("GET", "/healthz").body)
        assertEquals("ready", api.raw("GET", "/readyz").body)
        val version = ApiJson.decodeFromString(VersionResponse.serializer(), api.raw("GET", "/version").body)
        assertEquals(VersionResponse(SERVER_NAME, SERVER_VERSION), version)
    }

    @Test
    fun readyzFailsWhenTheStoreIsNotReady() = testApplication {
        TestServer().use { server ->
            val closed = Services(server.services.sync, server.services.auth, mockk<Store> { every { isReady() } returns false })
            application { module(closed) }
            val response = client.get("/readyz")
            val reply = Triple(response.status.value, response.headers["Content-Type"].orEmpty(), response.bodyAsText())
            assertEquals(503, reply.first)
            assertTrue(reply.second.contains("problem+json"))
        }
    }

    @Test
    fun anUnexpectedErrorBecomesAnOpaqueProblem() = testApplication {
        TestServer().use { server ->
            val failing = Services(server.services.sync, server.services.auth, mockk<Store> { every { isReady() } throws IllegalStateException("secret internals") })
            application { module(failing) }
            val response = client.get("/readyz")
            val reply = Triple(response.status.value, response.headers["Content-Type"].orEmpty(), response.bodyAsText())
            assertEquals(500, reply.first)
            assertTrue(reply.second.contains("problem+json"))
            assertFalse(reply.third.contains("secret internals") || reply.third.contains("IllegalStateException") || reply.third.contains("at circles."))
        }
    }

    // ---- creating and joining ----

    @Test
    fun creatingACircleIs201ThenAReplayIs200Duplicate() = apiTest {
        val alice = Person(server.crypto)
        val circle = newCircleId(server.crypto)
        val create = alice.author.circleCreate(circle, TODAY)
        val (first, result) = api.createCircle(create)
        assertEquals(201, first.status)
        assertEquals(PushStatus.ACCEPTED, result.status)
        assertEquals(1L, result.serverSeq)
        assertEquals(create.eventId(server.crypto).v, result.id)
        val (again, duplicate) = api.createCircle(create)
        assertEquals(200, again.status)
        assertEquals(PushStatus.DUPLICATE, duplicate.status)
        assertEquals(1L, duplicate.serverSeq)
    }

    @Test
    fun aSecondFounderForTheSameCircleIs422() = apiTest {
        val circle = newCircleId(server.crypto)
        api.createCircle(Person(server.crypto).author.circleCreate(circle, TODAY))
        val (reply, result) = api.createCircle(Person(server.crypto).author.circleCreate(circle, TODAY))
        assertEquals(422, reply.status)
        assertEquals("circle_exists", result.reason)
    }

    @Test
    fun creationCanBeSwitchedOff() = apiTest(allowCreate = false) {
        val reply = api.raw("POST", circlesPath, """{"event":{}}""")
        assertEquals(403, reply.status)
        assertTrue(reply.contentType!!.startsWith("application/problem+json"))
    }

    @Test
    fun joiningThroughThePublicEndpointNeedsNoSession() = apiTest {
        val alice = Person(server.crypto)
        val circle = newCircleId(server.crypto)
        api.createCircle(alice.author.circleCreate(circle, TODAY))
        val key = newInviteKey(server.crypto)
        val invite = alice.author.invite(circle, key, TODAY + 7, 1, TODAY)
        val token = api.login(alice.identity)
        assertEquals(PushStatus.ACCEPTED, api.push(token, circle, listOf(invite)).second.single().status)

        val bea = Person(server.crypto)
        val (reply, result) = api.join(circle, bea.author.join(circle, invite.eventId(server.crypto), key, TODAY))
        assertEquals(201, reply.status)
        assertEquals(PushStatus.ACCEPTED, result.status)
        assertNotNull(api.login(bea.identity))
    }

    @Test
    fun aBadJoinIs422WithAReasonAndAnUnknownCirclePathIs404() = apiTest {
        val alice = Person(server.crypto)
        val circle = newCircleId(server.crypto)
        api.createCircle(alice.author.circleCreate(circle, TODAY))
        val bea = Person(server.crypto)
        val (reply, result) = api.join(circle, bea.author.join(circle, circles.event.EventId("ab".repeat(32)), newInviteKey(server.crypto), TODAY))
        assertEquals(422, reply.status)
        assertEquals("unknown_invite", result.reason)
        assertEquals(404, api.raw("POST", "$circlesPath/not-a-circle/join", """{"event":{}}""").status)
    }

    // ---- authentication ----

    @Test
    fun pushAndPullNeedAValidBearerToken() = apiTest {
        val alice = Person(server.crypto)
        val circle = newCircleId(server.crypto)
        api.createCircle(alice.author.circleCreate(circle, TODAY))
        val path = "$circlesPath/${circle.v}/events"
        val none = api.raw("GET", path)
        assertEquals(401, none.status)
        assertTrue(none.contentType!!.startsWith("application/problem+json"))
        assertEquals(401, api.raw("GET", path, token = "nonsense").status)
        assertEquals(401, api.raw("POST", path, """{"events":[]}""").status)
        assertEquals(401, api.raw("POST", "$API_PREFIX/auth/session", """{"deviceId":"x","nonce":"y","sig":"z"}""").status)
    }

    @Test
    fun aTokenOnlyOpensItsOwnCircle() = apiTest {
        val alice = Person(server.crypto)
        val circle = newCircleId(server.crypto)
        api.createCircle(alice.author.circleCreate(circle, TODAY))
        val other = Person(server.crypto)
        val otherCircle = newCircleId(server.crypto)
        api.createCircle(other.author.circleCreate(otherCircle, TODAY))
        val token = api.login(alice.identity)
        assertEquals(403, api.pull(token, otherCircle).first.status)
        assertEquals(403, api.push(token, otherCircle, emptyList()).first.status)
        assertEquals(200, api.pull(token, circle).first.status)
    }

    @Test
    fun aMalformedCircleIdInThePathIs404() = apiTest {
        assertEquals(404, api.raw("GET", "$circlesPath/short/events", token = "x").status)
    }

    // ---- push and pull ----

    @Test
    fun pushedEventsComeBackOnPullInOrderAndCanBeVerifiedByTheReader() = apiTest {
        val alice = Person(server.crypto)
        val circle = newCircleId(server.crypto)
        api.createCircle(alice.author.circleCreate(circle, TODAY))
        val token = api.login(alice.identity)
        val invites = List(3) { alice.author.invite(circle, newInviteKey(server.crypto), TODAY + 5, 1, TODAY) }
        val (reply, results) = api.push(token, circle, invites)
        assertEquals(200, reply.status)
        assertEquals(listOf(2L, 3L, 4L), results.map { it.serverSeq })
        val (_, page) = api.pull(token, circle)
        assertEquals(listOf(1L, 2L, 3L, 4L), page!!.events.map { it.serverSeq })
        assertEquals(4L, page.next)
        assertFalse(page.hasMore)
        page.events.drop(1).zip(invites).forEach { (stored, sent) ->
            val back = (stored.envelope.toEnvelope() as circles.encoding.Parsed.Ok).value
            assertEquals(sent.eventId(server.crypto), back.eventId(server.crypto))
            assertTrue(back.hasValidSignature(server.crypto, alice.identity.signing.publicKey))
        }
    }

    @Test
    fun pushAnswersEachEventAndAnyMemberDeviceMayRelayAnotherDevicesEvents() = apiTest {
        val alice = Person(server.crypto)
        val circle = newCircleId(server.crypto)
        api.createCircle(alice.author.circleCreate(circle, TODAY))
        val phone = DeviceIdentity.generate(server.crypto, alice.identity.member)
        val token = api.login(alice.identity)
        api.push(token, circle, listOf(alice.author.deviceAdd(circle, phone, TODAY)))
        val phoneToken = api.login(phone)
        val good = alice.author.invite(circle, newInviteKey(server.crypto), TODAY + 5, 1, TODAY)
        val stranger = Person(server.crypto).author.invite(circle, newInviteKey(server.crypto), TODAY + 5, 1, TODAY)
        val (_, results) = api.push(phoneToken, circle, listOf(good, stranger, good))
        assertEquals(listOf(PushStatus.ACCEPTED, PushStatus.REJECTED, PushStatus.DUPLICATE), results.map { it.status })
        assertEquals("unknown_device", results[1].reason)
        assertEquals(results[0].serverSeq, results[2].serverSeq)
    }

    @Test
    fun aPushBatchIsLimitedTo200Events() = apiTest {
        val alice = Person(server.crypto)
        val circle = newCircleId(server.crypto)
        api.createCircle(alice.author.circleCreate(circle, TODAY))
        val token = api.login(alice.identity)
        val event = alice.author.invite(circle, newInviteKey(server.crypto), TODAY + 5, 1, TODAY)
        assertEquals(400, api.push(token, circle, List(MAX_PUSH_EVENTS + 1) { event }).first.status)
        assertEquals(emptyList(), api.push(token, circle, emptyList()).second)
    }

    @Test
    fun pullParametersAreValidated() = apiTest {
        val alice = Person(server.crypto)
        val circle = newCircleId(server.crypto)
        api.createCircle(alice.author.circleCreate(circle, TODAY))
        val token = api.login(alice.identity)
        val base = "$circlesPath/${circle.v}/events"
        listOf("after=-1", "after=abc", "limit=0", "limit=abc", "limit=-5").forEach {
            assertEquals(400, api.raw("GET", "$base?$it", token = token).status, it)
        }
        assertEquals(200, api.raw("GET", "$base?limit=100000", token = token).status)
        assertEquals(200, api.raw("GET", base, token = token).status)
        assertEquals(0, api.pull(token, circle, after = 99).second!!.events.size)
    }

    @Test
    fun pullPagesWithLimit() = apiTest {
        val alice = Person(server.crypto)
        val circle = newCircleId(server.crypto)
        api.createCircle(alice.author.circleCreate(circle, TODAY))
        val token = api.login(alice.identity)
        api.push(token, circle, List(3) { alice.author.invite(circle, newInviteKey(server.crypto), TODAY + 5, 1, TODAY) })
        val first = api.pull(token, circle, after = 0, limit = 3).second!!
        assertEquals(listOf(1L, 2L, 3L), first.events.map { it.serverSeq })
        assertTrue(first.hasMore)
        assertEquals(listOf(4L), api.pull(token, circle, after = first.next, limit = 3).second!!.events.map { it.serverSeq })
    }

    // ---- bad bodies ----

    @Test
    fun badBodiesAre400ProblemJson() = apiTest {
        val alice = Person(server.crypto)
        val circle = newCircleId(server.crypto)
        api.createCircle(alice.author.circleCreate(circle, TODAY))
        val token = api.login(alice.identity)
        val push = "$circlesPath/${circle.v}/events"
        listOf("", "not json", "[]", """{"events":"x"}""", """{"events":[],"extra":1}""", """{"events":[{"v":0}]}""").forEach {
            val reply = api.raw("POST", push, it, token)
            assertEquals(400, reply.status, "body '$it'")
            assertTrue(reply.contentType!!.startsWith("application/problem+json"))
            assertEquals(400, problem(reply.body).status)
        }
        assertEquals(400, api.raw("POST", circlesPath, "{").status)
        assertEquals(400, api.raw("POST", "$API_PREFIX/auth/challenge", "{}").status)
    }

    @Test
    fun anOversizedBodyIs413() = apiTest {
        val big = "x".repeat((MAX_BODY_BYTES + 1).toInt())
        val reply = api.raw("POST", circlesPath, """{"event":"$big"}""")
        assertEquals(413, reply.status)
        assertNull(reply.body.takeIf { it.contains(big) })
    }

    @Test
    fun problemsNeverCarryStackTraces() = apiTest {
        val reply = api.raw("POST", circlesPath, "{")
        assertFalse(reply.body.contains("Exception") || reply.body.contains("\tat "))
    }
}
