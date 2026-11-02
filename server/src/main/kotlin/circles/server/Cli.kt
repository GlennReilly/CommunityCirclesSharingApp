package circles.server

import circles.crypto.Crypto
import circles.crypto.JvmCrypto
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

private const val USAGE = """Usage: circles-server <command>

Commands:
  serve     Start the server (migrates a brand-new database; refuses an old or newer one)
  migrate   Create or update the database schema
  help      Show this text

Settings come from the environment: CIRCLES_DATA_DIR, CIRCLES_BIND, CIRCLES_PORT, CIRCLES_ALLOW_CREATE,
CIRCLES_LOG_LEVEL, CIRCLES_TRUST_PROXY, CIRCLES_RATE_LIMIT (see the README)."""

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

    private fun migrate(config: ServerConfig): Int =
        Database.openFile(config.dataDir).use { connection ->
            val migrator = Migrator(connection)
            when (val state = migrator.state()) {
                is SchemaState.Ahead -> failAhead(state)
                else -> {
                    val applied = migrator.migrate()
                    out.println(if (applied.isEmpty()) "Database is up to date at version ${migrator.currentVersion()}." else "Applied migrations ${applied.joinToString()}; database is at version ${migrator.currentVersion()}.")
                    EXIT_OK
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
            "plaintext_dev_format" to config.allowPlaintextDev,
            "version" to SERVER_VERSION,
        )
        connection.use { launcher.launch(config, buildServices(it, crypto, clock, config.allowCreate, log, config.rateLimit, config.trustProxy, config.allowPlaintextDev)) }
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
