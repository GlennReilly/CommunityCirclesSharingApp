package circles.server.logging

import circles.server.support.MutableClock
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class JsonLogTest {
    private val bytes = ByteArrayOutputStream()
    private val clock = MutableClock()

    private fun log(level: LogLevel = LogLevel.INFO) = JsonLog(PrintStream(bytes, true, Charsets.UTF_8), level, clock)

    private fun lines(): List<JsonObject> = bytes.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }

    @Test
    fun writesOneJsonObjectPerLineWithTimeLevelAndEvent() {
        val log = log()
        log.info("started", "port" to 8080)
        log.warn("slow", "ms" to 12)
        val out = lines()
        assertEquals(2, out.size)
        assertEquals("2026-11-02T12:00:00Z", out[0].getValue("time").jsonPrimitive.content)
        assertEquals("info", out[0].getValue("level").jsonPrimitive.content)
        assertEquals("started", out[0].getValue("event").jsonPrimitive.content)
        assertEquals(8080, out[0].getValue("port").jsonPrimitive.int)
        assertEquals("warn", out[1].getValue("level").jsonPrimitive.content)
        assertEquals(2, bytes.toString(Charsets.UTF_8).trimEnd().lines().size, "no line may contain a newline")
    }

    @Test
    fun dropsLinesBelowTheMinimumLevel() {
        val log = log(LogLevel.WARN)
        log.debug("a")
        log.info("b")
        log.warn("c")
        log.error("d")
        assertEquals(listOf("c", "d"), lines().map { it.getValue("event").jsonPrimitive.content })
    }

    @Test
    fun writesTextNumbersBooleansNullsMapsAndLists() {
        log().info("shapes", "s" to "x", "n" to 1.5, "b" to true, "z" to null, "m" to mapOf("k" to 2), "l" to listOf("a", 3))
        val line = lines().single()
        assertEquals("x", line.getValue("s").jsonPrimitive.content)
        assertEquals(true, line.getValue("b").jsonPrimitive.boolean)
        assertEquals(JsonNull, line.getValue("z"))
        assertEquals(2, line.getValue("m").jsonObject.getValue("k").jsonPrimitive.int)
        assertEquals(2, (line.getValue("l") as JsonArray).jsonArray.size)
    }

    @Test
    fun escapesQuotesAndNewlinesSoALineStaysOneLine() {
        log().info("tricky", "text" to "a\"b\nc\u0007")
        assertEquals("a\"b\nc\u0007", lines().single().getValue("text").jsonPrimitive.content)
    }

    @Test
    fun aFieldCannotOverwriteTimeLevelOrEvent() {
        log().info("real", "level" to "fake", "event" to "fake", "time" to "fake")
        val line = lines().single()
        assertEquals("info", line.getValue("level").jsonPrimitive.content)
        assertEquals("real", line.getValue("event").jsonPrimitive.content)
        assertEquals("2026-11-02T12:00:00Z", line.getValue("time").jsonPrimitive.content)
    }

    @Test
    fun otherObjectsAreNeverWrittenSoTheirToStringCannotLeak() {
        class Secret { override fun toString() = "hunter2" }
        log().info("oops", "thing" to Secret())
        assertFalse(bytes.toString(Charsets.UTF_8).contains("hunter2"))
        assertEquals("<unsupported>", lines().single().getValue("thing").jsonPrimitive.content)
    }

    @Test
    fun logLevelNamesRoundTripAndUnknownNamesAreNull() {
        LogLevel.entries.forEach { assertEquals(it, LogLevel.fromWire(it.wire)) }
        assertNull(LogLevel.fromWire("INFO"))
        assertTrue(LogLevel.DEBUG < LogLevel.ERROR)
    }

    @Test
    fun noLogDropsEverything() {
        NoLog.info("x", "a" to 1)
    }
}
