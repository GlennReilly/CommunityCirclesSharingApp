package circles.server.backup

import circles.api.ApiJson
import circles.crypto.Crypto
import circles.server.data.Database
import circles.server.data.FileBlobFiles
import circles.policy.CircleId
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import java.time.Clock
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneOffset.UTC)

/** A timestamp for file names, from the clock so tests can fix it. */
fun fileStamp(clock: Clock): String = STAMP.format(clock.instant())

sealed interface BackupResult {
    data class Done(val blobs: Int, val schemaVersion: Int) : BackupResult

    data class Failed(val problems: List<String>) : BackupResult
}

/** Writes `circles-backup/1` archives of a data directory while the server may be running (ADR 0031). */
class BackupService(private val dataDir: Path, private val crypto: Crypto) {
    /** Writes the archive to [target] (which must not exist); without [includeBlobs] it holds the database only. */
    fun backup(target: Path, includeBlobs: Boolean = true): BackupResult {
        if (!Files.exists(dataDir.resolve(Database.FILE_NAME))) return BackupResult.Failed(listOf("there is no database in $dataDir"))
        if (Files.exists(target)) return BackupResult.Failed(listOf("refusing to overwrite ${target.fileName}"))
        return try {
            write(target, includeBlobs)
        } catch (e: java.io.IOException) {
            BackupResult.Failed(listOf("could not write the backup: ${e::class.simpleName}: ${e.message}"))
        } catch (e: java.sql.SQLException) {
            BackupResult.Failed(listOf("could not read the database: ${e.message}"))
        }
    }

    private fun write(target: Path, includeBlobs: Boolean): BackupResult {
        Files.createDirectories(target.toAbsolutePath().parent)
        val work = Files.createTempDirectory(dataDir, ".backup-work-")
        try {
            val snapshot = work.resolve(Database.FILE_NAME)
            Database.openFile(dataDir).use { it.createStatement().use { s -> s.execute("VACUUM INTO '${snapshot.toString().replace("'", "''")}'") } }
            val facts = tidySnapshot(snapshot)
            val dbBytes = Files.readAllBytes(snapshot)
            val blobRefs = if (includeBlobs) facts.blobs else emptyList()
            val files = FileBlobFiles(dataDir.resolve(BLOBS_DIR_NAME))
            val blobBytes = mutableMapOf<BackupBlobRef, ByteArray>()
            val problems = mutableListOf<String>()
            blobRefs.forEach { ref ->
                val bytes = files.read(CircleId(ref.circle), ref.hash)
                when {
                    bytes == null -> problems += "blob ${ref.hash} of circle ${ref.circle} is recorded but its file is missing"
                    crypto.hash256(bytes).toHex() != ref.hash || bytes.size.toLong() != ref.size -> problems += "blob ${ref.hash} of circle ${ref.circle} does not match its hash"
                    else -> blobBytes[ref] = bytes
                }
            }
            if (problems.isNotEmpty()) return BackupResult.Failed(problems)
            val manifest = BackupManifest(BACKUP_FORMAT, facts.schemaVersion, includeBlobs, crypto.hash256(dbBytes).toHex(), dbBytes.size.toLong(), blobRefs)
            writeZip(target, manifest, dbBytes, blobBytes)
            return BackupResult.Done(blobRefs.size, facts.schemaVersion)
        } finally {
            work.toFile().deleteRecursively()
        }
    }

    private class SnapshotFacts(val schemaVersion: Int, val blobs: List<BackupBlobRef>)

    /** Drops sessions and challenges from the copy, makes it a single file, and reads what the manifest needs. */
    private fun tidySnapshot(snapshot: Path): SnapshotFacts =
        DriverManager.getConnection("jdbc:sqlite:$snapshot").use { c ->
            c.createStatement().use { s ->
                s.executeUpdate("DELETE FROM sessions")
                s.executeUpdate("DELETE FROM challenges")
                s.execute("PRAGMA journal_mode = DELETE")
            }
            val version = c.createStatement().use { s -> s.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version").use { it.next(); it.getInt(1) } }
            val blobs = c.createStatement().use { s ->
                s.executeQuery("SELECT circle_id, hash, size FROM blobs ORDER BY circle_id, hash").use { rs ->
                    buildList { while (rs.next()) add(BackupBlobRef(rs.getString(1), rs.getString(2), rs.getLong(3))) }
                }
            }
            SnapshotFacts(version, blobs)
        }

    private fun writeZip(target: Path, manifest: BackupManifest, db: ByteArray, blobs: Map<BackupBlobRef, ByteArray>) {
        val temp = Files.createTempFile(target.toAbsolutePath().parent, ".backup-", ".tmp")
        try {
            ZipOutputStream(Files.newOutputStream(temp)).use { zip ->
                fun put(name: String, bytes: ByteArray) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
                put(MANIFEST_NAME, ApiJson.encodeToString(BackupManifest.serializer(), manifest).encodeToByteArray())
                put(BACKUP_DB_ENTRY, db)
                blobs.forEach { (ref, bytes) -> put(blobEntry(ref), bytes) }
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    companion object {
        const val MANIFEST_NAME = "manifest.json"

        fun blobEntry(ref: BackupBlobRef): String = "$BACKUP_BLOB_PREFIX${ref.circle}/${ref.hash.take(2)}/${ref.hash}"
    }
}
