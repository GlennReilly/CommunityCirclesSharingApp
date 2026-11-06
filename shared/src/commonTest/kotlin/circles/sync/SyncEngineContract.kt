package circles.sync

import circles.api.PullResponse
import circles.api.PushResult
import circles.api.PushStatus
import circles.api.StoredEvent
import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.encoding.ByteString
import circles.event.CircleFold
import circles.event.DeviceIdentity
import circles.event.Envelope
import circles.event.EventAuthor
import circles.event.EventId
import circles.event.FIRST_EPOCH
import circles.event.InviteKey
import circles.event.KeyRing
import circles.event.newCircleId
import circles.event.toDto
import circles.policy.CircleId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val DAY = 20_000
private const val SMALL_PAGE = 2

/** A crypto that counts signature checks, to show the fold does not repeat them (ADR 0036). */
private class CountingCrypto(private val inner: Crypto) : Crypto by inner {
    var verifies = 0
    override fun verify(publicKey: ByteString, message: ByteArray, signature: ByteString): Boolean {
        verifies++
        return inner.verify(publicKey, message, signature)
    }
}

/** A stand-in server: keeps events in order, no rules of its own, with switches to simulate trouble. */
private class FakeServer(private val crypto: Crypto) : SyncApi {
    val log = mutableListOf<StoredEvent>()
    var down = false
    var unauthorized = false
    var busyRetryAfter: Long? = null
    var busy = false
    var refuse = mutableMapOf<EventId, String>()
    var pageSize = 500
    var scrambleOrder = false
    var pushCalls = 0

    override suspend fun push(circle: CircleId, events: List<Envelope>): ApiOutcome<List<PushResult>> {
        pushCalls++
        failure<List<PushResult>>()?.let { return it }
        return ApiOutcome.Ok(
            events.map { e ->
                val id = e.eventId(crypto)
                val reason = refuse[id]
                val existing = log.firstOrNull { it.envelope == e.toDto() }
                when {
                    reason != null -> PushResult(id.v, PushStatus.REJECTED, reason = reason, detail = "no")
                    existing != null -> PushResult(id.v, PushStatus.DUPLICATE, existing.serverSeq)
                    else -> {
                        val seq = log.size + 1L
                        log += StoredEvent(seq, e.toDto())
                        PushResult(id.v, PushStatus.ACCEPTED, seq)
                    }
                }
            },
        )
    }

    /** Runs at the start of every pull, after the push: stands in for the app doing something while the pull is on the wire. */
    var beforePull: suspend () -> Unit = {}
    var pullDown = false

    override suspend fun pull(circle: CircleId, after: Long, limit: Int): ApiOutcome<PullResponse> {
        beforePull()
        if (pullDown) return ApiOutcome.Offline
        failure<PullResponse>()?.let { return it }
        var page = log.filter { it.serverSeq > after }.take(minOf(pageSize, limit))
        val more = log.any { it.serverSeq > (page.lastOrNull()?.serverSeq ?: after) }
        if (scrambleOrder) page = page.reversed()
        return ApiOutcome.Ok(PullResponse(page, page.maxOfOrNull { it.serverSeq } ?: after, more))
    }

    private fun <T> failure(): ApiOutcome<T>? = when {
        down -> ApiOutcome.Offline
        unauthorized -> ApiOutcome.Unauthorized
        busy -> ApiOutcome.Busy(busyRetryAfter)
        else -> null
    }
}

/** The sync engine against a fake server, on every Crypto/Cipher pair. */
abstract class SyncEngineContract {
    protected abstract suspend fun newCrypto(): Crypto
    protected abstract suspend fun newCipher(): Cipher

    private class World(val crypto: CountingCrypto, val cipher: Cipher) {
        val circle: CircleId = newCircleId(crypto)
        val server = FakeServer(crypto)
        val replica = InMemoryReplica { it.eventId(crypto) }
        val engine = SyncEngine(crypto, server, replica)
        val key1 = KeyRing.newCircleKey(crypto)
        val founder = person().also { it.keyRing = KeyRing.EMPTY.with(FIRST_EPOCH, key1) }
        val bea = person()

        fun person() = EventAuthor(crypto, DeviceIdentity.generate(crypto, cipher = cipher), cipher = cipher)

