package circles.sync

import circles.api.Wire
import circles.encoding.ByteString
import circles.encoding.Parsed
import circles.event.Envelope
import circles.event.EnvelopeDto
import circles.event.EventId
import circles.event.toDto
import circles.keys.KeyStore
import circles.keys.StoredIdentity
import circles.policy.CircleId
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Whether the browser gave us a database. It can refuse (private browsing in some browsers, a full disk, a blocked upgrade). */
sealed interface IdbOpenResult {
    data class Opened(val replica: IndexedDbReplica) : IdbOpenResult

    data class Unavailable(val reason: String) : IdbOpenResult
}

class IdbException(message: String) : RuntimeException(message)

private const val DB_VERSION = 2
private const val STORE_EVENTS = "events"
private const val STORE_OUTBOX = "outbox"
private const val STORE_REFUSED = "refused"
private const val STORE_META = "meta"
private const val STORE_KEYS = "keys"
private const val KEY_IDENTITY = "identity"
private const val INDEX_BY_ID = "byId"
private const val META_CURSOR = "cursor:"
private const val META_OUTBOX_COUNTER = "outbox:"
private const val MODE_READ = "readonly"
private const val MODE_WRITE = "readwrite"
private const val SEQUENCE_UPPER_BOUND = 9_007_199_254_740_991.0

/**
 * The device's [Replica] in IndexedDB (ADR 0036). One database, four stores: `events` keyed `[circle, serverSeq]`,
 * `outbox` and `refused` keyed `[circle, n]` with a unique `[circle, id]` index, and `meta` for cursors and counters.
 * Every write is one transaction, so a crash leaves either all of a pulled page and its cursor or none of it.
 * Envelopes are kept as the JSON the server sent. Transactions are written with callbacks only: awaiting a coroutine
 * inside one would let the browser commit it early.
 */
