package circles.server

import circles.server.support.testCipher
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
import circles.encoding.CborText
import circles.event.CircleKeys
import circles.event.FIRST_EPOCH
import circles.event.KeyRing
import circles.event.PayloadSealing
import circles.policy.CircleId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M1 and M2 acceptance: two JVM test clients sync a circle (spec 9). Alice founds it, wraps the circle key to herself
 * and invites Bea with a sealed note; Bea joins, gets the key from Alice, adds a second device with a sealed label and
 * wraps the key to it; all three devices pull the whole log, check it independently and read the sealed parts.
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
        val circleKey = KeyRing.newCircleKey(crypto)
        alice.author.keyRing = KeyRing.EMPTY.with(FIRST_EPOCH, circleKey)

        // Alice founds the circle, wraps the circle key to her own device and invites Bea.
        val (_, created) = aliceApi.createCircle(alice.author.circleCreate(circle, TODAY))
        assertEquals(PushStatus.ACCEPTED, created.status)
        val aliceToken = aliceApi.login(alice.identity)
        val selfWrap = alice.author.keyWrap(circle, alice.identity.registration, FIRST_EPOCH, circleKey, TODAY)
        assertEquals(PushStatus.ACCEPTED, aliceApi.push(aliceToken, circle, listOf(selfWrap)).second.single().status)
        val inviteKey = newInviteKey(crypto)
        val invite = alice.author.invite(circle, inviteKey, TODAY + 14, 1, TODAY, note = "for Bea")
        assertEquals(PushStatus.ACCEPTED, aliceApi.push(aliceToken, circle, listOf(invite)).second.single().status)

        // Bea redeems the invite, logs in with the device she just registered and adds a phone.
        val (_, joined) = beaApi.join(circle, bea.author.join(circle, invite.eventId(crypto), inviteKey, TODAY))
        assertEquals(PushStatus.ACCEPTED, joined.status)
        val beaToken = beaApi.login(bea.identity)

        // Alice wraps the key to Bea's device; Bea opens it, adds a phone with a sealed label and wraps the key to it.
        val beaWrap = alice.author.keyWrap(circle, bea.identity.registration, FIRST_EPOCH, circleKey, TODAY)
        assertEquals(PushStatus.ACCEPTED, aliceApi.push(aliceToken, circle, listOf(beaWrap)).second.single().status)
        bea.author.keyRing = openWrap(beaApi, beaToken, circle, bea.identity)
        val phone = DeviceIdentity.generate(crypto, testCipher, bea.identity.member)
        assertEquals(PushStatus.ACCEPTED, beaApi.push(beaToken, circle, listOf(bea.author.deviceAdd(circle, phone, TODAY, label = "phone"))).second.single().status)
        val phoneWrap = bea.author.keyWrap(circle, phone.registration, FIRST_EPOCH, bea.author.keyRing[FIRST_EPOCH]!!, TODAY)
        assertEquals(PushStatus.ACCEPTED, beaApi.push(beaToken, circle, listOf(phoneWrap)).second.single().status)
        val phoneToken = beaApi.login(phone)
        val phoneAuthor = circles.event.EventAuthor(crypto, phone, testCipher, keyRing = openWrap(beaApi, phoneToken, circle, phone))

        // Both push more at the same time, from three devices.
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
        assertEquals(7 + 15, aliceView.events.size)
        assertEquals(aliceView, beaView)
        assertEquals(aliceView, phoneView)
        assertEquals((1L..22L).toList(), aliceView.events.map { it.serverSeq })

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
            listOf("circle.create", "key.wrap", "member.invite", "member.join", "key.wrap", "device.add", "key.wrap"),
            events.take(7).map { it.type },
        )

        // A client that was offline catches up from where it stopped.
        val resumed = beaApi.pull(beaToken, circle, after = 10).second!!
        assertEquals((11L..22L).toList(), resumed.events.map { it.serverSeq })

        // The note and label are sealed: the server holds ciphertext, and each device reads them with the key it was wrapped.
        val inviteBack = events[2]
        val deviceAddBack = events[5]
        assertEquals(ByteString.of(invite.encrypted.toByteArray()), inviteBack.encrypted)
        assertTrue(!inviteBack.encrypted.toHex().contains(ByteString.of("for Bea".encodeToByteArray()).toHex()))
        listOf(alice.author.keyRing, bea.author.keyRing, phoneAuthor.keyRing).forEach { ring ->
            val note = (PayloadSealing.open(testCipher, ring, inviteBack) as Parsed.Ok).value["note"] as CborText
            val label = (PayloadSealing.open(testCipher, ring, deviceAddBack) as Parsed.Ok).value["label"] as CborText
            assertEquals("for Bea", note.value)
            assertEquals("phone", label.value)
        }
    }

    /** Pulls the log as [identity] and opens the circle key that was wrapped to that device. */
    private suspend fun openWrap(api: ApiClient, token: String, circle: CircleId, identity: DeviceIdentity): KeyRing {
        val events = api.pull(token, circle).second!!.events.map { (it.envelope.toEnvelope() as Parsed.Ok).value }
        val wrap = events.first { it.type == EventType.KEY_WRAP.wire && (CircleKeys.keyWrap(it.public) as Parsed.Ok).value.device == identity.device }
        val terms = (CircleKeys.keyWrap(wrap.public) as Parsed.Ok).value
        val key = (CircleKeys.unwrap(testCipher, identity.boxKeys, circle, wrap.epoch, terms.wrapped) as Parsed.Ok).value
        return KeyRing.EMPTY.with(wrap.epoch, key)
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
