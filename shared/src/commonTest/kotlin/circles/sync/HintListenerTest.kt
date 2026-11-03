package circles.sync

import circles.policy.CircleId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val circle = CircleId("c".repeat(32))
private const val MIDDLE = 0.5
private val STREAM_PATH = "/api/v1/circles/${"c".repeat(32)}/events/stream"

/** The listener on virtual time with a scripted stream and token source. */
@OptIn(ExperimentalCoroutinesApi::class)
class HintListenerTest {
    private class Tokens : TokenSource {
        var logins = 0
        var rejected = 0
        var outcome: ApiOutcome<String>? = null
        override suspend fun token(): ApiOutcome<String> = outcome ?: ApiOutcome.Ok("token-${++logins}")
        override fun rejectToken() { rejected++ }
    }

    /** Each call to [open] takes the next script: a status and the pieces of text it sends before it ends. */
    private class Streams(private val scope: TestScope) : StreamTransport {
        class Script(val status: Int, val texts: List<String> = emptyList(), val holdOpen: CompletableDeferred<Unit>? = null)
        val scripts = ArrayDeque<Script>()
        val opens = mutableListOf<Pair<Long, String>>() // virtual time, token
        val paths = mutableListOf<String>()

        override fun open(path: String, token: String): Flow<StreamPiece> = flow {
            opens += scope.currentTime to token
            paths += path
            val script = if (scripts.size > 1) scripts.removeFirst() else scripts.firstOrNull() ?: Script(200)
            emit(StreamPiece.Opened(script.status))
            script.texts.forEach { emit(StreamPiece.Text(it)) }
            script.holdOpen?.await()
        }
    }

    private class Fixture(scope: TestScope) {
        val tokens = Tokens()
        val streams = Streams(scope)
        val hints = mutableListOf<Long>()
        val listener = HintListener(scope.backgroundScope, streams, tokens, circle, { MIDDLE }) { hints += it }
    }

    private fun greeting(n: Long) = "data: {\"serverSeq\":$n}\n\n"

    @Test fun connectsWithTheTokenToTheCirclesStreamAndReportsHints() = runTest {
        val f = Fixture(this)
        val open = CompletableDeferred<Unit>()
        f.streams.scripts += Streams.Script(200, listOf(greeting(4), "data: {\"serverSeq\"", ":5}\n\n"), holdOpen = open)
        f.listener.start()
        runCurrent()
        assertEquals(listOf(4L, 5L), f.hints)
        assertEquals(listOf(STREAM_PATH), f.streams.paths)
        assertEquals("token-1", f.streams.opens.single().second)
        assertTrue(f.listener.connected.value)
        f.listener.stop()
        assertFalse(f.listener.connected.value)
    }

    @Test fun reconnectsAfterTheServerClosesTheStreamWaitingTheRetryTime() = runTest {
        val f = Fixture(this)
        f.streams.scripts += Streams.Script(200, listOf(greeting(1)))
        f.listener.start()
        runCurrent()
        assertEquals(1, f.streams.opens.size)
        advanceTimeBy(HintListener.RECONNECT_MILLIS - 1)
        assertEquals(1, f.streams.opens.size)
        advanceTimeBy(2)
        assertEquals(2, f.streams.opens.size)
        assertEquals(HintListener.RECONNECT_MILLIS, f.streams.opens[1].first - f.streams.opens[0].first)
        assertEquals(listOf(1L, 1L), f.hints, "each connection starts with a greeting of the current number")
    }

    @Test fun anExpiredTokenIsDroppedAndTheNextTryLogsInAgain() = runTest {
        val f = Fixture(this)
        f.streams.scripts += Streams.Script(401)
        f.streams.scripts += Streams.Script(200, listOf(greeting(2)), holdOpen = CompletableDeferred())
        f.listener.start()
        runCurrent()
        assertEquals(1, f.tokens.rejected)
        advanceTimeBy(Backoff.delayMillis(0, MIDDLE) + 1)
        assertEquals(listOf(2L), f.hints)
        assertTrue(f.listener.connected.value)
    }

    @Test fun aBusyOrFullServerIsRetriedWithGrowingBackoff() = runTest {
        val f = Fixture(this)
        f.streams.scripts += Streams.Script(429)
        f.listener.start()
        advanceTimeBy(60_000)
        val gaps = f.streams.opens.zipWithNext { a, b -> b.first - a.first }.take(4)
        assertEquals(listOf(0, 1, 2, 3).map { Backoff.delayMillis(it, MIDDLE) }, gaps)
        assertEquals(0, f.tokens.rejected, "429 is not an authentication problem")
        assertEquals(emptyList(), f.hints)
        assertFalse(f.listener.connected.value)
    }

    @Test fun textFromARefusedConnectionIsNotMistakenForHints() = runTest {
        val f = Fixture(this)
        f.streams.scripts += Streams.Script(503, listOf(greeting(99)))
        f.listener.start()
        runCurrent()
        assertEquals(emptyList(), f.hints)
    }

    @Test fun noTokenBecauseTheNetworkIsDownWaitsAndTries() = runTest {
        val f = Fixture(this)
        f.tokens.outcome = ApiOutcome.Offline
        f.listener.start()
        advanceTimeBy(5_000)
        assertEquals(emptyList(), f.streams.opens)
        f.tokens.outcome = null
        advanceTimeBy(60_000)
        assertTrue(f.streams.opens.isNotEmpty())
    }

    @Test fun aDeviceThatCannotLogInYetKeepsTryingSlowlyAndConnectsOnceItCan() = runTest {
        val f = Fixture(this)
        f.tokens.outcome = ApiOutcome.Unauthorized
        f.listener.start()
        advanceTimeBy(60_000)
        assertEquals(emptyList(), f.streams.opens)
        f.streams.scripts += Streams.Script(200, listOf(greeting(1)), holdOpen = CompletableDeferred())
        f.tokens.outcome = null
        advanceTimeBy(Backoff.MAX_MILLIS + 1)
        assertEquals(1, f.streams.opens.size)
        assertEquals(listOf(1L), f.hints)
    }

    @Test fun stoppingEndsTheConnectionAttempts() = runTest {
        val f = Fixture(this)
        f.streams.scripts += Streams.Script(200)
        f.listener.start()
        runCurrent()
        f.listener.stop()
        val opens = f.streams.opens.size
        advanceTimeBy(600_000)
        assertEquals(opens, f.streams.opens.size)
    }

    @Test fun startingTwiceDoesNotOpenTwoStreams() = runTest {
        val f = Fixture(this)
        f.streams.scripts += Streams.Script(200, holdOpen = CompletableDeferred())
        f.listener.start()
        f.listener.start()
        runCurrent()
        assertEquals(1, f.streams.opens.size)
    }
}
