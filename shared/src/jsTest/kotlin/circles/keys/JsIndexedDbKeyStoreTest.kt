package circles.keys

import circles.crypto.JsCipher
import circles.crypto.JsCrypto
import circles.event.DeviceIdentity
import circles.sync.IdbOpenResult
import circles.sync.IndexedDbReplica
import circles.sync.LogEntry
import circles.sync.indexedDB
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

private var counter = 0

private fun freshName(): String = "keys-${counter++}-${Random.nextInt()}"

private val crypto = JsCrypto()

private suspend fun open(name: String): IndexedDbReplica =
    assertIs<IdbOpenResult.Opened>(IndexedDbReplica.open({ it.eventId(crypto) }, name)).replica

/** The same contract as the in-memory store, against IndexedDB. */
class JsIndexedDbKeyStoreTest : KeyStoreContract() {
    override suspend fun newStore(): KeyStore {
        indexedDB
        return open(freshName())
    }
}

class JsIndexedDbKeyStoreStorageTest {
    @Test fun theWrappedIdentitySurvivesReopeningAndStillUnwraps() = runTest {
        indexedDB
        val name = freshName()
        val cipher = JsCipher()
        val hasher = JsPasswordHasher()
        val identity = DeviceIdentity.generate(crypto, cipher)
        val fast = Argon2Params(1_024, 1, 1)
        val wrapped = SeedWrapping.wrapWithPassphrase(crypto, cipher, hasher, identity.seed!!, identity.device, "correct horse battery staple", fast)
        val first = open(name)
        first.save(StoredIdentity(identity.member.v, identity.device.v, wrapped))
        first.close()
        val stored = assertIs<StoredIdentity>(open(name).load())
        val opened = assertIs<UnwrapResult.Opened>(SeedWrapping.unwrapWithPassphrase(cipher, hasher, stored.wrapped, stored.deviceId, "correct horse battery staple"))
        assertEquals(identity.seed, opened.seed)
    }

    @Test fun upgradingAVersionOneDatabaseKeepsItsEventsAndAddsTheKeyStore() = runTest {
        indexedDB
        val name = freshName()
        // Build a database exactly as version 1 left it: no `keys` store.
        val legacy = js("indexedDB")
        val ready = kotlinx.coroutines.CompletableDeferred<Unit>()
        val request = legacy.open(name, 1)
        request.onupgradeneeded = { _: dynamic ->
            val db = request.result
            db.createObjectStore("events", js("({keyPath: ['circle', 'serverSeq']})"))
            db.createObjectStore("meta", js("({keyPath: 'k'})"))
            for (queue in arrayOf("outbox", "refused")) db.createObjectStore(queue, js("({keyPath: ['circle', 'n']})")).createIndex("byId", js("['circle', 'id']"), js("({unique: true})"))
        }
        request.onsuccess = { _: dynamic -> request.result.close(); ready.complete(Unit) }
        ready.await()
        val replica = open(name)
        assertNull(replica.load())
        val cipher = JsCipher()
        val author = circles.event.EventAuthor(crypto, DeviceIdentity.generate(crypto, cipher), cipher)
        val circle = circles.event.newCircleId(crypto)
        val event = author.circleCreate(circle, 20_000)
        replica.append(circle, listOf(LogEntry(1, event.eventId(crypto), event, null)), 1, emptySet())
        assertEquals(1, replica.entries(circle).size)
    }
}
