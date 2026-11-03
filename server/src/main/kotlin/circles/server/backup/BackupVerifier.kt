package circles.server.backup

import circles.api.ApiJson
import circles.api.MAX_BLOB_BYTES
import circles.crypto.Crypto
import circles.event.isLowerHex
import circles.event.isValidRandomId
import circles.export.ExportSource
import circles.server.data.MIGRATIONS
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlinx.serialization.SerializationException

private const val MAX_MANIFEST_BYTES = 64L * 1024 * 1024
private const val MAX_DB_BYTES = 64L * 1024 * 1024 * 1024
private const val MAX_BACKUP_BLOBS = 5_000_000
private const val HASH_HEX = 64

/** The schema version this build writes; a backup from a newer one is refused. */
val LATEST_SCHEMA: Int = MIGRATIONS.maxOf { it.version }

class BackupVerification(val problems: List<String>, val manifest: BackupManifest?) {
    val ok: Boolean get() = problems.isEmpty()
}

/** Checks a `circles-backup/1` archive: manifest, hashes, entry names, and that the database is sound (ADR 0031). */
object BackupVerifier {
    /** [work] is a scratch folder the database is copied into to be opened; the caller removes it. */
    fun verify(source: ExportSource, crypto: Crypto, work: Path): BackupVerification {
        val problems = mutableListOf<String>()
        source.entryNames.groupBy { it }.filter { it.value.size > 1 }.keys.forEach { problems += "entry '$it' appears more than once" }
        val manifest = readManifest(source, problems) ?: return BackupVerification(problems, null)
        val expected = manifest.blobs.associateBy { BackupService.blobEntry(it) }
        if (expected.size != manifest.blobs.size) problems += "the manifest lists a blob twice"
        source.entryNames.toSet().forEach { name ->
            if (name != BackupService.MANIFEST_NAME && name != BACKUP_DB_ENTRY && name !in expected) problems += "unexpected entry '$name'"
        }
        checkBlobs(source, manifest, expected, crypto, problems)
        checkDatabase(source, manifest, crypto, work, problems)
        return BackupVerification(problems, manifest)
    }

    private fun readManifest(source: ExportSource, problems: MutableList<String>): BackupManifest? {
        val bytes = source.read(BackupService.MANIFEST_NAME, MAX_MANIFEST_BYTES)
        if (bytes == null) {
            problems += if (source.sizeOf(BackupService.MANIFEST_NAME) == null) "manifest.json is missing" else "manifest.json is too large"
            return null
        }
        val manifest = try {
            ApiJson.decodeFromString(BackupManifest.serializer(), bytes.decodeToString())
        } catch (_: SerializationException) {
            problems += "manifest.json is not valid"
            return null
        } catch (_: IllegalArgumentException) {
            problems += "manifest.json is not valid"
            return null
        }
        if (manifest.format != BACKUP_FORMAT) problems += "unsupported format '${manifest.format}' (this reads $BACKUP_FORMAT)"
        if (manifest.schemaVersion < 1) problems += "schemaVersion must be at least 1"
        if (manifest.schemaVersion > LATEST_SCHEMA) {
            problems += "the backup is from schema ${manifest.schemaVersion} but this server only knows ${LATEST_SCHEMA}; use a newer server"
        }
        if (!isLowerHex(manifest.dbHash, HASH_HEX)) problems += "dbHash is not a BLAKE2b-256 hash"
        if (manifest.blobs.size > MAX_BACKUP_BLOBS) problems += "more than $MAX_BACKUP_BLOBS blobs"
        if (!manifest.includesBlobs && manifest.blobs.isNotEmpty()) problems += "a database-only backup lists blobs"
        manifest.blobs.forEach {
            if (!isValidRandomId(it.circle) || !isLowerHex(it.hash, HASH_HEX)) problems += "a blob entry has a malformed circle or hash"
        }
        return manifest
    }

    private fun checkBlobs(source: ExportSource, manifest: BackupManifest, expected: Map<String, BackupBlobRef>, crypto: Crypto, problems: MutableList<String>) {
        expected.forEach { (name, ref) ->
            if (!isValidRandomId(ref.circle) || !isLowerHex(ref.hash, HASH_HEX)) return@forEach
            val declared = source.sizeOf(name)
            when {
                declared == null -> problems += "blob ${ref.hash.take(SHORT)}… is listed but missing"
                declared > MAX_BLOB_BYTES -> problems += "blob ${ref.hash.take(SHORT)}… is larger than $MAX_BLOB_BYTES bytes"
                declared != ref.size -> problems += "blob ${ref.hash.take(SHORT)}… is $declared bytes but the manifest says ${ref.size}"
                else -> {
                    val bytes = source.read(name, MAX_BLOB_BYTES.toLong())
                    if (bytes == null || crypto.hash256(bytes).toHex() != ref.hash) problems += "blob ${ref.hash.take(SHORT)}… does not match its hash"
                }
            }
        }
        if (manifest.includesBlobs.not() && source.entryNames.any { it.startsWith(BACKUP_BLOB_PREFIX) }) problems += "a database-only backup holds blobs"
    }

    private fun checkDatabase(source: ExportSource, manifest: BackupManifest, crypto: Crypto, work: Path, problems: MutableList<String>) {
        val declared = source.sizeOf(BACKUP_DB_ENTRY)
        if (declared == null) {
            problems += "$BACKUP_DB_ENTRY is missing"
            return
        }
        val bytes = source.read(BACKUP_DB_ENTRY, MAX_DB_BYTES)
        if (bytes == null) {
            problems += "$BACKUP_DB_ENTRY is too large or unreadable"
            return
        }
        if (bytes.size.toLong() != manifest.dbSize) problems += "$BACKUP_DB_ENTRY is ${bytes.size} bytes but the manifest says ${manifest.dbSize}"
        if (crypto.hash256(bytes).toHex() != manifest.dbHash) {
            problems += "$BACKUP_DB_ENTRY does not match dbHash"
            return
        }
        val file = work.resolve("verify-$BACKUP_DB_ENTRY")
        Files.write(file, bytes)
        try {
            DriverManager.getConnection("jdbc:sqlite:$file").use { c ->
                val integrity = c.createStatement().use { s -> s.executeQuery("PRAGMA integrity_check").use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } } }
                if (integrity != listOf("ok")) problems += "the database fails its integrity check: ${integrity.take(3).joinToString("; ")}"
                val version = c.createStatement().use { s -> s.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_version").use { it.next(); it.getInt(1) } }
                if (version != manifest.schemaVersion) problems += "the database is at schema $version but the manifest says ${manifest.schemaVersion}"
            }
        } catch (e: java.sql.SQLException) {
            problems += "$BACKUP_DB_ENTRY cannot be opened as a database"
        } finally {
            Files.deleteIfExists(file)
        }
    }

    private const val SHORT = 12
}
