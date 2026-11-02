package circles.server.config

import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ServerConfigTest {
    private fun valid(env: Map<String, String>): ServerConfig = assertIs<ConfigResult.Valid>(ServerConfigReader.fromEnv(env)).config

    private fun problems(env: Map<String, String>): List<String> = assertIs<ConfigResult.Invalid>(ServerConfigReader.fromEnv(env)).problems

    @Test
    fun defaultsMatchTheSpec() {
        assertEquals(ServerConfig(Paths.get("./data"), "0.0.0.0", 8080, allowCreate = true), valid(emptyMap()))
    }

    @Test
    fun everySettingCanBeOverridden() {
        val config = valid(mapOf(ENV_DATA_DIR to "/var/circles", ENV_BIND to "127.0.0.1", ENV_PORT to "9090", ENV_ALLOW_CREATE to "false"))
        assertEquals(ServerConfig(Paths.get("/var/circles"), "127.0.0.1", 9090, allowCreate = false), config)
    }

    @Test
    fun blankValuesFallBackToDefaults() {
        val config = valid(mapOf(ENV_DATA_DIR to " ", ENV_BIND to ""))
        assertEquals(Paths.get(DEFAULT_DATA_DIR), config.dataDir)
        assertEquals(DEFAULT_BIND, config.bind)
    }

    @Test
    fun portsOutsideTheRangeOrNotNumbersAreRefused() {
        listOf("0", "65536", "-1", "abc", "").forEach {
            assertTrue(problems(mapOf(ENV_PORT to it)).single().contains(ENV_PORT), "port '$it'")
        }
        assertEquals(1, valid(mapOf(ENV_PORT to "1")).port)
        assertEquals(65535, valid(mapOf(ENV_PORT to "65535")).port)
    }

    @Test
    fun allowCreateAcceptsOnlyTrueOrFalse() {
        listOf("yes", "1", "TRUE", "").forEach { assertTrue(problems(mapOf(ENV_ALLOW_CREATE to it)).single().contains(ENV_ALLOW_CREATE)) }
    }

    @Test
    fun allProblemsAreReportedTogether() {
        assertEquals(2, problems(mapOf(ENV_PORT to "x", ENV_ALLOW_CREATE to "maybe")).size)
    }

    @Test
    fun theNewSettingsHaveSafeDefaultsAndCanBeChanged() {
        val defaults = valid(emptyMap())
        assertEquals(circles.server.logging.LogLevel.INFO, defaults.logLevel)
        assertEquals(false, defaults.trustProxy)
        assertEquals(true, defaults.rateLimit)
        assertEquals(256L, defaults.blobQuotaMib)
        assertEquals(64L, valid(mapOf(ENV_BLOB_QUOTA_MIB to "64")).blobQuotaMib)
        val changed = valid(mapOf(ENV_LOG_LEVEL to "debug", ENV_TRUST_PROXY to "true", ENV_RATE_LIMIT to "false"))
        assertEquals(circles.server.logging.LogLevel.DEBUG, changed.logLevel)
        assertEquals(true, changed.trustProxy)
        assertEquals(false, changed.rateLimit)
    }

    @Test
    fun badValuesForTheNewSettingsAreReported() {
        assertTrue(problems(mapOf(ENV_LOG_LEVEL to "LOUD")).single().contains(ENV_LOG_LEVEL))
        assertTrue(problems(mapOf(ENV_TRUST_PROXY to "yes")).single().contains(ENV_TRUST_PROXY))
        assertTrue(problems(mapOf(ENV_RATE_LIMIT to "0")).single().contains(ENV_RATE_LIMIT))
        listOf("0", "-5", "lots", "1.5").forEach { assertTrue(problems(mapOf(ENV_BLOB_QUOTA_MIB to it)).single().contains(ENV_BLOB_QUOTA_MIB), it) }
        assertEquals(3, problems(mapOf(ENV_LOG_LEVEL to "x", ENV_TRUST_PROXY to "x", ENV_RATE_LIMIT to "x")).size)
    }
}
