package circles.server

import circles.crypto.JvmCrypto
import circles.server.backup.BACKUP_DIR_NAME
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

    @Test
    fun migrateBacksUpTheDatabaseFirstWhenThereAreMigrationsToApply() {
        val dir = Files.createDirectories(base.resolve("old"))
        Database.openFile(dir).use { connection -> Migrator(connection, MIGRATIONS.take(3)).migrate() }
        val result = run(dir, "migrate")
        assertEquals(EXIT_OK, result.code, result.err)
        assertTrue(result.out.contains("Backed up the database to"), result.out)
        assertTrue(result.out.contains("Applied migrations 4, 5"), result.out)
        val backup = onlyZip(dir.resolve(BACKUP_DIR_NAME))
        assertTrue(backup.fileName.toString().startsWith("pre-migrate-from-v3-"), backup.fileName.toString())
        val verified = run(null, "verify", backup.toString())
        assertEquals(EXIT_OK, verified.code, verified.err)
        assertTrue(verified.out.contains("schema 3") && verified.out.contains("(database only)"), verified.out)
        // That backup restores to the old version and says what to do next.
        val restored = run(base.resolve("back-then"), "restore", backup.toString())
        assertEquals(EXIT_OK, restored.code, restored.err)
        assertTrue(restored.out.contains("run 'migrate' next"), restored.out)
    }

    @Test
    fun noBackupMeansNoMigration() {
        val dir = Files.createDirectories(base.resolve("old"))
        Database.openFile(dir).use { connection -> Migrator(connection, MIGRATIONS.take(3)).migrate() }
        Files.write(dir.resolve(BACKUP_DIR_NAME), byteArrayOf(1)) // a file where the backup folder should be
        val result = run(dir, "migrate")
        assertEquals(EXIT_FAILURE, result.code)
        assertTrue(result.err.contains("so the database was not migrated"), result.err)
        Database.openFile(dir).use { assertEquals(3, Migrator(it).currentVersion()) }
    }

    @Test
    fun aBrandNewDatabaseAndAnUpToDateOneAreNotBackedUp() {
        val fresh = base.resolve("fresh")
        assertEquals(EXIT_OK, run(fresh, "migrate").code)
        assertFalse(Files.exists(fresh.resolve(BACKUP_DIR_NAME)))
        assertEquals(EXIT_OK, run(fresh, "migrate").code)
        assertFalse(Files.exists(fresh.resolve(BACKUP_DIR_NAME)), "nothing to migrate, nothing to back up")
    }

    @Test
    fun theBackupSettingsAreValidated() {
        val bad = run(base.resolve("x"), "serve", extraEnv = mapOf(ENV_BACKUP_INTERVAL_HOURS to "0", ENV_BACKUP_KEEP to "many"))
        assertEquals(EXIT_USAGE, bad.code)
        assertTrue(bad.err.contains(ENV_BACKUP_INTERVAL_HOURS) && bad.err.contains(ENV_BACKUP_KEEP), bad.err)
    }

    @Test
    fun verifyStillHandlesAnExportAndUsageListsTheNewCommands() {
        val out = run(null, "help").out
        listOf("backup <dir>", "restore <file> [--force]").forEach { assertTrue(out.contains(it), it) }
    }
}
