package circles.sync

import circles.policy.CircleId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A piece of an open stream: first the status of the reply, then text. The flow ends when the connection does. */
sealed interface StreamPiece {
    data class Opened(val status: Int) : StreamPiece

    data class Text(val text: String) : StreamPiece
}

/** Streaming GET with a bearer token. The browser uses `fetch`; tests use an in-process fake or a socket. */
fun interface StreamTransport {
    /** A failure to connect or a dropped connection ends the flow quietly (no exception): the listener reconnects. */
    fun open(path: String, token: String): Flow<StreamPiece>
}

/** Where the listener gets the bearer token; the HTTP API owns the login. */
interface TokenSource {
    /** A valid token, logging in if needed. */
    suspend fun token(): ApiOutcome<String>

    /** The server said the token is no good: forget it so the next [token] logs in again. */
    fun rejectToken()
}

/**
 * Keeps a hint stream open for one circle (ADR 0026) and calls [onHint] with each newest sequence number. It reconnects
 * after the connection ends (the server closes it when the session expires) and backs off when the server is busy or
 * unreachable. The stream is only an accelerator: if it never connects, the scheduler's polling still works.
 */
class HintListener(
    private val scope: CoroutineScope,
    private val streams: StreamTransport,
    private val tokens: TokenSource,
    private val circle: CircleId,
    private val random: () -> Double = { kotlin.random.Random.nextDouble() },
    private val onHint: suspend (Long) -> Unit,
) {
    private val mutableConnected = MutableStateFlow(false)

    /** True while a stream is open and the server has accepted it. */
    val connected: StateFlow<Boolean> = mutableConnected.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { loop() }
    }

    fun stop() {
        job?.cancel()
        job = null
        mutableConnected.value = false
    }

    private suspend fun loop() {
        var failures = 0
        while (true) {
            val token = when (val outcome = tokens.token()) {
                is ApiOutcome.Ok -> outcome.value
                else -> {
                    // Not logged in yet is normal for a new device (its first events are still in the outbox), so this
                    // is never final: try again, slowly.
                    delay(Backoff.delayMillis(failures++, random()))
                    continue
                }
            }
            var accepted = false
            var status = 0
            val parser = SseParser()
            streams.open("$STREAM_PATH_PREFIX${circle.v}$STREAM_PATH_SUFFIX", token).collect { piece ->
                when (piece) {
                    is StreamPiece.Opened -> {
                        status = piece.status
                        accepted = piece.status == HTTP_OK
                        mutableConnected.value = accepted
                    }
                    is StreamPiece.Text -> if (accepted) parser.feed(piece.text).forEach { onHint(it) }
                }
            }
            mutableConnected.value = false
            when {
                accepted -> {
                    failures = 0
                    delay(RECONNECT_MILLIS)
                }
                status == HTTP_UNAUTHORIZED -> {
                    tokens.rejectToken()
                    delay(Backoff.delayMillis(failures++, random()))
                }
                else -> delay(Backoff.delayMillis(failures++, random()))
            }
        }
    }

    companion object {
        /** The server's `retry: 5000` (ADR 0026). */
        const val RECONNECT_MILLIS = 5_000L
        private const val STREAM_PATH_PREFIX = "/api/v1/circles/"
        private const val STREAM_PATH_SUFFIX = "/events/stream"
    }
}
