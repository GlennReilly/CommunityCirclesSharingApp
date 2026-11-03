package circles.server

import circles.crypto.JvmCrypto
import circles.server.config.ENV_BACKUP_DIR
import circles.server.config.ENV_BACKUP_INTERVAL_HOURS
import circles.server.config.ENV_BACKUP_KEEP
import circles.server.config.ENV_DATA_DIR
import circles.server.data.Database
import circles.server.data.MIGRATIONS
import circles.server.data.Migrator
import circles.server.support.CircleFixture
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `backup`, `restore`, `verify` of a backup and the backup before a migration (ADR 0031). */
class CliBackupTest {
    private class Run(val code: Int, val out: String, val err: String)

    private val base: Path = Files.createTempDirectory("circles-cli-backup")

    @AfterTest
    fun cleanUp() {
        base.toFile().deleteRecursively()
    }

    private fun run(dataDir: Path?, vararg args: String, extraEnv: Map<String, String> = emptyMap()): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val env = (if (dataDir == null) emptyMap() else mapOf(ENV_DATA_DIR to dataDir.toString())) + extraEnv
        val code = Cli(env, PrintStream(out), PrintStream(err), launcher = { _, _ -> }).run(args.toList())
        return Run(code, out.toString(), err.toString())
    }

    private fun host(name: String): Path {
        val dir = Files.createDirectories(base.resolve(name))
        val crypto = JvmCrypto()
        Database.openFile(dir).use { connection ->
            Migrator(connection).migrate()
            CircleFixture(buildServices(connection, crypto, java.time.Clock.systemUTC(), true, blobsDir = dir.resolve("blobs")), crypto).build()
        }
        return dir
    }

    private fun onlyZip(dir: Path): Path = Files.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(".zip") }.toList() }.single()

    @Test
    fun backupThenVerifyThenRestoreOnANewHost() {
        val from = host("from")
        val folder = base.resolve("backups-out")
        val made = run(from, "backup", folder.toString())
        assertEquals(EXIT_OK, made.code, made.err)
        assertTrue(made.out.contains("and 2 blobs to"), made.out)
        val file = onlyZip(folder)
        assertTrue(file.fileName.toString().startsWith("circles-backup-"))

        val verified = run(null, "verify", file.toString())
        assertEquals(EXIT_OK, verified.code, verified.err)
        assertTrue(verified.out.contains("OK: backup of schema ${MIGRATIONS.maxOf { it.version }} with 2 blobs"), verified.out)

        val to = base.resolve("to")
        val restored = run(to, "restore", file.toString())
        assertEquals(EXIT_OK, restored.code, restored.err)
        assertTrue(restored.out.contains("Restored the database"), restored.out)
        assertFalse(restored.out.contains("run 'migrate'"), "the schema is current")
        // The restored host serves: migrate is a no-op and an export of the circle works.
        assertTrue(run(to, "migrate").out.contains("up to date"))
    }

    @Test
    fun restoreRefusesANonEmptyHostUnlessForcedAndSaysWhatItMovedAside() {
        val from = host("from")
        val folder = base.resolve("out")
        run(from, "backup", folder.toString())
        val file = onlyZip(folder)
        val target = host("target")
        val refused = run(target, "restore", file.toString())
        assertEquals(EXIT_FAILURE, refused.code)
        assertTrue(refused.err.contains("--force"), refused.err)
        assertTrue(refused.err.contains("Nothing was restored"))
        val forced = run(target, "restore", file.toString(), "--force")
        assertEquals(EXIT_OK, forced.code, forced.err)
        assertTrue(forced.out.contains("Moved aside, not deleted:"), forced.out)
        assertTrue(Files.list(target).use { s -> s.anyMatch { it.fileName.toString().startsWith("circles.db.before-restore-") } })
    }

    @Test
    fun backupAndRestoreNeedTheRightArguments() {
        assertEquals(EXIT_USAGE, run(null, "backup").code)
        assertEquals(EXIT_USAGE, run(null, "restore").code)
        assertEquals(EXIT_USAGE, run(null, "restore", "a", "b").code)
        val missing = run(base.resolve("d"), "restore", base.resolve("none.zip").toString())
        assertEquals(EXIT_FAILURE, missing.code)
        assertTrue(missing.err.contains("no such file"))
        val noDb = run(base.resolve("empty"), "backup", base.resolve("o").toString())
        assertEquals(EXIT_FAILURE, noDb.code)
        assertTrue(noDb.err.contains("no database"))
    }

    private fun oldHost(name: String): Path {
        val dir = Files.createDirectories(base.resolve(name))
        Database.openFile(dir).use { connection -> Migrator(connection, MIGRATIONS.take(3)).migrate() }
        return dir
    }

    @Test
    fun migrateTakesAFullBackupInTheConfiguredFolderFirst() {
        val dir = oldHost("old")
        val safe = base.resolve("safe")
        val result = run(dir, "migrate", extraEnv = mapOf(ENV_BACKUP_DIR to safe.toString()))
        assertEquals(EXIT_OK, result.code, result.err)
        assertTrue(result.out.contains("Backed up the database and 0 blobs to"), result.out)
        assertTrue(result.out.contains("Applied migrations 4, 5"), result.out)
        val backup = onlyZip(safe)
        assertTrue(backup.fileName.toString().startsWith("pre-migrate-from-v3-"), backup.fileName.toString())
        assertFalse(Files.exists(dir.resolve("backups")), "nothing is written inside the data directory")
        val verified = run(null, "verify", backup.toString())
        assertEquals(EXIT_OK, verified.code, verified.err)
        assertTrue(verified.out.contains("schema 3") && !verified.out.contains("database only"), verified.out)
        // That backup restores to the old version and says what to do next.
        val restored = run(base.resolve("back-then"), "restore", backup.toString())
        assertEquals(EXIT_OK, restored.code, restored.err)
        assertTrue(restored.out.contains("run 'migrate' next"), restored.out)
    }

    @Test
    fun theBackupBeforeMigratingIncludesTheBlobs() {
        // A host one migration behind (schema 4) with two blobs on disk and in its blob table.
        val data = Files.createDirectories(base.resolve("blobs-host"))
        val crypto = JvmCrypto()
        val circle = circles.event.newCircleId(crypto)
        val files = circles.server.data.FileBlobFiles(data.resolve("blobs"))
        val blobs = listOf(ByteArray(300) { it.toByte() }, ByteArray(80) { (it * 2).toByte() })
        Database.openFile(data).use { c ->
            Migrator(c, MIGRATIONS.take(4)).migrate()
            blobs.forEach { bytes ->
                val hash = crypto.hash256(bytes).toHex()
                files.write(circle, hash, bytes)
                c.createStatement().use { it.executeUpdate("INSERT INTO blobs (circle_id, hash, size, created_at) VALUES ('${circle.v}', '$hash', ${bytes.size}, 1)") }
            }
        }
        val safe = base.resolve("safe")
        val result = run(data, "migrate", extraEnv = mapOf(ENV_BACKUP_DIR to safe.toString()))
        assertEquals(EXIT_OK, result.code, result.err)
        assertTrue(result.out.contains("and 2 blobs to"), result.out)
        assertTrue(run(null, "verify", onlyZip(safe).toString()).out.contains("with 2 blobs"))
        Database.openFile(data).use { assertEquals(5, Migrator(it).currentVersion()) }
    }

    @Test
    fun migrateRefusesWithoutABackupFolder() {
        val dir = oldHost("old")
        val result = run(dir, "migrate")
        assertEquals(EXIT_FAILURE, result.code)
        assertTrue(result.err.contains(ENV_BACKUP_DIR) && result.err.contains("not migrated"), result.err)
        Database.openFile(dir).use { assertEquals(3, Migrator(it).currentVersion()) }
    }

    @Test
    fun noBackupMeansNoMigration() {
        val dir = oldHost("old")
        val blocked = base.resolve("blocked")
        Files.write(blocked, byteArrayOf(1)) // a file where the backup folder should be
        val result = run(dir, "migrate", extraEnv = mapOf(ENV_BACKUP_DIR to blocked.toString()))
        assertEquals(EXIT_FAILURE, result.code)
        assertTrue(result.err.contains("so the database was not migrated"), result.err)
        Database.openFile(dir).use { assertEquals(3, Migrator(it).currentVersion()) }
    }

    @Test
    fun aBrandNewDatabaseAndAnUpToDateOneNeedNoBackupFolder() {
        val fresh = base.resolve("fresh")
        val safe = base.resolve("safe")
        assertEquals(EXIT_OK, run(fresh, "migrate").code)
        val second = run(fresh, "migrate", extraEnv = mapOf(ENV_BACKUP_DIR to safe.toString()))
        assertEquals(EXIT_OK, second.code)
        assertTrue(second.out.contains("up to date"))
        assertFalse(Files.exists(safe), "nothing to migrate, nothing to back up")
    }

    @Test
    fun theBackupSettingsAreValidated() {
        val bad = run(base.resolve("x"), "serve", extraEnv = mapOf(ENV_BACKUP_INTERVAL_HOURS to "0", ENV_BACKUP_KEEP to "many"))
        assertEquals(EXIT_USAGE, bad.code)
        assertTrue(bad.err.contains(ENV_BACKUP_INTERVAL_HOURS) && bad.err.contains(ENV_BACKUP_KEEP), bad.err)
        val noFolder = run(base.resolve("y"), "serve", extraEnv = mapOf(ENV_BACKUP_INTERVAL_HOURS to "6"))
        assertEquals(EXIT_USAGE, noFolder.code)
        assertTrue(noFolder.err.contains("needs $ENV_BACKUP_DIR"), noFolder.err)
        val ok = run(base.resolve("z"), "serve", extraEnv = mapOf(ENV_BACKUP_INTERVAL_HOURS to "6", ENV_BACKUP_DIR to base.resolve("safe").toString()))
        assertEquals(EXIT_OK, ok.code, ok.err)
    }

    @Test
    fun verifyStillHandlesAnExportAndUsageListsTheNewCommands() {
        val out = run(null, "help").out
        listOf("backup <dir>", "restore <file> [--force]").forEach { assertTrue(out.contains(it), it) }
    }
}
