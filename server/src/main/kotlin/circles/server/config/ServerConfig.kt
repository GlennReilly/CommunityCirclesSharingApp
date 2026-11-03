package circles.server.config

import circles.server.domain.DEFAULT_BLOB_QUOTA_MIB
import circles.server.logging.LogLevel
import java.nio.file.Path
import java.nio.file.Paths

const val DEFAULT_DATA_DIR = "./data"
const val DEFAULT_BIND = "0.0.0.0"
const val DEFAULT_PORT = 8080
const val MIN_PORT = 1
const val MAX_PORT = 65_535

const val ENV_DATA_DIR = "CIRCLES_DATA_DIR"
const val ENV_BIND = "CIRCLES_BIND"
const val ENV_PORT = "CIRCLES_PORT"
const val ENV_ALLOW_CREATE = "CIRCLES_ALLOW_CREATE"
const val ENV_LOG_LEVEL = "CIRCLES_LOG_LEVEL"
const val ENV_TRUST_PROXY = "CIRCLES_TRUST_PROXY"
const val ENV_RATE_LIMIT = "CIRCLES_RATE_LIMIT"
const val ENV_BLOB_QUOTA_MIB = "CIRCLES_BLOB_QUOTA_MIB"
const val ENV_BACKUP_INTERVAL_HOURS = "CIRCLES_BACKUP_INTERVAL_HOURS"
const val ENV_BACKUP_KEEP = "CIRCLES_BACKUP_KEEP"
const val ENV_BACKUP_DIR = "CIRCLES_BACKUP_DIR"
const val DEFAULT_BACKUP_KEEP = 7

/** Settings from the `CIRCLES_*` environment variables (spec 7). */
data class ServerConfig(
    val dataDir: Path,
    val bind: String,
    val port: Int,
    val allowCreate: Boolean,
    val logLevel: LogLevel = LogLevel.INFO,
    val trustProxy: Boolean = false,
    val rateLimit: Boolean = true,
    /** Blob storage allowed per circle, in MiB (ADR 0026). */
    val blobQuotaMib: Long = DEFAULT_BLOB_QUOTA_MIB,
    /** Hours between scheduled backups while serving; 0 means no scheduled backups (ADR 0031). */
    val backupIntervalHours: Int = 0,
    /** How many scheduled backups to keep. */
    val backupKeep: Int = DEFAULT_BACKUP_KEEP,
    /** Where scheduled and pre-migration backups go; required for both, no default (ADR 0032). */
    val backupDir: Path? = null,
)

sealed interface ConfigResult {
    data class Valid(val config: ServerConfig) : ConfigResult

    data class Invalid(val problems: List<String>) : ConfigResult
}

object ServerConfigReader {
    fun fromEnv(env: Map<String, String>): ConfigResult {
        val problems = mutableListOf<String>()
        val port = env[ENV_PORT]?.let { text ->
            text.toIntOrNull()?.takeIf { it in MIN_PORT..MAX_PORT } ?: run {
                problems += "$ENV_PORT must be a number from $MIN_PORT to $MAX_PORT, not '$text'"
                null
            }
        } ?: DEFAULT_PORT
        val allowCreate = flag(env, ENV_ALLOW_CREATE, default = true, problems)
        val trustProxy = flag(env, ENV_TRUST_PROXY, default = false, problems)
        val rateLimit = flag(env, ENV_RATE_LIMIT, default = true, problems)
        val backupIntervalHours = positiveInt(env, ENV_BACKUP_INTERVAL_HOURS, problems) ?: 0
        val backupKeep = positiveInt(env, ENV_BACKUP_KEEP, problems) ?: DEFAULT_BACKUP_KEEP
        val backupDir = env[ENV_BACKUP_DIR]?.takeIf { it.isNotBlank() }?.let { Paths.get(it) }
        if (backupIntervalHours > 0 && backupDir == null) {
            problems += "$ENV_BACKUP_INTERVAL_HOURS needs $ENV_BACKUP_DIR: name a folder for backups, ideally on another disk"
        }
        val blobQuotaMib = env[ENV_BLOB_QUOTA_MIB]?.let { text ->
            text.toLongOrNull()?.takeIf { it >= 1 } ?: run {
                problems += "$ENV_BLOB_QUOTA_MIB must be a whole number of MiB, at least 1, not '$text'"
                null
            }
        } ?: DEFAULT_BLOB_QUOTA_MIB
        val logLevel = env[ENV_LOG_LEVEL]?.let { text ->
            LogLevel.fromWire(text) ?: run {
                problems += "$ENV_LOG_LEVEL must be one of ${LogLevel.entries.joinToString { it.wire }}, not '$text'"
                null
            }
        } ?: LogLevel.INFO
        val bind = env[ENV_BIND]?.takeIf { it.isNotBlank() } ?: DEFAULT_BIND
        val dataDir = env[ENV_DATA_DIR]?.takeIf { it.isNotBlank() } ?: DEFAULT_DATA_DIR
        return if (problems.isEmpty()) ConfigResult.Valid(ServerConfig(Paths.get(dataDir), bind, port, allowCreate, logLevel, trustProxy, rateLimit, blobQuotaMib, backupIntervalHours, backupKeep, backupDir)) else ConfigResult.Invalid(problems)
    }

    private fun positiveInt(env: Map<String, String>, name: String, problems: MutableList<String>): Int? =
        env[name]?.let { text ->
            text.toIntOrNull()?.takeIf { it >= 1 } ?: run {
                problems += "$name must be a whole number, at least 1, not '$text'"
                null
            }
        }

    private fun flag(env: Map<String, String>, name: String, default: Boolean, problems: MutableList<String>): Boolean =
        env[name]?.let { text ->
            when (text) {
                "true" -> true
                "false" -> false
                else -> {
                    problems += "$name must be 'true' or 'false', not '$text'"
                    default
                }
            }
        } ?: default
}
