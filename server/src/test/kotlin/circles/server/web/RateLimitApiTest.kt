package circles.server.web

import circles.api.API_PREFIX
import circles.event.newCircleId
import circles.server.support.Person
import circles.server.support.TODAY
import circles.server.support.apiTest
import circles.server.support.newInviteKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RateLimitApiTest {
    private val challenge = "$API_PREFIX/auth/challenge"
    private val circlesPath = "$API_PREFIX/circles"
    private val someDevice = """{"deviceId":"${"ab".repeat(16)}"}"""

    @Test
    fun theEleventhAuthRequestInAMinuteIs429WithRetryAfter() = apiTest(rateLimit = true) {
        repeat(10) { assertEquals(200, api.raw("POST", challenge, someDevice).status) }
        val refused = api.raw("POST", challenge, someDevice)
        assertEquals(429, refused.status)
        assertEquals("6", refused.retryAfter)
        assertTrue(refused.contentType!!.startsWith("application/problem+json"))
        // Challenge and session requests share the one auth bucket.
        assertEquals(429, api.raw("POST", "$API_PREFIX/auth/session", """{"deviceId":"x","nonce":"y","sig":"z"}""").status)
    }

    @Test
    fun allowanceReturnsAsTimePasses() = apiTest(rateLimit = true) {
        repeat(10) { api.raw("POST", challenge, someDevice) }
        assertEquals(429, api.raw("POST", challenge, someDevice).status)
        server.clock.advanceSeconds(6)
        assertEquals(200, api.raw("POST", challenge, someDevice).status)
        assertEquals(429, api.raw("POST", challenge, someDevice).status)
    }

    @Test
    fun createAndJoinAreLimitedToFiveAMinuteEach() = apiTest(rateLimit = true) {
        val founders = List(6) { Person(server.crypto) }
        val statuses = founders.map { api.createCircle(it.author.circleCreate(newCircleId(server.crypto), TODAY)).first.status }
        assertEquals(listOf(201, 201, 201, 201, 201, 429), statuses)

        val circle = newCircleId(server.crypto)
        val alice = Person(server.crypto)
        api.raw("POST", "$circlesPath/${circle.v}/join", """{"event":{}}""")
        val joins = List(6) { api.raw("POST", "$circlesPath/${circle.v}/join", """{"event":{}}""").status }
        assertEquals(listOf(400, 400, 400, 400, 429, 429), joins)
        assertTrue(alice.identity.member.v.isNotEmpty())
    }

    @Test
    fun closedCreationDoesNotUseUpTheAllowance() = apiTest(allowCreate = false, rateLimit = true) {
        repeat(10) { assertEquals(403, api.raw("POST", circlesPath, """{"event":{}}""").status) }
    }

    @Test
    fun pushIsLimitedPerDeviceAndOtherDevicesAreNotAffected() = apiTest(rateLimit = true) {
        val circle = newCircleId(server.crypto)
        val alice = Person(server.crypto)
        api.createCircle(alice.author.circleCreate(circle, TODAY))
        val key = newInviteKey(server.crypto)
        val invite = alice.author.invite(circle, key, TODAY + 5, 2, TODAY)
        val aliceToken = api.login(alice.identity)
        api.push(aliceToken, circle, listOf(invite))
        val bea = Person(server.crypto)
        api.join(circle, bea.author.join(circle, invite.eventId(server.crypto), key, TODAY))
        server.clock.advanceSeconds(60) // the logins above used the auth allowance
        val beaToken = api.login(bea.identity)

        val aliceStatuses = List(61) { api.push(aliceToken, circle, emptyList()).first.status }
        assertEquals(60, aliceStatuses.count { it == 200 })
        assertEquals(429, aliceStatuses.last())
        assertEquals(200, api.push(beaToken, circle, emptyList()).first.status)
    }

    @Test
    fun pullAndProbesAreNotLimited() = apiTest(rateLimit = true) {
        val circle = newCircleId(server.crypto)
        val alice = Person(server.crypto)
        api.createCircle(alice.author.circleCreate(circle, TODAY))
        val token = api.login(alice.identity)
        repeat(150) { assertEquals(200, api.pull(token, circle).first.status) }
        repeat(150) { assertEquals(200, api.raw("GET", "/healthz").status) }
    }

    @Test
    fun forwardedForIsIgnoredUnlessTheProxyIsTrusted() = apiTest(rateLimit = true) {
        val statuses = List(6) { n ->
            api.raw("POST", circlesPath, """{"event":{}}""", headers = mapOf("X-Forwarded-For" to "10.0.0.$n")).status
        }
        assertEquals(listOf(400, 400, 400, 400, 400, 429), statuses)
    }

    @Test
    fun withATrustedProxyEachClientAddressHasItsOwnAllowance() = apiTest(rateLimit = true, trustProxy = true) {
        val separate = List(8) { n -> api.raw("POST", circlesPath, """{"event":{}}""", headers = mapOf("X-Forwarded-For" to "10.0.0.$n")).status }
        assertEquals(List(8) { 400 }, separate)
    }

    @Test
    fun withATrustedProxyOnlyTheLastEntryCountSoAClientCannotForgeItsWayOut() = apiTest(rateLimit = true, trustProxy = true) {
        val forged = List(6) { n -> api.raw("POST", circlesPath, """{"event":{}}""", headers = mapOf("X-Forwarded-For" to "6.6.6.$n, 203.0.113.9")).status }
        assertEquals(listOf(400, 400, 400, 400, 400, 429), forged)
    }

    @Test
    fun limitingCanBeSwitchedOff() = apiTest(rateLimit = false) {
        repeat(30) { assertEquals(400, api.raw("POST", circlesPath, """{"event":{}}""").status) }
    }
}