        /** create, key, invite, join, key for bea: five events from two devices. */
        fun buildCircle(): List<Envelope> {
            val inviteKey = InviteKey(crypto.generateSigningKeyPair())
            val invite = founder.invite(circle, inviteKey, DAY + 30, 1, DAY)
            return listOf(
                founder.circleCreate(circle, DAY),
                founder.keyWrap(circle, founder.identity.registration, FIRST_EPOCH, key1, DAY),
                invite,
                bea.join(circle, invite.eventId(crypto), inviteKey, DAY),
                founder.keyWrap(circle, bea.identity.registration, FIRST_EPOCH, key1, DAY),
            )
        }

        suspend fun enqueueAll(events: List<Envelope>) = events.forEach { engine.enqueue(circle, it) }
    }

    private suspend fun world() = World(CountingCrypto(newCrypto()), newCipher())

    @Test fun anOutboxIsSentAndComesBackAsTheLog() = runTest {
        val w = world()
        val events = w.buildCircle()
        w.enqueueAll(events)
        val report = w.engine.sync(w.circle)
        assertEquals(SyncState.UpToDate, report.state)
        assertEquals(5, report.sent)
        assertEquals(5, report.pulled)
        assertEquals(emptyList(), w.replica.outbox(w.circle))
        assertEquals(events, w.replica.entries(w.circle).map { it.envelope })
        assertEquals(5, w.replica.cursor(w.circle))
        assertEquals(SyncState.UpToDate, w.engine.state.value)
    }

    // Regression: the engine used to drop accepted events from the outbox on the push answer, before the pull had stored
    // them, so a fold during the pull saw a brand-new circle with no events at all (the founder's "Your name" was refused).
    @Test fun ownEventsStayVisibleToTheFoldWhileThePullIsOnTheWire() = runTest {
        val w = world()
        val events = w.buildCircle()
        w.enqueueAll(events)
        var seenDuringPull: Boolean? = null
        w.server.beforePull = {
            if (seenDuringPull == null) {
                val view = w.replica.fold(w.circle, w.crypto, w.cipher, w.founder.identity)
                seenDuringPull = view.currentEpoch == FIRST_EPOCH && view.keys[FIRST_EPOCH] != null
            }
        }
        assertEquals(SyncState.UpToDate, w.engine.sync(w.circle).state)
        assertEquals(true, seenDuringPull, "the founder's key must still be readable while the pull runs")
        assertEquals(emptyList(), w.replica.outbox(w.circle))
        assertEquals(5, w.replica.entries(w.circle).size)
    }

    @Test fun aFailedPullAfterAnAcceptedPushDoesNotSendTheEventsAgain() = runTest {
        val w = world()
        w.enqueueAll(w.buildCircle())
        w.server.pullDown = true
        val report = w.engine.sync(w.circle)
        assertEquals(SyncState.Offline(0), report.state)
        assertEquals(5, report.sent)
        assertEquals(emptyList(), w.replica.outbox(w.circle))
        w.server.pullDown = false
        val again = w.engine.sync(w.circle)
        assertEquals(0, again.sent)
        assertEquals(5, again.pulled)
        assertEquals(5, w.server.log.size)
    }

    @Test fun everyIngestedEventCarriesTheKeyItWasCheckedWith() = runTest {
        val w = world()
        w.enqueueAll(w.buildCircle())
        val report = w.engine.sync(w.circle)
        assertEquals(0, report.unchecked)
        val keys = w.replica.entries(w.circle).map { it.verifiedWith }
        assertEquals(w.founder.identity.signing.publicKey, keys[0])
        assertEquals(w.bea.identity.signing.publicKey, keys[3])
        assertTrue(keys.none { it == null })
    }

    @Test fun offlineKeepsEveryChangeAndReportsHowManyAreWaiting() = runTest {
        val w = world()
        w.enqueueAll(w.buildCircle())
        w.server.down = true
        val report = w.engine.sync(w.circle)
        assertEquals(SyncState.Offline(5), report.state)
        assertEquals(5, w.replica.outbox(w.circle).size)
        assertEquals(0, w.server.log.size)
        w.server.down = false
        assertEquals(SyncState.UpToDate, w.engine.sync(w.circle).state)
        assertEquals(5, w.server.log.size)
    }

    @Test fun aRepeatedSyncSendsNothingTwice() = runTest {
        val w = world()
        w.enqueueAll(w.buildCircle())
        w.engine.sync(w.circle)
        val again = w.engine.sync(w.circle)
        assertEquals(0, again.sent)
        assertEquals(0, again.pulled)
        assertEquals(5, w.server.log.size)
    }

