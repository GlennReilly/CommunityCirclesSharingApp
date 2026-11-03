package circles.server

import circles.crypto.Crypto
import circles.crypto.JvmCrypto
import circles.api.ApiJson
import circles.event.isValidRandomId
import circles.export.ExportSource
import circles.server.backup.BACKUP_DIR_NAME
import circles.server.backup.BACKUP_FORMAT
import circles.server.backup.BackupResult
import circles.server.backup.BackupScheduler
import circles.server.backup.BackupService
import circles.server.backup.BackupVerifier
import circles.server.backup.LATEST_SCHEMA
import circles.server.backup.RestoreResult
import circles.server.backup.RestoreService
import circles.server.backup.fileStamp
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import circles.export.ExportVerifier
import circles.policy.CircleId
import circles.server.backup.CircleExporter
import circles.server.backup.CircleImporter
import circles.server.backup.ExportResult
import circles.server.backup.ImportResult
import circles.server.backup.ZipExportSource
import circles.server.domain.BYTES_PER_MIB
import circles.server.logging.NoLog
import java.nio.file.Files
import java.nio.file.Paths
import circles.server.config.ConfigResult
import circles.server.config.ServerConfig
import circles.server.config.ServerConfigReader
import circles.server.data.Database
import circles.server.data.Migrator
import circles.server.data.SchemaState
import circles.server.logging.JsonLog
import circles.server.logging.info
import circles.server.web.SERVER_VERSION
import circles.server.web.Services
import circles.server.web.module
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.io.PrintStream
import java.time.Clock

const val EXIT_OK = 0
const val EXIT_FAILURE = 1
const val EXIT_USAGE = 2
private const val MAX_FORMAT_PROBE_BYTES = 64L * 1024 * 1024

private const val USAGE = """Usage: circles-server <command>

Commands:
  serve     Start the server (migrates a brand-new database; refuses an old or newer one)
  migrate   Create or update the database schema
  export <circleId> <file>   Write a circle's log and blobs to a circles-export/1 zip file (ADR 0030)
  import <file>              Seed this server from an export; re-checks every event, safe to repeat
  verify <file>              Check an export or a backup without touching any database
  backup <dir>               Write circles-backup-<time>.zip (database and blobs) into <dir>
  restore <file> [--force]   Put a backup back (stop the server first); --force moves existing data aside
  help      Show this text

Settings come from the environment: CIRCLES_DATA_DIR, CIRCLES_BIND, CIRCLES_PORT, CIRCLES_ALLOW_CREATE,
CIRCLES_LOG_LEVEL, CIRCLES_TRUST_PROXY, CIRCLES_RATE_LIMIT, CIRCLES_BLOB_QUOTA_MIB,
CIRCLES_BACKUP_INTERVAL_HOURS, CIRCLES_BACKUP_KEEP (see the README)."""

/** Starts the HTTP server and blocks until it stops. Replaceable so tests need not open a port. */
fun interface ServerLauncher {
    fun launch(config: ServerConfig, services: Services)
}

private val NettyLauncher = ServerLauncher { config, services ->
    embeddedServer(Netty, port = config.port, host = config.bind) { module(services) }.start(wait = true)
}

