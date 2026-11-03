package circles.server.support

import circles.crypto.JvmCrypto
import circles.server.buildServices
import circles.server.data.Database
import circles.server.data.Migrator
import circles.server.domain.MILLIS_PER_DAY
import circles.server.logging.JsonLog
import circles.server.logging.Log
import circles.server.logging.LogLevel
import circles.server.logging.NoLog
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import circles.server.web.Services
import java.sql.Connection
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/** 2026-11-02 as days since 1970-01-01. */
const val TODAY = 20_759

private const val HALF_DAY_MILLIS = 12L * 60 * 60 * 1000

/** A clock tests can move. */
class MutableClock(var millis: Long = TODAY * MILLIS_PER_DAY + HALF_DAY_MILLIS) : Clock() {
    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId?): Clock = this

    override fun instant(): Instant = Instant.ofEpochMilli(millis)

    fun advanceSeconds(seconds: Long) {
        millis += seconds * 1000
    }
}

/** A real server stack over a private in-memory SQLite database, with a movable clock. */
class TestServer(
    allowCreate: Boolean = true,
    rateLimit: Boolean = false,
    trustProxy: Boolean = false,
    log: Log = NoLog,
    blobQuotaBytes: Long = 1024L * 1024,
    streamKeepAliveMillis: Long = 25_000,
    /** Run over a real database file and blob folder in this directory instead of memory (backup and restore tests). */
    private val dataDir: java.nio.file.Path? = null,
) : AutoCloseable {
    val crypto = JvmCrypto()
    val clock = MutableClock()
    val connection: Connection = (if (dataDir == null) Database.openMemory() else Database.openFile(dataDir)).also { Migrator(it).migrate() }
    val blobsDir: java.nio.file.Path = dataDir?.resolve("blobs") ?: java.nio.file.Files.createTempDirectory("circles-blobs")
    val services: Services = buildServices(connection, crypto, clock, allowCreate, log, rateLimit, trustProxy, blobsDir, blobQuotaBytes, streamKeepAliveMillis)

    /** Runs a query against the database for assertions. */
    fun rows(sql: String): List<List<Any?>> =
        synchronized(connection) {
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rs ->
                    val width = rs.metaData.columnCount
                    buildList { while (rs.next()) add((1..width).map { rs.getObject(it) }) }
                }
            }
        }

    /** Changes the database behind the server's back, to set up states the M1 events cannot reach (a revoked device). */
    fun exec(sql: String) {
        synchronized(connection) {
            connection.createStatement().use { it.executeUpdate(sql) }
            connection.commit()
        }
    }

    override fun close() {
        connection.close()
        if (dataDir == null) blobsDir.toFile().deleteRecursively()
    }
}

/** The real [JsonLog] writing into memory, so tests see exactly what an operator would. */
class CapturedLog(clock: Clock = MutableClock(), level: LogLevel = LogLevel.DEBUG) : Log {
    private val bytes = ByteArrayOutputStream()
    private val inner = JsonLog(PrintStream(bytes, true, Charsets.UTF_8), level, clock)

    override fun log(level: LogLevel, event: String, fields: Map<String, Any?>) = inner.log(level, event, fields)

    fun text(): String = bytes.toString(Charsets.UTF_8)

    /** Every line parsed as a JSON object (a line that is not valid JSON fails the test). */
    fun entries(): List<JsonObject> = text().lines().filter { it.isNotBlank() }.map { Json.parseToJsonElement(it).jsonObject }

    fun named(event: String): List<JsonObject> = entries().filter { it.getValue("event").toString().trim('"') == event }
}
