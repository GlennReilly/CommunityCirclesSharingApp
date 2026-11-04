package circles.sync

import circles.policy.CircleId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val circle = CircleId("c".repeat(32))
private const val MIDDLE = 0.5

/** The scheduler on virtual time with a scripted syncer: no crypto, no network. */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncSchedulerTest {
    private class Script(private val scope: TestScope) : Syncer {
        val runs = mutableListOf<Long>()
        val answers = ArrayDeque<SyncState>()
        var duration = 0L

        override suspend fun sync(circle: CircleId): SyncReport {
            runs += scope.currentTime
            if (duration > 0) delay(duration)
            val state = if (answers.size > 1) answers.removeFirst() else answers.firstOrNull() ?: SyncState.UpToDate
            return SyncReport(state, 0, 0, 0, 0, 0)
        }
    }

    private fun TestScope.fixture(random: () -> Double = { MIDDLE }): Pair<Script, SyncScheduler> {
        val script = Script(this)
        return script to SyncScheduler(backgroundScope, script, circle, random).also { it.start() }
    }

    @Test fun openingSyncsRightAway() = runTest {
        val (script, scheduler) = fixture()
        scheduler.trigger(SyncTrigger.OPEN)
        runCurrent()
        assertEquals(listOf(0L), script.runs)
    }

    @Test fun nothingRunsUntilSomethingAsks() = runTest {
        val (script, _) = fixture()
        advanceTimeBy(60_000)
        assertEquals(emptyList(), script.runs)
    }

    @Test fun aBurstOfLocalWritesGoesOutAsOneSync() = runTest {
        val (script, scheduler) = fixture()
        repeat(5) { scheduler.trigger(SyncTrigger.LOCAL_WRITE) }
        advanceTimeBy(SyncScheduler.LOCAL_WRITE_DEBOUNCE_MILLIS - 1)
        assertEquals(emptyList(), script.runs)
        advanceTimeBy(2)
        assertEquals(listOf(SyncScheduler.LOCAL_WRITE_DEBOUNCE_MILLIS), script.runs)
        advanceTimeBy(60_000)
        assertEquals(1, script.runs.size)
    }

    @Test fun aHintWaitsARandomTimeUnderTwoSeconds() = runTest {
        val (script, scheduler) = fixture(random = { 0.25 })
        scheduler.trigger(SyncTrigger.HINT)
        advanceTimeBy(499)
        assertEquals(emptyList(), script.runs)
        advanceTimeBy(2)
        assertEquals(listOf(500L), script.runs)
    }

    @Test fun triggersDuringARunLeadToExactlyOneMoreRun() = runTest {
        val (script, scheduler) = fixture()
        script.duration = 1_000
        scheduler.trigger(SyncTrigger.OPEN)
        runCurrent()
        repeat(4) { scheduler.trigger(SyncTrigger.LOCAL_WRITE) }
        advanceTimeBy(10_000)
        assertEquals(2, script.runs.size)
        assertEquals(0L, script.runs[0])
        assertTrue(script.runs[1] >= 1_000)
    }

    @Test fun failuresRetryWithGrowingBackoff() = runTest {
        val (script, scheduler) = fixture()
        script.answers += SyncState.Offline(2)
        scheduler.trigger(SyncTrigger.OPEN)
        advanceTimeBy(60_000)
        val gaps = script.runs.zipWithNext { a, b -> b - a }.take(4)
        assertEquals(listOf(0, 1, 2, 3).map { Backoff.delayMillis(it, MIDDLE) }, gaps)
    }

    @Test fun successAfterFailuresStopsTheRetriesAndResetsTheBackoff() = runTest {
        val (script, scheduler) = fixture()
        script.answers += SyncState.Offline(1)
        script.answers += SyncState.Offline(1)
        script.answers += SyncState.UpToDate
        scheduler.trigger(SyncTrigger.OPEN)
        advanceTimeBy(60_000)
        assertEquals(3, script.runs.size)
        // The next failure starts again from the base delay.
        script.answers.clear(); script.answers += SyncState.Offline(1)
        val before = script.runs.size
        scheduler.trigger(SyncTrigger.MANUAL)
        runCurrent()
        advanceTimeBy(Backoff.delayMillis(0, MIDDLE) + 1)
        assertEquals(before + 2, script.runs.size)
    }

    @Test fun comingBackOnlineCutsTheWaitShort() = runTest {
        val (script, scheduler) = fixture()
        script.answers += SyncState.Offline(1)
        scheduler.trigger(SyncTrigger.OPEN)
        runCurrent()
        advanceTimeBy(1_000)
        scheduler.trigger(SyncTrigger.ONLINE)
        runCurrent()
        assertEquals(listOf(0L, 1_000L), script.runs)
    }

    @Test fun hintsAndEditsDoNotCutABackoffShort() = runTest {
        val (script, scheduler) = fixture()
        script.answers += SyncState.Busy(1, 30)
        scheduler.trigger(SyncTrigger.OPEN)
        runCurrent()
        repeat(3) { scheduler.trigger(SyncTrigger.HINT); scheduler.trigger(SyncTrigger.LOCAL_WRITE) }
        advanceTimeBy(29_000)
        assertEquals(1, script.runs.size)
        advanceTimeBy(2_000)
        assertEquals(2, script.runs.size)
        assertTrue(script.runs[1] >= 30_000)
    }

    @Test fun theServersRetryAfterIsObeyed() = runTest {
        val (script, scheduler) = fixture()
        script.answers += SyncState.Busy(1, 45)
        scheduler.trigger(SyncTrigger.OPEN)
        advanceTimeBy(44_000)
        assertEquals(1, script.runs.size)
        advanceTimeBy(2_000)
        assertEquals(2, script.runs.size)
    }

    @Test fun signedOutStopsUntilTheUserActs() = runTest {
        val (script, scheduler) = fixture()
        script.answers += SyncState.SignedOut
        scheduler.trigger(SyncTrigger.OPEN)
        advanceTimeBy(3_600_000)
        assertEquals(1, script.runs.size)
        scheduler.trigger(SyncTrigger.LOCAL_WRITE)
        advanceTimeBy(10_000)
        assertEquals(1, script.runs.size, "an edit alone does not retry while signed out")
        script.answers.clear(); script.answers += SyncState.UpToDate
        scheduler.trigger(SyncTrigger.MANUAL)
        runCurrent()
        assertEquals(2, script.runs.size)
    }

    @Test fun stoppingEndsAllWork() = runTest {
        val (script, scheduler) = fixture()
        script.answers += SyncState.Offline(1)
        scheduler.trigger(SyncTrigger.OPEN)
        runCurrent()
        scheduler.stop()
        advanceTimeBy(600_000)
        assertEquals(1, script.runs.size)
    }

    @Test fun startingTwiceDoesNotDoubleTheWork() = runTest {
        val (script, scheduler) = fixture()
        scheduler.start()
        scheduler.trigger(SyncTrigger.OPEN)
        advanceTimeBy(1_000)
        assertEquals(1, script.runs.size)
    }

    @Test
    fun aSignalSentAfterAReaderWasCancelledIsKeptNotHandedToNobody() = runTest {
        val signal = Signal<Int>()
        val reader = launch { signal.receive() }
        runCurrent()
        reader.cancel()
        signal.send(7) // before the cancelled reader has run any clean-up
        assertEquals(7, signal.take())
        // And a waiting reader still gets the newest value at once.
        var got = 0
        val waiting = launch { got = signal.receive() }
        runCurrent()
        signal.send(9)
        runCurrent()
        assertEquals(9, got)
        waiting.join()
    }
}
