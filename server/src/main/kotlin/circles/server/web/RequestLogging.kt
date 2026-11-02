package circles.server.web

import circles.server.logging.Log
import circles.server.logging.LogLevel
import circles.server.logging.NoLog
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.util.AttributeKey

private const val NANOS_PER_MILLI = 1_000_000L
private val ID_PATTERN = Regex("[0-9a-f]{32}")
private val QUIET_PATHS = setOf("/healthz", "/readyz")

class RequestLogConfig {
    var log: Log = NoLog
}

/**
 * One log line per request: method, path with ids replaced by `{id}`, status and duration. It never reads headers,
 * query strings, bodies or the client address (ADR 0016). Probes are logged at debug level, unless they fail with a server error.
 */
val RequestLogging = createApplicationPlugin("RequestLogging", ::RequestLogConfig) {
    val log = pluginConfig.log
    val startKey = AttributeKey<Long>("RequestStartNanos")
    onCall { call -> call.attributes.put(startKey, System.nanoTime()) }
    on(ResponseSent) { call ->
        val path = call.request.path()
        val status = call.response.status()?.value ?: 0
        val level = when {
            status >= 500 -> LogLevel.ERROR
            path in QUIET_PATHS -> LogLevel.DEBUG
            status >= 400 -> LogLevel.WARN
            else -> LogLevel.INFO
        }
        val started = call.attributes.getOrNull(startKey) ?: System.nanoTime()
        log.log(
            level,
            "request",
            mapOf(
                "method" to call.request.httpMethod.value,
                "path" to path.replace(ID_PATTERN, "{id}"),
                "status" to status,
                "duration_ms" to (System.nanoTime() - started) / NANOS_PER_MILLI,
            ),
        )
    }
}