class IndexedDbReplica private constructor(
    private val db: IdbDatabase,
    private val idOf: (Envelope) -> EventId,
) : Replica, KeyStore {

    override suspend fun cursor(circle: CircleId): Long {
        val tx = db.transaction(arrayOf(STORE_META), MODE_READ)
        val request = tx.objectStore(STORE_META).get(META_CURSOR + circle.v)
        val row = request.await().value
        return (row?.value as? Number)?.toLong() ?: 0L
    }

    override suspend fun entries(circle: CircleId, after: Long): List<LogEntry> {
        val tx = db.transaction(arrayOf(STORE_EVENTS), MODE_READ)
        val rows = tx.objectStore(STORE_EVENTS).getAll(range(circle, after + 1)).await().asRows()
        return rows.map { row ->
            LogEntry(
                serverSeq = (row.serverSeq as Number).toLong(),
                id = EventId(row.id as String),
                envelope = decode(row.wire as String),
                verifiedWith = (row.key as? String)?.let { ByteString.fromBase64Url(it) ?: throw IdbException("a stored key is damaged") },
            )
        }
    }

    override suspend fun append(circle: CircleId, entries: List<LogEntry>, cursor: Long, delivered: Set<EventId>) {
        transact(arrayOf(STORE_EVENTS, STORE_OUTBOX, STORE_META)) { tx ->
            val events = tx.objectStore(STORE_EVENTS)
            for (entry in entries) {
                events.put(
                    jsObject {
                        this.circle = circle.v
                        this.serverSeq = entry.serverSeq.toDouble()
                        this.id = entry.id.v
                        this.wire = wire(entry.envelope)
                        this.key = entry.verifiedWith?.toBase64Url()
                    },
                )
            }
            removeFromOutbox(tx, STORE_OUTBOX, circle, delivered)
            val meta = tx.objectStore(STORE_META)
            meta.get(META_CURSOR + circle.v).onsuccess = { e ->
                val current = ((e.target.asDynamic().result)?.value as? Number)?.toLong() ?: 0L
                meta.put(metaRow(META_CURSOR + circle.v, maxOf(current, cursor).toDouble()))
            }
        }
    }

    override suspend fun outbox(circle: CircleId): List<Envelope> {
        val tx = db.transaction(arrayOf(STORE_OUTBOX), MODE_READ)
        return tx.objectStore(STORE_OUTBOX).getAll(range(circle, 0)).await().asRows().map { decode(it.wire as String) }
    }

    override suspend fun enqueue(circle: CircleId, envelope: Envelope) {
        val id = idOf(envelope).v
        transact(arrayOf(STORE_OUTBOX, STORE_META)) { tx ->
            val outbox = tx.objectStore(STORE_OUTBOX)
            outbox.index(INDEX_BY_ID).getKey(arrayOf(circle.v, id)).onsuccess = { e ->
                if (e.target.asDynamic().result == undefined) {
                    nextNumber(tx, circle) { n -> outbox.put(queueRow(circle, n, id, envelope)) }
                }
            }
        }
    }

    override suspend fun dropFromOutbox(circle: CircleId, ids: Set<EventId>) {
        transact(arrayOf(STORE_OUTBOX)) { tx -> removeFromOutbox(tx, STORE_OUTBOX, circle, ids) }
    }

    override suspend fun markRefused(circle: CircleId, id: EventId, type: String, reason: String) {
        transact(arrayOf(STORE_OUTBOX, STORE_REFUSED, STORE_META)) { tx ->
            removeFromOutbox(tx, STORE_OUTBOX, circle, setOf(id))
            val refused = tx.objectStore(STORE_REFUSED)
            refused.index(INDEX_BY_ID).getKey(arrayOf(circle.v, id.v)).onsuccess = { e ->
                if (e.target.asDynamic().result == undefined) {
                    nextNumber(tx, circle) { n ->
                        refused.put(
                            jsObject {
                                this.circle = circle.v
                                this.n = n
                                this.id = id.v
                                this.type = type
                                this.reason = reason
                            },
                        )
                    }
                }
            }
        }
    }

    override suspend fun refused(circle: CircleId): List<RefusedEvent> {
        val tx = db.transaction(arrayOf(STORE_REFUSED), MODE_READ)
        return tx.objectStore(STORE_REFUSED).getAll(range(circle, 0)).await().asRows()
            .map { RefusedEvent(EventId(it.id as String), it.type as String, it.reason as String) }
    }

    // ---- KeyStore (ADR 0039): the one wrapped identity of this browser profile, in the same database ----

    override suspend fun load(): StoredIdentity? {
        val tx = db.transaction(arrayOf(STORE_KEYS), MODE_READ)
        val row = tx.objectStore(STORE_KEYS).get(KEY_IDENTITY).await().value ?: return null
        return Wire.decodeStoredIdentity(row.json as String)
    }

    override suspend fun save(identity: StoredIdentity) {
        transact(arrayOf(STORE_KEYS)) { tx ->
            tx.objectStore(STORE_KEYS).put(
                jsObject {
                    this.k = KEY_IDENTITY
                    this.json = Wire.encode(identity)
                },
            )
        }
    }

    override suspend fun erase() {
        transact(arrayOf(STORE_KEYS)) { tx -> tx.objectStore(STORE_KEYS).delete(KEY_IDENTITY) }
    }

    /** Empties the log, outbox, refused and meta stores in one transaction; the keys store is [erase]'s business. */
    override suspend fun clear() {
        transact(arrayOf(STORE_EVENTS, STORE_OUTBOX, STORE_REFUSED, STORE_META)) { tx ->
            for (store in listOf(STORE_EVENTS, STORE_OUTBOX, STORE_REFUSED, STORE_META)) tx.objectStore(store).clear()
        }
    }

    /** Closes the connection; a later upgrade by another tab is then not blocked. */
    fun close() = db.close()

    // ---- helpers ----

    private fun wire(envelope: Envelope): String = Wire.encode(envelope.toDto())

    private fun decode(text: String): Envelope =
        when (val parsed = (Wire.decodeEnvelope(text) ?: throw IdbException("a stored event is damaged: not valid JSON")).toEnvelope()) {
            is Parsed.Ok -> parsed.value
            is Parsed.Invalid -> throw IdbException("a stored event is damaged: ${parsed.reason}")
        }

    private fun queueRow(circle: CircleId, n: Double, id: String, envelope: Envelope): dynamic =
        jsObject {
            this.circle = circle.v
            this.n = n
            this.id = id
            this.wire = wire(envelope)
        }

    private fun metaRow(key: String, value: Double): dynamic =
        jsObject {
            this.k = key
            this.value = value
        }

    private fun range(circle: CircleId, from: Long): dynamic =
        js("IDBKeyRange").bound(arrayOf(circle.v, from.toDouble()), arrayOf(circle.v, SEQUENCE_UPPER_BOUND))

    /** Within a transaction: reads and bumps the per-circle counter, then runs [then] with the new number. */
    private fun nextNumber(tx: IdbTransaction, circle: CircleId, then: (Double) -> Unit) {
        val meta = tx.objectStore(STORE_META)
        val key = META_OUTBOX_COUNTER + circle.v
        meta.get(key).onsuccess = { e ->
            val n = (((e.target.asDynamic().result)?.value as? Number)?.toDouble() ?: 0.0) + 1
            meta.put(metaRow(key, n))
            then(n)
        }
    }

    private fun removeFromOutbox(tx: IdbTransaction, store: String, circle: CircleId, ids: Set<EventId>) {
        val outbox = tx.objectStore(store)
        for (id in ids) {
            outbox.index(INDEX_BY_ID).getKey(arrayOf(circle.v, id.v)).onsuccess = { e ->
                val key = e.target.asDynamic().result
                if (key != undefined) outbox.delete(key)
            }
        }
    }

    private suspend fun transact(stores: Array<String>, body: (IdbTransaction) -> Unit) {
        suspendCancellableCoroutine { cont ->
            val tx = db.transaction(stores, MODE_WRITE)
            tx.oncomplete = { cont.resume(Unit) }
            tx.onerror = { cont.resumeWithException(IdbException("the database refused a write: ${describe(tx.error)}")) }
            tx.onabort = { if (cont.isActive) cont.resumeWithException(IdbException("the database cancelled a write: ${describe(tx.error)}")) }
            body(tx)
        }
    }

    private suspend fun IdbRequest.await(): Wrapped = suspendCancellableCoroutine { cont ->
        onsuccess = { cont.resume(Wrapped(result)) }
        onerror = { cont.resumeWithException(IdbException("the database could not read: ${describe(error)}")) }
    }

    /** A request result wrapped so `null` and `undefined` do not get confused with a missing value. */
    private class Wrapped(val raw: dynamic) {
        val value: dynamic get() = raw
        fun asRows(): List<dynamic> = (raw as Array<dynamic>).toList()
    }

    companion object {
        /** Opens (and on first use creates) the database called [name]. */
        suspend fun open(idOf: (Envelope) -> EventId, name: String = "circles", factory: dynamic = js("globalThis.indexedDB")): IdbOpenResult {
            if (factory == null || factory == undefined) return IdbOpenResult.Unavailable("this browser has no IndexedDB")
            return suspendCancellableCoroutine { cont ->
                val request = try {
                    (factory as IdbFactory).open(name, DB_VERSION)
                } catch (e: Throwable) {
                    cont.resume(IdbOpenResult.Unavailable("IndexedDB refused to open: ${e.message}"))
                    return@suspendCancellableCoroutine
                }
                request.onupgradeneeded = { upgrade(request.result as IdbDatabase) }
                request.onsuccess = { cont.resume(IdbOpenResult.Opened(IndexedDbReplica(request.result as IdbDatabase, idOf))) }
                request.onerror = { cont.resume(IdbOpenResult.Unavailable("IndexedDB could not open: ${describe(request.error)}")) }
                request.onblocked = { cont.resume(IdbOpenResult.Unavailable("another tab is holding an older version of the database open")) }
            }
        }

        private fun upgrade(db: IdbDatabase) {
            val names = db.objectStoreNames
            fun has(name: String): Boolean = names.contains(name) as Boolean
            if (!has(STORE_EVENTS)) db.createObjectStore(STORE_EVENTS, js("{keyPath: ['circle', 'serverSeq']}"))
            for (queue in listOf(STORE_OUTBOX, STORE_REFUSED)) {
                if (!has(queue)) {
                    db.createObjectStore(queue, js("{keyPath: ['circle', 'n']}")).createIndex(INDEX_BY_ID, js("['circle', 'id']"), js("{unique: true}"))
                }
            }
            if (!has(STORE_META)) db.createObjectStore(STORE_META, js("{keyPath: 'k'}"))
            if (!has(STORE_KEYS)) db.createObjectStore(STORE_KEYS, js("{keyPath: 'k'}"))
        }

        private fun describe(error: dynamic): String = (error?.message as? String) ?: (error?.name as? String) ?: "unknown error"
    }
}

/** A plain JavaScript object filled in by [fill], for IndexedDB rows. */
private fun jsObject(fill: dynamic.() -> Unit): dynamic {
    val row = js("({})")
    fill(row)
    return row
}
