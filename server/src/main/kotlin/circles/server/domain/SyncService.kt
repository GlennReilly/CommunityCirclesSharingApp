package circles.server.domain

import circles.api.MAX_PULL_LIMIT
import circles.api.PullResponse
import circles.api.PushResult
import circles.api.PushStatus
import circles.crypto.Crypto
import circles.encoding.Parsed
import circles.event.EnvelopeChecks
import circles.event.EnvelopeDto
import circles.event.EventId
import circles.event.Rejection
import circles.event.RejectionCode
import circles.policy.CircleId
import circles.server.logging.Log
import circles.server.logging.NoLog
import circles.server.logging.info
import java.time.Clock

const val MILLIS_PER_DAY = 86_400_000L

/** Which door an event came through. The two public ones need no session (ADR 0014). */
sealed interface Entrance {
    /** Authenticated push to an existing circle. */
    data class Push(val circle: CircleId) : Entrance

    /** `POST /circles`: one `circle.create`. */
    data object Create : Entrance

    /** `POST /circles/{id}/join`: one `member.join`. */
    data class Join(val circle: CircleId) : Entrance
}

/** Accepts, deduplicates and stores events, and serves pages of them back. */
class SyncService(
    private val store: Store,
    private val crypto: Crypto,
    private val clock: Clock,
    private val rules: EventRules,
    private val creationAllowed: Boolean,
    private val log: Log = NoLog,
) {
    val isCreationAllowed: Boolean get() = creationAllowed

    /** Judges each event on its own, in order, in one transaction; a refused event writes nothing. */
    fun submit(events: List<EnvelopeDto>, entrance: Entrance): List<PushResult> {
        val now = clock.millis()
        val today = Math.floorDiv(now, MILLIS_PER_DAY).toInt()
        val results = store.transaction { tx -> events.map { submitOne(it, entrance, now, today, tx) } }
        logSummary(entrance, results)
        return results
    }

    fun pull(circle: CircleId, after: Long, limit: Int): PullResponse {
        val pageSize = limit.coerceIn(1, MAX_PULL_LIMIT)
        val rows = store.transaction { it.eventsAfter(circle, after, pageSize + 1) }
        val page = rows.take(pageSize)
        return PullResponse(events = page, next = page.lastOrNull()?.serverSeq ?: after, hasMore = rows.size > pageSize)
    }

    private fun submitOne(dto: EnvelopeDto, entrance: Entrance, now: Long, today: Int, tx: StoreTransaction): PushResult {
        val envelope = when (val parsed = dto.toEnvelope()) {
            is Parsed.Ok -> parsed.value
            is Parsed.Invalid -> return refused(null, Rejection(RejectionCode.MALFORMED, parsed.reason))
        }
        val id = envelope.eventId(crypto)
        EnvelopeChecks.check(envelope)?.let { return refused(id, it) }
        wrongDoor(envelope.circle, envelope.type, entrance)?.let { return refused(id, it) }
        tx.findEventSeq(envelope.circle, id)?.let { return PushResult(id.v, PushStatus.DUPLICATE, serverSeq = it) }
        return when (val decision = rules.decide(envelope, id, today, tx, creationAllowed)) {
            is Decision.Reject -> refused(id, decision.rejection)
            is Decision.Accept -> {
                val seq = tx.appendEvent(id, envelope, now)
                decision.effects.forEach { tx.apply(it, seq) }
                PushResult(id.v, PushStatus.ACCEPTED, serverSeq = seq)
            }
        }
    }

    /** Counts only: no ids, keys or payloads (ADR 0016). */
    private fun logSummary(entrance: Entrance, results: List<PushResult>) {
        log.info(
            "events_submitted",
            "entrance" to when (entrance) {
                is Entrance.Push -> "push"
                Entrance.Create -> "create"
                is Entrance.Join -> "join"
            },
            "accepted" to results.count { it.status == PushStatus.ACCEPTED },
            "duplicate" to results.count { it.status == PushStatus.DUPLICATE },
            "rejected" to results.count { it.status == PushStatus.REJECTED },
            "reasons" to results.mapNotNull { it.reason }.groupingBy { it }.eachCount(),
        )
    }

    private fun wrongDoor(circle: CircleId, type: String, entrance: Entrance): Rejection? =
        when (entrance) {
            is Entrance.Push -> if (circle != entrance.circle) Rejection(RejectionCode.WRONG_CIRCLE, "event is for another circle") else null
            is Entrance.Join -> when {
                circle != entrance.circle -> Rejection(RejectionCode.WRONG_CIRCLE, "event is for another circle")
                type != JOIN_TYPE -> Rejection(RejectionCode.UNSUPPORTED_TYPE, "this endpoint takes member.join only")
                else -> null
            }
            Entrance.Create -> if (type != CREATE_TYPE) Rejection(RejectionCode.UNSUPPORTED_TYPE, "this endpoint takes circle.create only") else null
        }

    private fun refused(id: EventId?, rejection: Rejection): PushResult =
        PushResult(id?.v, PushStatus.REJECTED, reason = rejection.code.wire, detail = rejection.detail)

    private companion object {
        const val CREATE_TYPE = "circle.create"
        const val JOIN_TYPE = "member.join"
    }
}

