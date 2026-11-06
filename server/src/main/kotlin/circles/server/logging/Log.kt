package circles.server.logging

import java.io.PrintStream
import java.time.Clock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Severity, lowest first. The wire names are what `LENDGER_LOG_LEVEL` accepts (ADR 0016). */
enum class LogLevel(val wire: String) {
    DEBUG("debug"),
    INFO("info"),
    WARN("warn"),
    ERROR("error");

    companion object {
        fun fromWire(text: String): LogLevel? = entries.firstOrNull { it.wire == text }
    }
}

/**
 * The server's own log. Callers pass an event name and named fields, never a request or an exception, so a header,
 * body or message cannot end up in a log line by accident. Fields may be text, numbers, booleans, null, maps and lists.
 */
interface Log {
    fun log(level: LogLevel, event: String, fields: Map<String, Any?> = emptyMap())
}

fun Log.debug(event: String, vararg fields: Pair<String, Any?>) = log(LogLevel.DEBUG, event, fields.toMap())

fun Log.info(event: String, vararg fields: Pair<String, Any?>) = log(LogLevel.INFO, event, fields.toMap())

fun Log.warn(event: String, vararg fields: Pair<String, Any?>) = log(LogLevel.WARN, event, fields.toMap())

fun Log.error(event: String, vararg fields: Pair<String, Any?>) = log(LogLevel.ERROR, event, fields.toMap())

/** Drops everything. */
object NoLog : Log {
    override fun log(level: LogLevel, event: String, fields: Map<String, Any?>) = Unit
}

/** One JSON object per line: `time`, `level`, `event`, then the fields. Lines below [minLevel] are dropped. */
class JsonLog(private val out: PrintStream, private val minLevel: LogLevel, private val clock: Clock) : Log {
    override fun log(level: LogLevel, event: String, fields: Map<String, Any?>) {
        if (level < minLevel) return
        val line = JsonObject(
            linkedMapOf<String, JsonElement>(
                "time" to JsonPrimitive(clock.instant().toString()),
                "level" to JsonPrimitive(level.wire),
                "event" to JsonPrimitive(event),
            ).also { base -> fields.forEach { (name, value) -> if (name !in base) base[name] = toJson(value) } },
        )
        synchronized(out) { out.println(line.toString()) }
    }

    private fun toJson(value: Any?): JsonElement =
        when (value) {
            null -> JsonNull
            is String -> JsonPrimitive(value)
            is Boolean -> JsonPrimitive(value)
            is Number -> JsonPrimitive(value)
            is Map<*, *> -> JsonObject(value.entries.associate { (key, v) -> key.toString() to toJson(v) })
            is List<*> -> JsonArray(value.map { toJson(it) })
            else -> JsonPrimitive(UNSUPPORTED)
        }

    private companion object {
        /** Anything else is not written, so an object's `toString()` can never leak into a log. */
        const val UNSUPPORTED = "<unsupported>"
    }
}