    @Test fun anEventTheServerAlreadyHasCountsAsSent() = runTest {
        val w = world()
        val events = w.buildCircle()
        w.enqueueAll(events)
        w.engine.sync(w.circle)
        // A second device of the same replica sends the same event again (for example after a lost answer).
        w.engine.enqueue(w.circle, events[0])
        val report = w.engine.sync(w.circle)
        assertEquals(1, report.sent)
        assertEquals(5, w.server.log.size)
        assertEquals(emptyList(), w.replica.outbox(w.circle))
    }

    @Test fun aRefusedEventIsKeptAsRefusedAndNotRetried() = runTest {
        val w = world()
        val events = w.buildCircle()
        w.server.refuse[events[2].eventId(w.crypto)] = "not_steward"
        w.enqueueAll(events)
        val report = w.engine.sync(w.circle)
        assertEquals(1, report.refused)
        assertEquals(4, report.sent)
        val refused = w.replica.refused(w.circle).single()
        assertEquals(events[2].eventId(w.crypto), refused.id)
        assertEquals("not_steward: no", refused.reason)
        assertEquals(emptyList(), w.replica.outbox(w.circle))
        val calls = w.server.pushCalls
        w.engine.sync(w.circle)
        assertEquals(calls, w.server.pushCalls, "nothing left to push")
    }

    @Test fun aBusyServerKeepsTheChangesAndSaysWhenToTryAgain() = runTest {
        val w = world()
        w.enqueueAll(w.buildCircle())
        w.server.busy = true
        w.server.busyRetryAfter = 30
        assertEquals(SyncState.Busy(5, 30), w.engine.sync(w.circle).state)
        assertEquals(5, w.replica.outbox(w.circle).size)
    }

    @Test fun anExpiredLoginIsReportedAndLosesNothing() = runTest {
        val w = world()
        w.enqueueAll(w.buildCircle())
        w.server.unauthorized = true
        assertEquals(SyncState.SignedOut, w.engine.sync(w.circle).state)
        assertEquals(5, w.replica.outbox(w.circle).size)
    }

