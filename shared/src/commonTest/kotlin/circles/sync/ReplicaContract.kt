package circles.sync

import circles.crypto.Crypto
import circles.encoding.ByteString
import circles.event.Envelope
import circles.event.EventId
import circles.policy.CircleId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What every [Replica] must do, so the IndexedDB version can run the same tests (ADR 0036). */
abstract class ReplicaContract {
    protected abstract fun newCrypto(): Crypto
    protected abstract fun newCipher(): circles.crypto.Cipher
    protected abstract suspend fun newReplica(crypto: Crypto): Replica

    private class Fixture(val crypto: Crypto, cipher: circles.crypto.Cipher) {
        val circle = circles.event.newCircleId(crypto)
        val author = circles.event.EventAuthor(crypto, circles.event.DeviceIdentity.generate(crypto, cipher), cipher)
        fun event(): Envelope = author.circleCreate(circle, DAY)
        fun entry(seq: Long, e: Envelope = event(), key: ByteString? = null) = LogEntry(seq, e.eventId(crypto), e, key)
    }

    private suspend fun fixture(): Triple<Fixture, Replica, CircleId> {
        val crypto = newCrypto()
        val f = Fixture(crypto, newCipher())
        return Triple(f, newReplica(crypto), f.circle)
    }

    @Test fun anEmptyReplicaStartsAtZero() = runTest {
        val (_, replica, circle) = fixture()
        assertEquals(0, replica.cursor(circle))
        assertEquals(emptyList(), replica.entries(circle))
        assertEquals(emptyList(), replica.outbox(circle))
    }

    @Test fun appendStoresEntriesInOrderAndMovesTheCursor() = runTest {
        val (f, replica, circle) = fixture()
        val a = f.entry(1); val b = f.entry(2); val c = f.entry(3)
        replica.append(circle, listOf(c, a, b), 3, emptySet())
        assertEquals(listOf(1L, 2L, 3L), replica.entries(circle).map { it.serverSeq })
        assertEquals(3, replica.cursor(circle))
        assertEquals(listOf(3L), replica.entries(circle, after = 2).map { it.serverSeq })
    }

    @Test fun appendingTheSameEntryTwiceKeepsOne() = runTest {
        val (f, replica, circle) = fixture()
        val a = f.entry(1)
        replica.append(circle, listOf(a), 1, emptySet())
        replica.append(circle, listOf(a), 1, emptySet())
        assertEquals(1, replica.entries(circle).size)
    }

    @Test fun theCursorNeverMovesBack() = runTest {
        val (f, replica, circle) = fixture()
        replica.append(circle, listOf(f.entry(5)), 5, emptySet())
        replica.append(circle, emptyList(), 2, emptySet())
        assertEquals(5, replica.cursor(circle))
    }

    @Test fun theVerifiedKeyIsStoredWithTheEntry() = runTest {
        val (f, replica, circle) = fixture()
        val key = f.author.identity.signing.publicKey
        replica.append(circle, listOf(f.entry(1, key = key), f.entry(2)), 2, emptySet())
        val stored = replica.entries(circle)
        assertEquals(key, stored[0].verifiedWith)
        assertNull(stored[1].verifiedWith)
    }

    @Test fun theOutboxKeepsOrderAndIgnoresRepeats() = runTest {
        val (f, replica, circle) = fixture()
        val a = f.event(); val b = f.event()
        replica.enqueue(circle, a); replica.enqueue(circle, b); replica.enqueue(circle, a)
        assertEquals(listOf(a, b), replica.outbox(circle))
    }

    @Test fun deliveredEventsLeaveTheOutboxWhenTheyComeBack() = runTest {
        val (f, replica, circle) = fixture()
        val a = f.event(); val b = f.event()
        replica.enqueue(circle, a); replica.enqueue(circle, b)
        replica.append(circle, listOf(f.entry(1, a)), 1, setOf(a.eventId(f.crypto)))
        assertEquals(listOf(b), replica.outbox(circle))
    }

    @Test fun refusedEventsLeaveTheOutboxAndAreRemembered() = runTest {
        val (f, replica, circle) = fixture()
        val a = f.event()
        replica.enqueue(circle, a)
        replica.markRefused(circle, a.eventId(f.crypto), a.type, "not_steward")
        assertEquals(emptyList(), replica.outbox(circle))
        assertEquals(listOf(RefusedEvent(a.eventId(f.crypto), a.type, "not_steward")), replica.refused(circle))
    }

    @Test fun droppingFromTheOutboxForgetsNothingElse() = runTest {
        val (f, replica, circle) = fixture()
        val a = f.event(); val b = f.event()
        replica.enqueue(circle, a); replica.enqueue(circle, b)
        replica.dropFromOutbox(circle, setOf(a.eventId(f.crypto), EventId("unknown")))
        assertEquals(listOf(b), replica.outbox(circle))
    }

    @Test fun circlesAreIndependent() = runTest {
        val (f, replica, circle) = fixture()
        val other = circles.event.newCircleId(f.crypto)
        replica.append(circle, listOf(f.entry(1)), 1, emptySet())
        assertEquals(0, replica.cursor(other))
        assertEquals(emptyList(), replica.entries(other))
    }


    @Test fun clearForgetsEverythingForEveryCircle() = runTest {
        val (f, replica, circle) = fixture()
        val other = circles.event.newCircleId(f.crypto)
        val a = f.event()
        replica.append(circle, listOf(f.entry(1, a)), 1, emptySet())
        replica.append(other, listOf(f.entry(1)), 1, emptySet())
        replica.enqueue(circle, f.event())
        val refused = f.event()
        replica.enqueue(other, refused)
        replica.markRefused(other, refused.eventId(f.crypto), refused.type, "no")
        replica.clear()
        for (c in listOf(circle, other)) {
            assertEquals(0, replica.cursor(c))
            assertEquals(emptyList(), replica.entries(c))
            assertEquals(emptyList(), replica.outbox(c))
            assertEquals(emptyList(), replica.refused(c))
        }
        // It is usable again afterwards.
        replica.append(circle, listOf(f.entry(1)), 1, emptySet())
        assertEquals(1, replica.entries(circle).size)
    }

    private companion object { const val DAY = 20_000 }
}
