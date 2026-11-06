package circles.sync

import circles.api.MAX_PULL_LIMIT
import circles.api.MAX_PUSH_EVENTS
import circles.api.PushResult
import circles.api.PushStatus
import circles.crypto.Crypto
import circles.encoding.Parsed
import circles.event.Envelope
import circles.event.EnvelopeChecks
import circles.event.EventId
import circles.policy.CircleId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the status line shows (spec 6): "up to date / syncing / offline, N changes waiting". */
sealed interface SyncState {
    data object UpToDate : SyncState
    data object Syncing : SyncState
    data class Offline(val changesWaiting: Int) : SyncState
    data class Busy(val changesWaiting: Int, val retryAfterSeconds: Long?) : SyncState
    data object SignedOut : SyncState
    data class Problem(val message: String, val changesWaiting: Int) : SyncState
}

data class SyncReport(
    val state: SyncState,
    /** Own events the server took (accepted or already there). */
    val sent: Int,
    /** Own events the server refused for good; they are in [Replica.refused]. */
    val refused: Int,
    /** New log entries stored. */
    val pulled: Int,
    /** Pulled events dropped because they were malformed or from another circle; a healthy server sends none. */
    val skipped: Int,
    /** Events whose signature could not be checked at ingest (unknown device). The fold decides what to do with them. */
    val unchecked: Int,
)

/** What the scheduler needs from the engine: run one sync of one circle. */
fun interface Syncer {
    suspend fun sync(circle: CircleId): SyncReport
}

/**
 * Pushes the outbox, then pulls the log, for one circle (spec 6). Each pulled event is shape-checked and its signature
 * verified once, here, and the result is stored with it (ADR 0036), so opening the app never repeats that work.
 * Scheduling (on open, `online`, after writes, on a hint, with [Backoff]) belongs to the caller.
 */
class SyncEngine(
    private val crypto: Crypto,
    private val api: SyncApi,
    private val replica: Replica,
) : Syncer {
    private val mutableState = MutableStateFlow<SyncState>(SyncState.Offline(changesWaiting = 0))
    val state: StateFlow<SyncState> = mutableState.asStateFlow()

    private val directories = mutableMapOf<CircleId, DeviceKeyDirectory>()

    /** Queues an event this device built; it is sent on the next [sync]. Written locally first, so nothing is lost offline. */
    suspend fun enqueue(circle: CircleId, envelope: Envelope) {
        replica.enqueue(circle, envelope)
    }

    override suspend fun sync(circle: CircleId): SyncReport {
        mutableState.value = SyncState.Syncing
        var sent = 0
        var refused = 0
        var pulled = 0
        var skipped = 0
        var unchecked = 0
        // Events the server has taken in this run. They stay in the outbox until the pull brings them back into the log
        // (Replica.append drops them then), so a fold during the pull still sees them: dropping them on the push answer
        // left a gap in which the device's own events were in neither place, and a founder's brand-new circle looked
        // unreadable (the "Your name" page refused to save). If the pull fails they are dropped as before.
        val taken = mutableSetOf<EventId>()
        suspend fun forgetTaken() {
            if (taken.isNotEmpty()) replica.dropFromOutbox(circle, taken)
        }
        fun report(state: SyncState) = SyncReport(state, sent, refused, pulled, skipped, unchecked).also { mutableState.value = state }
        suspend fun waiting() = replica.outbox(circle).count { it.eventId(crypto) !in taken }
        suspend fun problem(text: String): SyncReport {
            forgetTaken()
            return report(SyncState.Problem(text, waiting()))
        }
        suspend fun failed(outcome: ApiOutcome<*>): SyncReport {
            forgetTaken()
            return report(failure(outcome, waiting()))
        }

        // Push first, in batches the server accepts.
        while (true) {
            val batch = replica.outbox(circle).filter { it.eventId(crypto) !in taken }.take(MAX_PUSH_EVENTS)
            if (batch.isEmpty()) break
            when (val outcome = api.push(circle, batch)) {
                is ApiOutcome.Ok -> {
                    val byId = outcome.value.filter { it.id != null }.associateBy { EventId(it.id!!) }
                    var progressed = false
                    for (event in batch) {
                        val id = event.eventId(crypto)
                        val result = byId[id] ?: continue
                        progressed = true
                        if (result.status == PushStatus.REJECTED) {
                            replica.markRefused(circle, id, event.type, describe(result))
                            refused++
                        } else {
                            taken += id
                            sent++
                        }
                    }
                    if (!progressed) return problem("The server did not answer for any of the changes sent")
                }
                else -> return failed(outcome)
            }
        }

        // Then pull until the server has nothing more.
        var cursor = replica.cursor(circle)
        while (true) {
            val page = when (val outcome = api.pull(circle, cursor, MAX_PULL_LIMIT)) {
                is ApiOutcome.Ok -> outcome.value
                else -> return failed(outcome)
            }
            val directory = directories.getOrPut(circle) { DeviceKeyDirectory.from(replica.entries(circle)) }
            val entries = mutableListOf<LogEntry>()
            var last = cursor
            for (stored in page.events) {
                if (stored.serverSeq <= last) return problem("The server sent events out of order")
                last = stored.serverSeq
                val envelope = (stored.envelope.toEnvelope() as? Parsed.Ok)?.value
                if (envelope == null || envelope.circle != circle || EnvelopeChecks.check(envelope) != null) {
                    skipped++
                    continue
                }
                val key = directory.keyFor(envelope)
                val checkedWith = key?.takeIf { envelope.hasValidSignature(crypto, it) }
                if (checkedWith != null) directory.learn(envelope) else unchecked++
                entries += LogEntry(stored.serverSeq, envelope.eventId(crypto), envelope, checkedWith)
            }
            if (page.next < last || (page.hasMore && page.next <= cursor)) {
                return problem("The server's cursor did not move forward")
            }
            replica.append(circle, entries, page.next, entries.map { it.id }.toSet())
            pulled += entries.size
            cursor = maxOf(cursor, page.next)
            if (!page.hasMore) break
        }
        forgetTaken() // the ones the log did not bring back (the server already had them before our cursor)
        return report(SyncState.UpToDate)
    }

    private fun describe(result: PushResult): String =
        listOfNotNull(result.reason, result.detail).joinToString(": ").ifEmpty { "refused" }

    private fun failure(outcome: ApiOutcome<*>, waiting: Int): SyncState = when (outcome) {
        ApiOutcome.Offline -> SyncState.Offline(waiting)
        ApiOutcome.Unauthorized -> SyncState.SignedOut
        is ApiOutcome.Busy -> SyncState.Busy(waiting, outcome.retryAfterSeconds)
        is ApiOutcome.Refused -> SyncState.Problem("The server refused the request (${outcome.status}): ${outcome.title}", waiting)
        is ApiOutcome.Ok -> error("not a failure")
    }
}
