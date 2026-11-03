package circles.sync

import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.crypto.JsCipher
import circles.crypto.JsCrypto
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.test.runTest

private var counter = 0

private fun freshName(): String = "test-${counter++}-${Random.nextInt()}"

/** The same contract as the in-memory replica, against IndexedDB (an in-memory implementation of it, in Node). */
class JsIndexedDbReplicaTest : ReplicaContract() {
    override fun newCrypto(): Crypto = JsCrypto()
    override fun newCipher(): Cipher = JsCipher()

    override suspend fun newReplica(crypto: Crypto): Replica {
        indexedDB // installs the globals
        val opened = IndexedDbReplica.open({ it.eventId(crypto) }, freshName())
        return assertIs<IdbOpenResult.Opened>(opened).replica
    }
}

class JsIndexedDbReplicaStorageTest {
    private val crypto = JsCrypto()

    @Test fun dataSurvivesClosingAndReopening() = runTest {
        indexedDB
        val name = freshName()
        val idOf = { e: circles.event.Envelope -> e.eventId(crypto) }
        val author = circles.event.EventAuthor(crypto, circles.event.DeviceIdentity.generate(crypto, JsCipher()), JsCipher())
        val circle = circles.event.newCircleId(crypto)
        val event = author.circleCreate(circle, 20_000)
        val first = assertIs<IdbOpenResult.Opened>(IndexedDbReplica.open(idOf, name)).replica
        first.append(circle, listOf(LogEntry(1, event.eventId(crypto), event, author.identity.signing.publicKey)), 1, emptySet())
        first.enqueue(circle, author.circleCreate(circle, 20_001))
        first.close()
        val second = assertIs<IdbOpenResult.Opened>(IndexedDbReplica.open(idOf, name)).replica
        assertEquals(1, second.cursor(circle))
        val stored = second.entries(circle).single()
        assertEquals(event, stored.envelope)
        assertEquals(author.identity.signing.publicKey, stored.verifiedWith)
        assertEquals(1, second.outbox(circle).size)
    }

    @Test fun noIndexedDbIsReportedNotThrown() = runTest {
        val result = IndexedDbReplica.open({ it.eventId(crypto) }, factory = null)
        assertIs<IdbOpenResult.Unavailable>(result)
    }

    @Test fun aWholePageIsOneTransactionAndTheOutboxFollows() = runTest {
        indexedDB
        val idOf = { e: circles.event.Envelope -> e.eventId(crypto) }
        val replica = assertIs<IdbOpenResult.Opened>(IndexedDbReplica.open(idOf, freshName())).replica
        val author = circles.event.EventAuthor(crypto, circles.event.DeviceIdentity.generate(crypto, JsCipher()), JsCipher())
        val circle = circles.event.newCircleId(crypto)
        val events = List(300) { author.circleCreate(circle, 20_000 + it) }
        events.forEach { replica.enqueue(circle, it) }
        assertEquals(events, replica.outbox(circle), "oldest first")
        val entries = events.mapIndexed { i, e -> LogEntry(i + 1L, e.eventId(crypto), e, null) }
        replica.append(circle, entries, 300, events.map { it.eventId(crypto) }.toSet())
        assertEquals(0, replica.outbox(circle).size)
        assertEquals(300, replica.entries(circle).size)
        assertEquals(100, replica.entries(circle, after = 200).size)
        assertEquals(300, replica.cursor(circle))
    }
}
