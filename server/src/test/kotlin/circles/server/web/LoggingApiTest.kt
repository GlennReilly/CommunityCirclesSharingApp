package circles.server.web

import circles.api.API_PREFIX
import circles.event.newCircleId
import circles.server.domain.Store
import circles.server.support.CapturedLog
import circles.server.support.Person
import circles.server.support.TODAY
import circles.server.support.TestServer
import circles.server.support.apiTest
import circles.server.support.newInviteKey
import io.ktor.client.request.get
import io.ktor.server.testing.testApplication
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class LoggingApiTest {
    private fun kotlinx.serialization.json.JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content

    @Test
    fun everyRequestIsOneLineWithMethodPathStatusAndDuration() {
        val log = CapturedLog()
        apiTest(log = log) {
            val circle = newCircleId(server.crypto)
            val alice = Person(server.crypto)
            api.createCircle(alice.author.circleCreate(circle, TODAY))
            val token = api.login(alice.identity)
            api.pull(token, circle)
            api.raw("GET", "$API_PREFIX/circles/${circle.v}/events")
        }
        val requests = log.named("request")
        assertEquals(listOf("POST", "POST", "POST", "GET", "GET"), requests.map { it.text("method") })
        assertEquals(
            listOf("/api/v1/circles", "/api/v1/auth/challenge", "/api/v1/auth/session", "/api/v1/circles/{id}/events", "/api/v1/circles/{id}/events"),
            requests.map { it.text("path") },
        )
        assertEquals(listOf(201, 200, 200, 200, 401), requests.map { it.getValue("status").jsonPrimitive.int })
        assertEquals(listOf("info", "info", "info", "info", "warn"), requests.map { it.text("level") })
        requests.forEach { assertTrue(it.getValue("duration_ms").jsonPrimitive.int >= 0) }
    }

    @Test
    fun probesAreOnlyLoggedAtDebugLevel() {
        val log = CapturedLog()
        apiTest(log = log) {
            api.raw("GET", "/healthz")
            api.raw("GET", "/readyz")
        }
        assertEquals(listOf("debug", "debug"), log.named("request").map { it.text("level") })
    }

    @Test
    fun aPushIsSummarisedByCountsAndReasonsOnly() {
        val log = CapturedLog()
        apiTest(log = log) {
            val circle = newCircleId(server.crypto)
            val alice = Person(server.crypto)
            api.createCircle(alice.author.circleCreate(circle, TODAY))
            val token = api.login(alice.identity)
            val good = alice.author.invite(circle, newInviteKey(server.crypto), TODAY + 5, 1, TODAY)
            val stranger = Person(server.crypto).author.invite(circle, newInviteKey(server.crypto), TODAY + 5, 1, TODAY)
            api.push(token, circle, listOf(good, stranger, good))
        }
        val summary = log.named("events_submitted").last()
        assertEquals("push", summary.text("entrance"))
        assertEquals(1, summary.getValue("accepted").jsonPrimitive.int)
        assertEquals(1, summary.getValue("duplicate").jsonPrimitive.int)
        assertEquals(1, summary.getValue("rejected").jsonPrimitive.int)
        assertEquals(1, summary.getValue("reasons").jsonObject.getValue("unknown_device").jsonPrimitive.int)
    }

    @Test
    fun nothingSecretOrIdentifyingReachesTheLog() {
        val log = CapturedLog()
        val crypto = TestServer().use { it.crypto }
        val alice = Person(crypto)
        val bea = Person(crypto)
        val circle = newCircleId(crypto)
        val key = newInviteKey(crypto)
        val invite = alice.author.invite(circle, key, TODAY + 5, 1, TODAY, note = "private note for Bea")
        var token = ""
        var nonceSeen = ""
        apiTest(rateLimit = true, trustProxy = true, log = log) {
            api.createCircle(alice.author.circleCreate(circle, TODAY))
            token = api.login(alice.identity)
            api.push(token, circle, listOf(invite))
            api.join(circle, bea.author.join(circle, invite.eventId(server.crypto), key, TODAY))
            api.pull(token, circle)
            api.raw("GET", "$API_PREFIX/circles/${circle.v}/events?after=0", token = token, headers = mapOf("X-Forwarded-For" to "198.51.100.77"))
            repeat(12) { api.raw("POST", "$API_PREFIX/auth/challenge", """{"deviceId":"${alice.identity.device.v}"}""") }
            nonceSeen = server.rows("SELECT nonce FROM challenges").firstOrNull()?.firstOrNull()?.let { (it as ByteArray).let { b -> circles.encoding.ByteString.of(b).toBase64Url() } }.orEmpty()
        }
        val text = log.text()
        assertTrue(log.named("rate_limited").isNotEmpty(), "the scenario should have hit a limit so that line is covered too")
        val secrets = listOf(
            token,
            "Bearer",
            nonceSeen,
            "198.51.100.77",
            "127.0.0.1",
            "private note for Bea",
            circle.v,
            alice.identity.device.v,
            alice.identity.member.v,
            bea.identity.device.v,
            alice.identity.signing.secretSeed.toHex(),
            alice.identity.signing.publicKey.toHex(),
            alice.identity.signing.publicKey.toBase64Url(),
            key.keys.secretSeed.toHex(),
            invite.sig.toBase64Url(),
            invite.eventId(crypto).v,
        ).filter { it.isNotEmpty() }
        secrets.forEach { assertFalse(text.contains(it), "the log must not contain '$it'") }
        assertTrue(text.lines().filter { it.isNotBlank() }.all { it.startsWith("{") && it.endsWith("}") })
    }

    @Test
    fun rateLimitedRequestsAreLoggedByBucketOnly() {
        val log = CapturedLog()
        apiTest(rateLimit = true, log = log) {
            repeat(11) { api.raw("POST", "$API_PREFIX/auth/challenge", """{"deviceId":"x"}""") }
        }
        val line = log.named("rate_limited").single()
        assertEquals("auth", line.text("bucket"))
        assertEquals("warn", line.text("level"))
        assertEquals(setOf("time", "level", "event", "bucket", "retry_after_seconds"), line.keys)
    }

    @Test
    fun anUnhandledErrorLogsTheClassAndFramesButNotTheMessage() = testApplication {
        val log = CapturedLog()
        TestServer(log = log).use { server ->
            val failing = Services(
                server.services.sync,
                server.services.auth,
                mockk<Store> { every { isReady() } throws IllegalStateException("secret internals") },
                log,
            )
            application { module(failing) }
            assertEquals(500, client.get("/readyz").status.value)
            val line = log.named("unhandled_error").single()
            assertEquals("java.lang.IllegalStateException", line.text("exception"))
            assertEquals("error", line.text("level"))
            assertFalse(log.text().contains("secret internals"))
            assertEquals("error", log.named("request").single().text("level"))
        }
    }
}
