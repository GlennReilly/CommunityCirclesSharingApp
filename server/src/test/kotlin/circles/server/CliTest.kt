package circles.server

import circles.server.config.ENV_ALLOW_CREATE
import circles.server.config.ENV_DATA_DIR
import circles.server.config.ENV_PORT
import circles.server.config.ServerConfig
import circles.server.data.Database
import circles.server.data.Migrator
import circles.server.web.Services
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CliTest {
    private class Run(val code: Int, val out: String, val err: String, val launched: ServerConfig?, val services: Services?)

    private fun tempDir(): Path = Files.createTempDirectory("circles-cli")

    private fun run(vararg args: String, env: Map<String, String> = mapOf(ENV_DATA_DIR to tempDir().toString())): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        var launched: ServerConfig? = null
        var services: Services? = null
        val cli = Cli(env, PrintStream(out), PrintStream(err), launcher = { config, s -> launched = config; services = s })
        val code = cli.run(args.toList())
        return Run(code, out.toString(), err.toString(), launched, services)
    }

    private fun setVersion(dir: Path, version: Int) {
        Database.openFile(dir).use { c ->
            c.createStatement().use { it.executeUpdate("DELETE FROM schema_version") }
            c.createStatement().use { it.executeUpdate("INSERT INTO schema_version (version) VALUES ($version)") }
        }
    }

    @Test
    fun helpShowsUsageAndSucceeds() {
        listOf("help", "--help", "-h").forEach {
            val r = run(it)
            assertEquals(EXIT_OK, r.code)
            assertTrue(r.out.contains("serve") && r.out.contains("migrate"))
        }
    }

    @Test
    fun missingOrUnknownCommandsAreUsageErrors() {
        val missing = run()
        assertEquals(EXIT_USAGE, missing.code)
        assertTrue(missing.err.contains("missing command"))
        val unknown = run("frobnicate")
        assertEquals(EXIT_USAGE, unknown.code)
        assertTrue(unknown.err.contains("frobnicate"))
    }

    @Test
    fun commandsRefuseExtraArguments() {
        assertEquals(EXIT_USAGE, run("migrate", "now").code)
        assertEquals(EXIT_USAGE, run("serve", "--fast").code)
    }

    @Test
    fun badSettingsAreAUsageErrorAndNothingStarts() {
        val r = run("serve", env = mapOf(ENV_PORT to "0", ENV_ALLOW_CREATE to "maybe"))
        assertEquals(EXIT_USAGE, r.code)
        assertTrue(r.err.contains(ENV_PORT) && r.err.contains(ENV_ALLOW_CREATE))
        assertNull(r.launched)
    }

    @Test
    fun migrateCreatesTheDatabaseThenReportsUpToDate() {
        val dir = tempDir().resolve("new/data")
        val env = mapOf(ENV_DATA_DIR to dir.toString())
        val first = run("migrate", env = env)
        assertEquals(EXIT_OK, first.code)
        assertTrue(first.out.contains("Applied migrations 1, 2, 3, 4"), first.out)
        val second = run("migrate", env = env)
        assertEquals(EXIT_OK, second.code)
        assertTrue(second.out.contains("up to date"), second.out)
        Database.openFile(dir).use { assertEquals(4, Migrator(it).currentVersion()) }
    }

    @Test
    fun serveMigratesABrandNewDatabaseAndLaunchesWithTheConfig() {
        val dir = tempDir()
        val r = run("serve", env = mapOf(ENV_DATA_DIR to dir.toString(), ENV_PORT to "9191"))
        assertEquals(EXIT_OK, r.code, r.err)
        assertEquals(9191, assertNotNull(r.launched).port)
        assertNotNull(r.services)
        val started = Json.parseToJsonElement(r.out.lines().last { "server_starting" in it }).jsonObject
        assertEquals(9191, started.getValue("port").jsonPrimitive.int)
        assertEquals(true, started.getValue("creation_open").jsonPrimitive.boolean)
        assertEquals(true, started.getValue("rate_limit").jsonPrimitive.boolean)
        assertEquals(false, started.getValue("trust_proxy").jsonPrimitive.boolean)
        assertTrue(r.out.contains("database_migrated"))
        Database.openFile(dir).use { assertEquals(4, Migrator(it).currentVersion()) }
    }

    @Test
    fun serveReportsWhenCreationIsClosed() {
        val r = run("serve", env = mapOf(ENV_DATA_DIR to tempDir().toString(), ENV_ALLOW_CREATE to "false"))
        assertEquals(false, r.launched?.allowCreate)
        assertTrue(r.out.contains("\"creation_open\":false"), r.out)
    }

    @Test
    fun serveAndMigrateRefuseADatabaseFromANewerServer() {
        val dir = tempDir()
        assertEquals(EXIT_OK, run("migrate", env = mapOf(ENV_DATA_DIR to dir.toString())).code)
        setVersion(dir, 99)
        val env = mapOf(ENV_DATA_DIR to dir.toString())
        val serve = run("serve", env = env)
        assertEquals(EXIT_FAILURE, serve.code)
        assertTrue(serve.err.contains("newer"))
        assertNull(serve.launched)
        val migrate = run("migrate", env = env)
        assertEquals(EXIT_FAILURE, migrate.code)
        Database.openFile(dir).use { assertEquals(99, Migrator(it).currentVersion()) }
    }

    @Test
    fun serveHonoursTheLogLevelAndPassesTheNewSettingsOn() {
        val quiet = run("serve", env = mapOf(ENV_DATA_DIR to tempDir().toString(), "CIRCLES_LOG_LEVEL" to "warn", "CIRCLES_RATE_LIMIT" to "false", "CIRCLES_TRUST_PROXY" to "true"))
        assertEquals(EXIT_OK, quiet.code, quiet.err)
        assertEquals("", quiet.out.trim(), "info lines must be dropped at level warn")
        assertEquals(false, quiet.launched?.rateLimit)
        assertEquals(true, quiet.launched?.trustProxy)
    }

    @Test
    fun anUnknownLogLevelIsAUsageError() {
        val r = run("serve", env = mapOf(ENV_DATA_DIR to tempDir().toString(), "CIRCLES_LOG_LEVEL" to "loud"))
        assertEquals(EXIT_USAGE, r.code)
        assertTrue(r.err.contains("CIRCLES_LOG_LEVEL"))
        assertNull(r.launched)
    }
}