    @Test fun pullingFollowsPagesUntilTheEnd() = runTest {
        val w = world()
        w.enqueueAll(w.buildCircle())
        w.server.pageSize = SMALL_PAGE
        val report = w.engine.sync(w.circle)
        assertEquals(5, report.pulled)
        assertEquals(5, w.replica.cursor(w.circle))
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), w.replica.entries(w.circle).map { it.serverSeq })
    }

    @Test fun aSecondDevicePullsTheWholeCircleAndFoldsWithoutCheckingSignaturesAgain() = runTest {
        val w = world()
        w.enqueueAll(w.buildCircle())
        w.engine.sync(w.circle)
        // Bea's phone: a fresh replica against the same server.
        val phone = InMemoryReplica { it.eventId(w.crypto) }
        val phoneEngine = SyncEngine(w.crypto, w.server, phone)
        val afterSync = w.crypto.verifies
        phoneEngine.sync(w.circle)
        assertTrue(w.crypto.verifies > afterSync, "ingest verifies each event")
        val atIngest = w.crypto.verifies
        val view = phone.fold(w.circle, w.crypto, w.cipher, w.bea.identity)
        // The one check left is the invite proof inside the single member.join, which is not an event signature.
        assertEquals(atIngest + 1, w.crypto.verifies, "the fold repeats no event signature check")
        // Same answer as a fold that checks everything itself.
        val plain = CircleFold(w.crypto, w.cipher, w.bea.identity).fold(w.circle, phone.entries(w.circle).map { it.envelope })
        assertEquals(plain.events.map { it.id }, view.events.map { it.id })
        assertEquals(plain.stewards, view.stewards)
        assertEquals(plain.devices.keys, view.devices.keys)
        assertEquals(emptyList(), view.rejected)
        assertEquals(FIRST_EPOCH, view.currentEpoch)
        assertNotNull(view.keys[FIRST_EPOCH])
    }

    @Test fun serverEventsOutOfOrderStopTheSyncAndStoreNothing() = runTest {
        val w = world()
        w.enqueueAll(w.buildCircle())
        w.engine.sync(w.circle)
        val phone = InMemoryReplica { it.eventId(w.crypto) }
        w.server.scrambleOrder = true
        val report = SyncEngine(w.crypto, w.server, phone).sync(w.circle)
        assertTrue(report.state is SyncState.Problem)
        assertEquals(0, phone.entries(w.circle).size)
        assertEquals(0, phone.cursor(w.circle))
    }

    @Test fun eventsFromAnotherCircleAreSkippedNotStored() = runTest {
        val w = world()
        val stray = w.founder.circleCreate(newCircleId(w.crypto), DAY)
        w.server.log += StoredEvent(1, stray.toDto())
        w.server.log += StoredEvent(2, w.founder.circleCreate(w.circle, DAY).toDto())
        val report = w.engine.sync(w.circle)
        assertEquals(1, report.skipped)
        assertEquals(1, report.pulled)
        assertEquals(2, w.replica.cursor(w.circle), "the cursor still moves past the stray event")
    }

    @Test fun malformedEventsAreSkipped() = runTest {
        val w = world()
        val good = w.founder.circleCreate(w.circle, DAY).toDto()
        w.server.log += StoredEvent(1, good.copy(sig = "AAAA"))
        w.server.log += StoredEvent(2, good.copy(circle = "not-an-id"))
        w.server.log += StoredEvent(3, good)
        val report = w.engine.sync(w.circle)
        assertEquals(2, report.skipped)
        assertEquals(1, report.pulled)
    }

    @Test fun anEventFromAnUnregisteredDeviceIsStoredUncheckedAndTheFoldRefusesIt() = runTest {
        val w = world()
        // The log has no circle.create, so nobody is registered.
        w.server.log += StoredEvent(1, w.founder.invite(w.circle, InviteKey(w.crypto.generateSigningKeyPair()), DAY + 1, 1, DAY).toDto())
        val report = w.engine.sync(w.circle)
        assertEquals(1, report.unchecked)
        assertNull(w.replica.entries(w.circle).single().verifiedWith)
        val view = w.replica.fold(w.circle, w.crypto, w.cipher, null)
        assertEquals(1, view.rejected.size)
    }

    @Test fun aForgedSignatureFromARegisteredDeviceIsNeverMarkedChecked() = runTest {
        val w = world()
        val events = w.buildCircle()
        val forged = events[2].copy(sig = events[4].sig)
        w.server.log += StoredEvent(1, events[0].toDto())
        w.server.log += StoredEvent(2, forged.toDto())
        val report = w.engine.sync(w.circle)
        assertEquals(1, report.unchecked)
        val stored = w.replica.entries(w.circle)
        assertNotNull(stored[0].verifiedWith)
        assertNull(stored[1].verifiedWith)
        val view = w.replica.fold(w.circle, w.crypto, w.cipher, null)
        assertTrue(view.rejected.any { it.reason == "bad signature" })
    }

    @Test fun aRecordedCheckForAnotherKeyDoesNotSkipTheRealCheck() = runTest {
        val w = world()
        val events = w.buildCircle()
        val forged = events[2].copy(sig = events[4].sig)
        val wrongKey = w.bea.identity.signing.publicKey
        val checked = mapOf(forged.eventId(w.crypto) to wrongKey)
        val view = CircleFold(w.crypto, w.cipher, null, signatures = circles.event.VerifiedSignatures.of(checked))
            .fold(w.circle, listOf(events[0], forged))
        assertTrue(view.rejected.any { it.reason == "bad signature" })
    }

    @Test fun theDirectoryCanBeRebuiltFromStoredEntriesWithoutCrypto() = runTest {
        val w = world()
        w.enqueueAll(w.buildCircle())
        w.engine.sync(w.circle)
        val before = w.crypto.verifies
        val directory = DeviceKeyDirectory.from(w.replica.entries(w.circle))
        assertEquals(before, w.crypto.verifies)
        val event = w.bea.invite(w.circle, InviteKey(w.crypto.generateSigningKeyPair()), DAY + 1, 1, DAY)
        assertEquals(w.bea.identity.signing.publicKey, directory.keyFor(event))
    }

    @Test fun aRestartedEngineKeepsCheckingNewEventsAgainstTheStoredDevices() = runTest {
        val w = world()
        w.enqueueAll(w.buildCircle())
        w.engine.sync(w.circle)
        // New engine over the same replica, as after reopening the app; bea sends a new event.
        val restarted = SyncEngine(w.crypto, w.server, w.replica)
        val more = w.bea.invite(w.circle, InviteKey(w.crypto.generateSigningKeyPair()), DAY + 1, 1, DAY)
        restarted.enqueue(w.circle, more)
        val report = restarted.sync(w.circle)
        assertEquals(0, report.unchecked)
        assertEquals(w.bea.identity.signing.publicKey, w.replica.entries(w.circle).last().verifiedWith)
    }
}
