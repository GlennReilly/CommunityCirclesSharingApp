package circles.server

import circles.api.PushStatus
import circles.encoding.ByteString
import circles.encoding.Parsed
import circles.event.DeviceIdentity
import circles.event.DeviceRegistration
import circles.event.Envelope
import circles.event.EventType
import circles.event.JoinTerms
import circles.event.PublicParts
import circles.event.newCircleId
import circles.server.support.ApiClient
import circles.server.support.Person
import circles.server.support.SocketTransport
import circles.server.support.TODAY
import circles.server.support.TestServer
import circles.server.support.apiTest
import circles.server.support.newInviteKey
import circles.server.web.module
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M1 acceptance: two JVM test clients sync a circle (spec 9, M1). Alice founds it and invites Bea; Bea joins,
 * adds a second device; both pull the whole log and check it independently with nothing but the events.
 */
class TwoClientsSyncTest {
    /** What a client can work out from the log alone: which device speaks for which member, and its signing key. */
    private fun registrations(events: List<Envelope>): Map<String, DeviceRegistration> =
        events.mapNotNull {
            when (it.type) {
                EventType.CIRCLE_CREATE.wire, EventType.DEVICE_ADD.wire -> (PublicParts.registration(it.public) as Parsed.Ok).value
                EventType.MEMBER_JOIN.wire -> (PublicParts.join(it.public) as Parsed.Ok<JoinTerms>).value.registration
                else -> null
            }
        }.associateBy { it.device.v }

    private suspend fun scenario(server: TestServer, aliceApi: ApiClient, beaApi: ApiClient) {
        val crypto = server.crypto
        val alice = Person(crypto)
        val bea = Person(crypto)
        val circle = newCircleId(crypto)

        // Alice founds the circle and invites Bea.
        val (_, created) = aliceApi.createCircle(alice.author.circleCreate(circle, TODAY))
        assertEquals(PushStatus.ACCEPTED, created.status)
        val aliceToken = aliceApi.login(alice.identity)
        val inviteKey = newInviteKey(crypto)
        val invite = alice.author.invite(circle, inviteKey, TODAY + 14, 1, TODAY, note = "for Bea")
        assertEquals(PushStatus.ACCEPTED, aliceApi.push(aliceToken, circle, listOf(invite)).second.single().status)

        // Bea redeems the invite, logs in with the device she just registered and adds a phone.
        val (_, joined) = beaApi.join(circle, bea.author.join(circle, invite.eventId(crypto), inviteKey, TODAY))
        assertEquals(PushStatus.ACCEPTED, joined.status)
        val beaToken = beaApi.login(bea.identity)
        val phone = DeviceIdentity.generate(crypto, bea.identity.member)
        assertEquals(PushStatus.ACCEPTED, beaApi.push(beaToken, circle, listOf(bea.author.deviceAdd(circle, phone, TODAY, label = "phone"))).second.single().status)
        val phoneToken = beaApi.login(phone)

        // Both push more at the same time, from three devices.
        val phoneAuthor = circles.event.EventAuthor(crypto, phone)
        runBlocking {
            listOf(
                async { aliceApi.push(aliceToken, circle, List(5) { alice.author.invite(circle, newInviteKey(crypto), TODAY + 3, 1, TODAY) }).second },
                async { beaApi.push(beaToken, circle, List(5) { bea.author.invite(circle, newInviteKey(crypto), TODAY + 3, 1, TODAY) }).second },
                async { beaApi.push(phoneToken, circle, List(5) { phoneAuthor.invite(circle, newInviteKey(crypto), TODAY + 3, 1, TODAY) }).second },
            ).awaitAll().flatten().forEach { assertEquals(PushStatus.ACCEPTED, it.status, "${it.reason}: ${it.detail}") }
        }

        // Everyone sees the same log: the same events in the same order.
        val aliceView = aliceApi.pull(aliceToken, circle).second!!
        val beaView = beaApi.pull(beaToken, circle).second!!
        val phoneView = beaApi.pull(phoneToken, circle).second!!
        assertEquals(4 + 15, aliceView.events.size)
        assertEquals(aliceView, beaView)
        assertEquals(aliceView, phoneView)
        assertEquals((1L..19L).toList(), aliceView.events.map { it.serverSeq })

        // Each client can verify the log on its own: ids recompute, and each signature matches a key the log registered.
        val events = aliceView.events.map { (it.envelope.toEnvelope() as Parsed.Ok).value }
        val keys = registrations(events)
        assertEquals(setOf(alice.identity.device.v, bea.identity.device.v, phone.device.v), keys.keys)
        events.forEach { event ->
            val signer = keys.getValue(event.device.v)
            assertEquals(event.author, signer.member, "the device speaks for its author")
            assertTrue(event.hasValidSignature(crypto, signer.signPub), "signature of ${event.type} #${event.ctr}")
        }
        assertEquals(
            listOf("circle.create", "member.invite", "member.join", "device.add"),
            events.take(4).map { it.type },
        )

        // A client that was offline catches up from where it stopped.
        val resumed = beaApi.pull(beaToken, circle, after = 10).second!!
        assertEquals((11L..19L).toList(), resumed.events.map { it.serverSeq })

        // The note and label travelled as payload, readable in this development format.
        val inviteBack = events[1]
        assertEquals(ByteString.of(invite.encrypted.toByteArray()), inviteBack.encrypted)
    }

    @Test
    fun twoClientsSyncACircleInProcess() = apiTest {
        scenario(server, api, api)
    }

    @Test
    fun twoClientsSyncACircleOverARealSocket() {
        TestServer().use { server ->
            val engine = embeddedServer(Netty, port = 0, host = "127.0.0.1") { module(server.services) }.start(wait = false)
            try {
                val port = runBlocking { engine.engine.resolvedConnectors().first().port }
                val transport = SocketTransport("http://127.0.0.1:$port")
                runBlocking { scenario(server, ApiClient(transport, server.crypto), ApiClient(transport, server.crypto)) }
            } finally {
                engine.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
            }
        }
    }
}
