package circles.server

import circles.crypto.JvmCrypto
import circles.event.CircleFold
import circles.event.DeviceIdentity
import circles.event.EventAuthor
import circles.event.FIRST_EPOCH
import circles.event.InviteKey
import circles.event.KeyRing
import circles.event.newCircleId
import circles.server.support.TODAY
import circles.server.support.apiTest
import circles.server.support.testCipher
import circles.sync.HttpReply
import circles.sync.HttpSyncApi
import circles.sync.HttpTransport
import circles.sync.InMemoryReplica
import circles.sync.SyncEngine
import circles.sync.SyncState
import circles.sync.fold
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The browser's sync stack on the JVM: `SyncEngine` over `HttpSyncApi` over the real server, with real rules. A founder
 * works offline, a member joins through the public endpoint, a refused event is kept as refused, a revoked device is
 * cut off, and a second device folds the log without repeating a signature check (ADR 0036).
 */
class SyncEngineEndToEndTest {
    private class Counting(val inner: JvmCrypto = JvmCrypto()) : circles.crypto.Crypto by inner {
        var verifies = 0
        override fun verify(publicKey: circles.encoding.ByteString, message: ByteArray, signature: circles.encoding.ByteString): Boolean {
            verifies++
            return inner.verify(publicKey, message, signature)
        }
    }

    @Test
    fun aCircleGrowsThroughTheEngineAndTheRealServer() = apiTest {
        val crypto = Counting()
        var offline = false
        val transport = HttpTransport { method, path, body, token ->
            if (offline) null else api.raw(method, path, body, token).let { HttpReply(it.status, it.body, it.retryAfter) }
        }
        val day = TODAY
        val circle = newCircleId(crypto)
        val key1 = KeyRing.newCircleKey(crypto)

        val founder = EventAuthor(crypto, DeviceIdentity.generate(crypto, testCipher), testCipher, keyRing = KeyRing.EMPTY.with(FIRST_EPOCH, key1))
        val founderReplica = InMemoryReplica { it.eventId(crypto) }
        val founderEngine = SyncEngine(crypto, HttpSyncApi(transport, crypto, founder.identity), founderReplica)

        // 1. The founder works offline; nothing is lost, and the status says how much is waiting.
        val inviteKey = InviteKey(crypto.generateSigningKeyPair())
        val invite = founder.invite(circle, inviteKey, day + 30, 1, day)
        offline = true
        founderEngine.enqueue(circle, founder.circleCreate(circle, day))
        founderEngine.enqueue(circle, founder.keyWrap(circle, founder.identity.registration, FIRST_EPOCH, key1, day))
        founderEngine.enqueue(circle, invite)
        assertEquals(SyncState.Offline(3), founderEngine.sync(circle).state)
        assertEquals(3, founderReplica.outbox(circle).size)

        // 2. Back online: create goes to the public endpoint, the rest with a login; everything returns as the log.
        offline = false
        val first = founderEngine.sync(circle)
        assertEquals(SyncState.UpToDate, first.state)
        assertEquals(3, first.sent)
        assertEquals(3, first.pulled)
        assertEquals(0, first.unchecked)
        assertEquals(emptyList(), founderReplica.outbox(circle))

        // 3. Bea joins through the public join endpoint, then logs in and pulls the log.
        val bea = EventAuthor(crypto, DeviceIdentity.generate(crypto, testCipher), testCipher)
        val beaReplica = InMemoryReplica { it.eventId(crypto) }
        val beaEngine = SyncEngine(crypto, HttpSyncApi(transport, crypto, bea.identity), beaReplica)
        beaEngine.enqueue(circle, bea.join(circle, invite.eventId(crypto), inviteKey, day))
        val beaFirst = beaEngine.sync(circle)
        assertEquals(SyncState.UpToDate, beaFirst.state)
        assertEquals(1, beaFirst.sent)
        assertEquals(4, beaFirst.pulled)

        // 4. The founder wraps the key to Bea; Bea pulls it and folds without a single repeated event signature.
        founderEngine.enqueue(circle, founder.keyWrap(circle, bea.identity.registration, FIRST_EPOCH, key1, day))
        founderEngine.sync(circle)
        beaEngine.sync(circle)
        val atIngest = crypto.verifies
        val view = beaReplica.fold(circle, crypto, testCipher, bea.identity)
        assertEquals(atIngest + 1, crypto.verifies, "only the invite proof of the join is checked again")
        assertEquals(emptyList(), view.rejected)
        assertNotNull(view.keys[FIRST_EPOCH])
        val plain = CircleFold(crypto, testCipher, bea.identity).fold(circle, beaReplica.entries(circle).map { it.envelope })
        assertEquals(plain.events.map { it.id }, view.events.map { it.id })
        assertEquals(5, beaReplica.entries(circle).size)
        assertEquals(5, server.rows("SELECT COUNT(*) FROM events").single().single().toString().toInt())

        // 5. An event the server's rules refuse (a second circle.create) is kept as refused, not retried forever.
        founderEngine.enqueue(circle, founder.circleCreate(circle, day))
        val refusedRun = founderEngine.sync(circle)
        assertEquals(1, refusedRun.refused)
        assertEquals(SyncState.UpToDate, refusedRun.state)
        assertEquals(1, founderReplica.refused(circle).size)
        assertEquals(emptyList(), founderReplica.outbox(circle))

        // 6. The founder revokes Bea's device; her next sync is cut off and says so, and keeps her queued change.
        founderEngine.enqueue(circle, founder.deviceRevoke(circle, bea.identity.device, day))
        founderEngine.sync(circle)
        beaEngine.enqueue(circle, bea.invite(circle, InviteKey(crypto.generateSigningKeyPair()), day + 1, 1, day))
        val cutOff = beaEngine.sync(circle)
        assertTrue(cutOff.state is SyncState.SignedOut || cutOff.state is SyncState.Problem, "state was ${cutOff.state}")
        assertEquals(1, beaReplica.outbox(circle).size)
    }
}