/** The command line: `serve` and `migrate` (spec 4.5; the other commands come in M5). */
class Cli(
    private val env: Map<String, String>,
    private val out: PrintStream,
    private val err: PrintStream,
    private val launcher: ServerLauncher = NettyLauncher,
    private val crypto: Crypto = JvmCrypto(),
    private val clock: Clock = Clock.systemUTC(),
) {
    fun run(args: List<String>): Int =
        when (args.firstOrNull()) {
            "help", "--help", "-h" -> out.println(USAGE).let { EXIT_OK }
            "serve" -> withCommand(args) { serve(it) }
            "migrate" -> withCommand(args) { migrate(it) }
            "verify" -> verify(args)
            "backup" -> backup(args)
            "restore" -> restore(args)
            "export" -> exportCircle(args)
            "import" -> importCircle(args)
            null -> usageError("missing command")
            else -> usageError("unknown command '${args.first()}'")
        }

    private fun withCommand(args: List<String>, action: (ServerConfig) -> Int): Int {
        if (args.size > 1) return usageError("'${args.first()}' takes no arguments")
        return when (val result = ServerConfigReader.fromEnv(env)) {
            is ConfigResult.Invalid -> {
                result.problems.forEach { err.println("error: $it") }
                EXIT_USAGE
            }
            is ConfigResult.Valid -> action(result.config)
        }
    }

    private fun verify(args: List<String>): Int {
        if (args.size != 2) return usageError("'verify' takes one file: verify <file>")
        val path = Paths.get(args[1])
        if (!Files.isRegularFile(path)) return fail("no such file: ${args[1]}")
        return openZip(path) { source ->
            if (formatOf(source) == BACKUP_FORMAT) verifyBackup(source) else verifyExport(source)
        }
    }

    /** The `format` field of the manifest, or null if it cannot be read; decides which verifier looks at the file. */
    private fun formatOf(source: ExportSource): String? =
        source.read("manifest.json", MAX_FORMAT_PROBE_BYTES)?.let {
            try {
                ApiJson.parseToJsonElement(it.decodeToString()).jsonObject["format"]?.jsonPrimitive?.content
            } catch (_: Exception) {
                null
            }
        }

    private fun verifyExport(source: ExportSource): Int {
        val report = ExportVerifier.verify(source, crypto)
        return if (report.ok) {
            out.println("OK: ${report.manifest?.eventCount} events and ${report.manifest?.blobs?.size} blobs for circle ${report.manifest?.circle}.")
            EXIT_OK
        } else {
            report.problems.forEach { err.println("problem: $it") }
            err.println("${report.problems.size} problem(s) found; this file cannot be imported.")
            EXIT_FAILURE
        }
    }

    private fun verifyBackup(source: ExportSource): Int {
        val work = Files.createTempDirectory("circles-verify-")
        try {
            val result = BackupVerifier.verify(source, crypto, work)
            return if (result.ok) {
                val m = result.manifest!!
                out.println("OK: backup of schema ${m.schemaVersion} with ${m.blobs.size} blobs${if (m.includesBlobs) "" else " (database only)"}.")
                EXIT_OK
            } else {
                result.problems.forEach { err.println("problem: $it") }
                err.println("${result.problems.size} problem(s) found; this file cannot be restored.")
                EXIT_FAILURE
            }
        } finally {
            work.toFile().deleteRecursively()
        }
    }

    private fun backup(args: List<String>): Int {
        if (args.size != 2) return usageError("'backup' takes one folder: backup <dir>")
        val dir = Paths.get(args[1])
        return withConfig { config ->
            val target = dir.resolve("circles-backup-${fileStamp(clock)}.zip")
            when (val result = BackupService(config.dataDir, crypto).backup(target)) {
                is BackupResult.Done -> out.println("Backed up the database (schema ${result.schemaVersion}) and ${result.blobs} blobs to $target.").let { EXIT_OK }
                is BackupResult.Failed -> result.problems.forEach { err.println("problem: $it") }.let { EXIT_FAILURE }
            }
        }
    }

    private fun restore(args: List<String>): Int {
        val force = args.contains("--force")
        val rest = args.drop(1).filter { it != "--force" }
        if (rest.size != 1) return usageError("'restore' takes one file: restore <file> [--force]")
        val path = Paths.get(rest.single())
        if (!Files.isRegularFile(path)) return fail("no such file: ${rest.single()}")
        return withConfig { config ->
            openZip(path) { source ->
                when (val result = RestoreService(config.dataDir, crypto, clock).restore(source, force)) {
                    is RestoreResult.Done -> {
                        out.println("Restored the database (schema ${result.schemaVersion}) and ${result.blobs} blobs into ${config.dataDir}.")
                        result.movedAside.forEach { out.println("Moved aside, not deleted: $it") }
                        if (result.schemaVersion < LATEST_SCHEMA) out.println("The database is older than this server: run 'migrate' next.")
                        EXIT_OK
                    }
                    is RestoreResult.Refused -> {
                        result.problems.forEach { err.println("problem: $it") }
                        err.println("Nothing was restored.")
                        EXIT_FAILURE
                    }
                }
            }
        }
    }

    private fun withConfig(action: (ServerConfig) -> Int): Int =
        when (val result = ServerConfigReader.fromEnv(env)) {
            is ConfigResult.Invalid -> result.problems.forEach { err.println("error: $it") }.let { EXIT_USAGE }
            is ConfigResult.Valid -> action(result.config)
        }

    private fun exportCircle(args: List<String>): Int {
        if (args.size != 3) return usageError("'export' takes a circle id and a file: export <circleId> <file>")
        if (!isValidRandomId(args[1])) return usageError("'${args[1]}' is not a circle id (32 lowercase hex characters)")
        val target = Paths.get(args[2])
        if (Files.exists(target)) return fail("refusing to overwrite ${args[2]}")
        return withServices { services ->
            val exporter = CircleExporter(services.store, services.blobs!!, crypto)
            when (val result = exporter.export(CircleId(args[1]), target)) {
                is ExportResult.Done -> out.println("Exported ${result.events} events and ${result.blobs} blobs to ${args[2]}.").let { EXIT_OK }
                ExportResult.UnknownCircle -> fail("this server has no circle ${args[1]}")
                is ExportResult.Failed -> result.problems.forEach { err.println("problem: $it") }.let { EXIT_FAILURE }
            }
        }
    }

    private fun importCircle(args: List<String>): Int {
        if (args.size != 2) return usageError("'import' takes one file: import <file>")
        val path = Paths.get(args[1])
        if (!Files.isRegularFile(path)) return fail("no such file: ${args[1]}")
        return withServices { services ->
            openZip(path) { source ->
                when (val result = CircleImporter(services.sync, services.blobs!!, crypto).import(source)) {
                    is ImportResult.Done -> {
                        val r = result.report
                        out.println("Imported ${r.eventsAccepted} new events (${r.eventsAlready} already there) and ${r.blobsStored} new blobs (${r.blobsAlready} already there).")
                        EXIT_OK
                    }
                    is ImportResult.Refused -> {
                        result.problems.forEach { err.println("problem: $it") }
                        err.println("The file did not verify; nothing was imported.")
                        EXIT_FAILURE
                    }
                    is ImportResult.Stopped -> {
                        result.problems.forEach { err.println("problem: $it") }
                        val r = result.report
                        err.println("Stopped after ${r.eventsAccepted} new events; what was accepted is kept and the same file can resume it.")
                        EXIT_FAILURE
                    }
                }
            }
        }
    }

    /** Opens the database with every migration applied, builds the services over it and runs [action]. */
    private fun withServices(action: (Services) -> Int): Int =
        when (val result = ServerConfigReader.fromEnv(env)) {
            is ConfigResult.Invalid -> result.problems.forEach { err.println("error: $it") }.let { EXIT_USAGE }
            is ConfigResult.Valid -> Database.openFile(result.config.dataDir).use { connection ->
                when (val state = Migrator(connection).state()) {
                    SchemaState.Current -> action(
                        buildServices(connection, crypto, clock, result.config.allowCreate, NoLog, rateLimit = false, trustProxy = false,
                            blobsDir = result.config.dataDir.resolve("blobs"), blobQuotaBytes = result.config.blobQuotaMib * BYTES_PER_MIB),
                    )
                    is SchemaState.Ahead -> failAhead(state)
                    else -> fail("the database is not at the current version; run 'migrate' first")
                }
            }
        }

    private fun openZip(path: java.nio.file.Path, action: (ZipExportSource) -> Int): Int =
        try {
            ZipExportSource(path).use(action)
        } catch (_: java.util.zip.ZipException) {
            fail("${path.fileName} is not a valid zip file")
        } catch (_: java.io.IOException) {
            fail("could not read ${path.fileName}")
        }

    private fun fail(message: String): Int {
        err.println("error: $message")
        return EXIT_FAILURE
    }

    private fun migrate(config: ServerConfig): Int =
        Database.openFile(config.dataDir).use { connection ->
            val migrator = Migrator(connection)
            when (val state = migrator.state()) {
                is SchemaState.Ahead -> failAhead(state)
                else -> {
                    if (state is SchemaState.Behind && !backupBeforeMigrating(config, state.current)) return@use EXIT_FAILURE
                    val applied = migrator.migrate()
                    out.println(if (applied.isEmpty()) "Database is up to date at version ${migrator.currentVersion()}." else "Applied migrations ${applied.joinToString()}; database is at version ${migrator.currentVersion()}.")
                    EXIT_OK
                }
            }
        }

    /** ADR 0031: a database-only backup before any migration of existing data; no backup means no migration. */
    private fun backupBeforeMigrating(config: ServerConfig, fromVersion: Int): Boolean {
        val target = config.dataDir.resolve(BACKUP_DIR_NAME).resolve("pre-migrate-from-v$fromVersion-${fileStamp(clock)}.zip")
        return when (val result = BackupService(config.dataDir, crypto).backup(target, includeBlobs = false)) {
            is BackupResult.Done -> out.println("Backed up the database to $target before migrating.").let { true }
            is BackupResult.Failed -> {
                result.problems.forEach { err.println("problem: $it") }
                err.println("error: the backup before migrating failed, so the database was not migrated.")
                false
            }
        }
    }

    private fun serve(config: ServerConfig): Int {
        val log = JsonLog(out, config.logLevel, clock)
        val connection = Database.openFile(config.dataDir)
        val migrator = Migrator(connection)
        val ready = when (val state = migrator.state()) {
            SchemaState.Current -> true
            SchemaState.Empty -> migrator.migrate().let { log.info("database_migrated", "applied" to it); true }
            is SchemaState.Behind -> {
                err.println("error: the database is at version ${state.current} but this server needs ${state.latest}. Run 'migrate' first.")
                false
            }
            is SchemaState.Ahead -> failAhead(state).let { false }
        }
        if (!ready) {
            connection.close()
            return EXIT_FAILURE
        }
        log.info(
            "server_starting",
            "bind" to config.bind,
            "port" to config.port,
            "creation_open" to config.allowCreate,
            "rate_limit" to config.rateLimit,
            "trust_proxy" to config.trustProxy,
            "blob_quota_mib" to config.blobQuotaMib,
            "backup_every_hours" to config.backupIntervalHours,
            "version" to SERVER_VERSION,
        )
        if (config.backupIntervalHours > 0) {
            BackupScheduler(BackupService(config.dataDir, crypto), config.dataDir.resolve(BACKUP_DIR_NAME), config.backupKeep, clock, log).start(config.backupIntervalHours)
        }
        connection.use { launcher.launch(config, buildServices(it, crypto, clock, config.allowCreate, log, config.rateLimit, config.trustProxy, config.dataDir.resolve("blobs"), config.blobQuotaMib * BYTES_PER_MIB)) }
        return EXIT_OK
    }

    private fun failAhead(state: SchemaState.Ahead): Int {
        err.println("error: the database is at version ${state.current}, newer than this server (${state.latest}). Use a newer server.")
        return EXIT_FAILURE
    }

    private fun usageError(message: String): Int {
        err.println("error: $message")
        err.println(USAGE)
        return EXIT_USAGE
    }
}
