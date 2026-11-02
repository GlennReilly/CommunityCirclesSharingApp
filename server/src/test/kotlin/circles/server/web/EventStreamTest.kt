package circles.server.web

import circles.api.PushStatus
import circles.event.newCircleId
import circles.policy.CircleId
import circles.server.support.LiveServer
import circles.server.support.Person
import circles.server.support.TODAY
import circles.server.support.TestServer
import circles.server.support.newInviteKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** `GET /circles/{id}/events/stream`, the hint-only SSE of ADR 0026, against a real server. */
class EventStreamTest {
    private val keepAliveMillis = 150L

    private class Founder(val person: Person, val circle: CircleId, val token: String)

    private fun LiveServer.found(): Founder {
        val person = Person(server.crypto)
        val circle = newCircleId(server.crypto)
        return runBlocking {
            assertEquals(PushStatus.ACCEPTED, api.createCircle(person.author.circleCreate(circle, TODAY)).second.status)
            Founder(person, circle, api.login(person.identity))
        }
    }

    private fun LiveServer.pushInvite(f: Founder) = runBlocking {
        val result = api.push(f.token, f.circle, listOf(f.person.author.invite(f.circle, newInviteKey(server.crypto), TODAY + 5, 1, TODAY))).second.single()
        assertEquals(PushStatus.ACCEPTED, result.status)
        result.serverSeq!!
    }

    private fun live() = LiveServer(TestServer(streamKeepAliveMillis = keepAliveMillis))

    @Test
    fun aNewStreamIsGreetedWithTheCurrentNumberThenGetsEveryNewOne() {
        live().use { live ->
            val f = live.found()
            live.openStream(f.circle, f.token).use { stream ->
                assertEquals(200, stream.awaitStatus())
                assertEquals("retry: 5000", stream.nextLine())
                assertEquals(1L, stream.nextHint(), "greeted with the circle's newest sequence number")
                assertEquals(2L, live.pushInvite(f))
                assertEquals(2L, stream.nextHint())
                assertEquals(3L, live.pushInvite(f))
                assertEquals(3L, stream.nextHint())
            }
        }
    }

    @Test
    fun aStreamOpenedLateIsToldWhereTheCircleIsAlready() {
        live().use { live ->
            val f = live.found()
            repeat(3) { live.pushInvite(f) }
            live.openStream(f.circle, f.token).use { stream ->
                assertEquals(4L, stream.nextHint())
            }
        }
    }

    @Test
    fun hintsOfOtherCirclesDoNotArrive() {
        live().use { live ->
            val mine = live.found()
            val other = live.found()
            live.openStream(mine.circle, mine.token).use { stream ->
                assertEquals(1L, stream.nextHint())
                live.pushInvite(other)
                assertNull(stream.nextHint(600), "nothing from another circle")
                live.pushInvite(mine)
                assertEquals(2L, stream.nextHint())
            }
        }
    }

    @Test
    fun anIdleStreamSendsKeepAlives() {
        live().use { live ->
            val f = live.found()
            live.openStream(f.circle, f.token).use { stream ->
                stream.nextHint()
                val lines = List(6) { stream.nextLine(1_000) }
                assertTrue(lines.any { it == ": keep-alive" }, lines.toString())
            }
        }
    }

    @Test
    fun theStreamNeedsAValidSessionForThatCircle() {
        live().use { live ->
            val a = live.found()
            val b = live.found()
            live.openStream(a.circle, null).use { assertEquals(401, it.awaitStatus()) }
            live.openStream(a.circle, "not-a-token").use { assertEquals(401, it.awaitStatus()) }
            live.openStream(a.circle, b.token).use { assertEquals(403, it.awaitStatus()) }
            assertEquals(404, live.request("GET", "/api/v1/circles/nope/events/stream", a.token).status)
        }
    }

    @Test
    fun aDeviceMayHoldThreeStreamsAndTheFourthIsRefusedUntilOneCloses() {
        live().use { live ->
            val f = live.found()
            val open = List(3) { live.openStream(f.circle, f.token).also { s -> assertEquals(200, s.awaitStatus()) } }
            live.openStream(f.circle, f.token).use { assertEquals(429, it.awaitStatus()) }
            assertEquals(3, live.server.services.hub.openStreams)
            open.first().close()
            val deadline = System.currentTimeMillis() + 3_000
            while (live.server.services.hub.openStreams > 2 && System.currentTimeMillis() < deadline) Thread.sleep(50)
            live.openStream(f.circle, f.token).use { assertEquals(200, it.awaitStatus()) }
            open.forEach { it.close() }
        }
    }

    @Test
    fun theHubForgetsAStreamWhenItsClientGoesAway() {
        live().use { live ->
            val f = live.found()
            val stream = live.openStream(f.circle, f.token)
            assertEquals(200, stream.awaitStatus())
            stream.nextHint()
            assertEquals(1, live.server.services.hub.openStreams)
            stream.close()
            val deadline = System.currentTimeMillis() + 5_000
            while (live.server.services.hub.openStreams > 0 && System.currentTimeMillis() < deadline) {
                live.pushInvite(f) // a write to the dead connection is what lets the server notice
                Thread.sleep(100)
            }
            assertEquals(0, live.server.services.hub.openStreams)
        }
    }

    @Test
    fun aRevokedDevicesStreamEndsAtTheNextKeepAlive() {
        live().use { live ->
            val owner = live.found()
            val bea = Person(live.server.crypto)
            val inviteKey = newInviteKey(live.server.crypto)
            val invite = owner.person.author.invite(owner.circle, inviteKey, TODAY + 5, 1, TODAY)
            runBlocking {
                live.api.push(owner.token, owner.circle, listOf(invite))
                live.api.join(owner.circle, bea.author.join(owner.circle, invite.eventId(live.server.crypto), inviteKey, TODAY))
            }
            val beaToken = runBlocking { live.api.login(bea.identity) }
            live.openStream(owner.circle, beaToken).use { stream ->
                assertEquals(200, stream.awaitStatus())
                stream.nextHint()
                runBlocking { live.api.push(owner.token, owner.circle, listOf(owner.person.author.deviceRevoke(owner.circle, bea.identity.device, TODAY))) }
                assertTrue(stream.awaitEnd(3_000), "the stream should end once the device is revoked")
            }
        }
    }
}
