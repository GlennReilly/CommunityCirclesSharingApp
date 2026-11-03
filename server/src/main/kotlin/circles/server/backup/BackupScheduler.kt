package circles.server.backup

import circles.server.logging.Log
import circles.server.logging.error
import circles.server.logging.info
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import kotlin.concurrent.thread

private const val SCHEDULED_PREFIX = "scheduled-"
private const val SCHEDULED_SUFFIX = ".zip"
private const val MILLIS_PER_HOUR = 3_600_000L

/** Writes a full backup every so often while the server runs and keeps the newest few (ADR 0031). */
class BackupScheduler(
    private val service: BackupService,
    private val directory: Path,
    private val keep: Int,
    private val clock: Clock,
    private val log: Log,
) {
    init {
        require(keep >= 1) { "keep at least one scheduled backup" }
    }

    /** One run: write `scheduled-<time>.zip`, then delete the oldest scheduled backups beyond [keep]. */
    fun runOnce(): BackupResult {
        Files.createDirectories(directory)
        val stamp = fileStamp(clock)
        var target = directory.resolve("$SCHEDULED_PREFIX$stamp$SCHEDULED_SUFFIX")
        var counter = 1
        while (Files.exists(target)) target = directory.resolve("$SCHEDULED_PREFIX$stamp-${counter++}$SCHEDULED_SUFFIX")
        val result = service.backup(target)
        if (result is BackupResult.Done) prune()
        return result
    }

    private fun prune() {
        val scheduled = Files.list(directory).use { stream ->
            stream.filter { it.fileName.toString().let { n -> n.startsWith(SCHEDULED_PREFIX) && n.endsWith(SCHEDULED_SUFFIX) } }.sorted().toList()
        }
        scheduled.dropLast(keep).forEach { Files.deleteIfExists(it) }
    }

    /** Starts a background thread that runs [runOnce] every [everyHours] hours until the process ends. */
    fun start(everyHours: Int): Thread =
        thread(name = "backup-scheduler", isDaemon = true) {
            while (true) {
                try {
                    Thread.sleep(everyHours * MILLIS_PER_HOUR)
                } catch (_: InterruptedException) {
                    return@thread
                }
                try {
                    when (val result = runOnce()) {
                        is BackupResult.Done -> log.info("backup_done", "blobs" to result.blobs, "schema" to result.schemaVersion)
                        is BackupResult.Failed -> log.error("backup_failed", "problems" to result.problems.size)
                    }
                } catch (e: Exception) {
                    log.error("backup_failed", "exception" to e::class.qualifiedName)
                }
            }
        }
}
