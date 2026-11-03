package circles.sync

import circles.encoding.ByteString
import circles.event.Envelope
import circles.event.EventId
import circles.policy.CircleId

/** One event of the local copy of a circle's log, as the server ordered it. */
data class LogEntry(
    val serverSeq: Long,
    val id: EventId,
    val envelope: Envelope,
    /** The public key the signature was checked against, or null when it could not be checked yet (ADR 0036). */
    val verifiedWith: ByteString?,
)

/** An event this device built that the server refused for good. */
data class RefusedEvent(val id: EventId, val type: String, val reason: String)

/**
 * The device's local store (ADR 0036): the log as received, the cursor, and the outbox of events not yet accepted.
 * The browser implements it over IndexedDB; [InMemoryReplica] is the reference implementation for tests.
 */
interface Replica {
    /** Highest `serverSeq` stored for [circle]; 0 when nothing has been pulled. */
    suspend fun cursor(circle: CircleId): Long

    /** Entries with `serverSeq` above [after], in order. */
    suspend fun entries(circle: CircleId, after: Long = 0): List<LogEntry>

    /**
     * Stores [entries] and moves the cursor to [cursor] in one step, and removes [delivered] from the outbox (events
     * that came back in the log). Entries already stored are left alone.
     */
    suspend fun append(circle: CircleId, entries: List<LogEntry>, cursor: Long, delivered: Set<EventId>)

    /** Events waiting to be sent, oldest first. Refused events are not included. */
    suspend fun outbox(circle: CircleId): List<Envelope>

    suspend fun enqueue(circle: CircleId, envelope: Envelope)

    /** Takes events out of the outbox because the server has them (accepted or duplicate). */
    suspend fun dropFromOutbox(circle: CircleId, ids: Set<EventId>)

    /** Takes an event out of the outbox and remembers why the server would not have it. */
    suspend fun markRefused(circle: CircleId, id: EventId, type: String, reason: String)

    suspend fun refused(circle: CircleId): List<RefusedEvent>
}

/** A [Replica] in memory. Not persistent; it is the specification the IndexedDB version must match. */
class InMemoryReplica(private val idOf: (Envelope) -> EventId) : Replica {
    private class Circle {
        var cursor = 0L
        val log = mutableListOf<LogEntry>()
        val outbox = LinkedHashMap<EventId, Envelope>()
        val refused = mutableListOf<RefusedEvent>()
    }

    private val circles = mutableMapOf<CircleId, Circle>()

    private fun of(circle: CircleId) = circles.getOrPut(circle) { Circle() }

    override suspend fun cursor(circle: CircleId): Long = of(circle).cursor

    override suspend fun entries(circle: CircleId, after: Long): List<LogEntry> = of(circle).log.filter { it.serverSeq > after }

    override suspend fun append(circle: CircleId, entries: List<LogEntry>, cursor: Long, delivered: Set<EventId>) {
        val c = of(circle)
        val known = c.log.map { it.id }.toMutableSet()
        for (entry in entries) if (known.add(entry.id)) c.log += entry
        c.log.sortBy { it.serverSeq }
        c.cursor = maxOf(c.cursor, cursor)
        delivered.forEach { c.outbox.remove(it) }
    }

    override suspend fun outbox(circle: CircleId): List<Envelope> = of(circle).outbox.values.toList()

    override suspend fun enqueue(circle: CircleId, envelope: Envelope) {
        of(circle).outbox.getOrPut(idOf(envelope)) { envelope }
    }

    override suspend fun dropFromOutbox(circle: CircleId, ids: Set<EventId>) {
        ids.forEach { of(circle).outbox.remove(it) }
    }

    override suspend fun markRefused(circle: CircleId, id: EventId, type: String, reason: String) {
        val c = of(circle)
        c.outbox.remove(id)
        c.refused += RefusedEvent(id, type, reason)
    }

    override suspend fun refused(circle: CircleId): List<RefusedEvent> = of(circle).refused.toList()
}
