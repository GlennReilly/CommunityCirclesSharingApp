package circles.sync

import circles.policy.CircleId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/** Why a sync is wanted (spec 6: on open, on `online`, after local writes, on an SSE hint). */
enum class SyncTrigger(val debounceMillis: Long, val urgent: Boolean) {
    /** The app opened. */
    OPEN(0, false),

    /** The browser came back online: try now, even in the middle of a backoff. */
    ONLINE(0, true),

    /** The user changed something; a short pause lets a burst of edits go out together. */
    LOCAL_WRITE(SyncScheduler.LOCAL_WRITE_DEBOUNCE_MILLIS, false),

    /** The server said there is something new; the delay is random so a whole circle does not pull at once. */
    HINT(SyncScheduler.HINT_SPREAD_MILLIS, false),

    /** The user asked ("sync now"), or signed in again. */
    MANUAL(0, true),
}

/**
 * Decides when to call the engine for one circle (spec 6). Triggers are coalesced: any number arriving while a sync is
 * waiting or running lead to one more run. After a failure it retries with [Backoff]; only [SyncTrigger.ONLINE] and
 * [SyncTrigger.MANUAL] cut a wait short, so a server that said "busy" is not hammered by hints and edits. After
 * [SyncState.SignedOut] it stops until the user acts. Time comes from coroutine `delay`, so tests use virtual time.
 */
class SyncScheduler(
    private val scope: CoroutineScope,
    private val syncer: Syncer,
    private val circle: CircleId,
    private val random: () -> Double = { Random.nextDouble() },
) {
    private val wake = Signal<SyncTrigger>()
    private val urgent = Signal<Unit>()
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** Asks for a sync. Safe to call from any event handler; it never blocks. */
    fun trigger(reason: SyncTrigger) {
        wake.send(reason)
        if (reason.urgent) urgent.send(Unit)
    }

    private suspend fun loop() {
        var failures = 0
        var reason = wake.receive()
        while (true) {
            delay(spread(reason))
            // Anything that arrived while waiting is covered by this run, including the urgent signal that started it.
            wake.take()
            urgent.take()
            val report = syncer.sync(circle)
            when (val state = report.state) {
                is SyncState.SignedOut -> {
                    urgent.receive()
                    failures = 0
                    reason = SyncTrigger.MANUAL
                }
                is SyncState.UpToDate, is SyncState.Syncing -> {
                    failures = 0
                    reason = wake.receive()
                }
                else -> {
                    val wait = Backoff.delayMillis(failures, random(), (state as? SyncState.Busy)?.retryAfterSeconds)
                    failures++
                    val interrupted = withTimeoutOrNull(wait) { urgent.receive() } != null
                    if (interrupted) failures = 0
                    wake.take()
                    urgent.take()
                    reason = if (interrupted) SyncTrigger.MANUAL else SyncTrigger.OPEN
                }
            }
        }
    }

    private fun spread(reason: SyncTrigger): Long = when (reason) {
        SyncTrigger.HINT -> (random() * HINT_SPREAD_MILLIS).toLong()
        else -> reason.debounceMillis
    }

    companion object {
        const val LOCAL_WRITE_DEBOUNCE_MILLIS = 500L
        const val HINT_SPREAD_MILLIS = 2_000L
    }
}

/**
 * A one-place mailbox for one waiting reader: a later [send] replaces an earlier one that was not read yet, and [receive]
 * waits for one. It replaces a conflated channel, whose machinery is far bigger than this need (the page is size-limited,
 * ADR 0009). Used from a single thread, as everything in the page and in the virtual-time tests is.
 */
internal class Signal<T : Any> {
    private var pending: T? = null
    private var waiter: CancellableContinuation<T>? = null

    fun send(value: T) {
        val reader = waiter
        waiter = null
        // A reader that was cancelled has already removed itself (invokeOnCancellation runs at once), so a signal is never handed to nobody.
        if (reader == null) pending = value else reader.resume(value)
    }

    /** Takes what is waiting, if anything, without waiting. */
    fun take(): T? = pending.also { pending = null }

    suspend fun receive(): T {
        take()?.let { return it }
        return suspendCancellableCoroutine { reader ->
            waiter = reader
            reader.invokeOnCancellation { if (waiter === reader) waiter = null }
        }
    }
}
