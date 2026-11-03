package circles.server.backup

import circles.crypto.Crypto
import circles.export.ExportSource
import circles.policy.CircleId
import circles.server.data.Database
import circles.server.data.FileBlobFiles
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

sealed interface RestoreResult {
    /** [movedAside] lists what `--force` renamed instead of deleting. */
    data class Done(val schemaVersion: Int, val blobs: Int, val movedAside: List<Path>) : RestoreResult

    data class Refused(val problems: List<String>) : RestoreResult
}

/** Puts a backup back into a data directory without ever deleting what was there (ADR 0031). Stop the server first. */
class RestoreService(private val dataDir: Path, private val crypto: Crypto, private val clock: Clock) {
    fun restore(source: ExportSource, force: Boolean): RestoreResult {
        Files.createDirectories(dataDir)
        val work = Files.createTempDirectory(dataDir, ".restore-work-")
        try {
            val verification = BackupVerifier.verify(source, crypto, work)
            val manifest = verification.manifest
            if (!verification.ok || manifest == null) return RestoreResult.Refused(verification.problems)
            val database = dataDir.resolve(Database.FILE_NAME)
            val blobsDir = dataDir.resolve(BLOBS_DIR_NAME)
            val hasBlobs = blobsDir.isDirectory() && Files.list(blobsDir).use { it.findAny().isPresent }
            if ((database.exists() || hasBlobs) && !force) {
                return RestoreResult.Refused(listOf("the data directory is not empty; use --force to move the existing files aside (nothing is deleted)"))
            }
            // Stage everything under temporary names first, so a failure leaves the existing data as it was.
            val stagedDb = work.resolve("staged.db")
            Files.write(stagedDb, source.read(BACKUP_DB_ENTRY, Long.MAX_VALUE)!!)
            val stagedBlobs = work.resolve("staged-blobs")
            if (manifest.includesBlobs) {
                val files = FileBlobFiles(stagedBlobs)
                manifest.blobs.forEach { ref -> files.write(CircleId(ref.circle), ref.hash, source.read(BackupService.blobEntry(ref), Long.MAX_VALUE)!!) }
            }
            val moved = mutableListOf<Path>()
            val suffix = ".before-restore-${fileStamp(clock)}"
            fun moveAside(path: Path) {
                if (path.exists()) {
                    val target = path.resolveSibling(path.fileName.toString() + suffix)
                    Files.move(path, target)
                    moved.add(target)
                }
            }
            moveAside(database)
            moveAside(dataDir.resolve("${Database.FILE_NAME}-wal"))
            moveAside(dataDir.resolve("${Database.FILE_NAME}-shm"))
            if (manifest.includesBlobs) moveAside(blobsDir)
            Files.move(stagedDb, database, StandardCopyOption.ATOMIC_MOVE)
            if (manifest.includesBlobs) {
                if (stagedBlobs.exists()) Files.move(stagedBlobs, blobsDir, StandardCopyOption.ATOMIC_MOVE) else Files.createDirectories(blobsDir)
            }
            return RestoreResult.Done(manifest.schemaVersion, manifest.blobs.size, moved)
        } finally {
            work.toFile().deleteRecursively()
        }
    }
}
