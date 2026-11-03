package circles.server.backup

import circles.api.ApiJson
import circles.api.PushStatus
import circles.crypto.JvmCrypto
import circles.encoding.ByteString
import circles.server.data.Database
import circles.server.data.MIGRATIONS
import circles.server.domain.MILLIS_PER_DAY
import circles.server.support.CircleFixture
import circles.server.support.HandoverFixture
import circles.server.support.LiveServer
import circles.server.support.MutableClock
import circles.server.support.TODAY
import circles.server.support.TestServer
import circles.server.support.newInviteKey
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** Server backup and restore (ADR 0031), over real database files. */
class BackupRestoreTest {
    private val base: Path = Files.createTempDirectory("circles-backup")
    private val crypto = JvmCrypto()
    private val clock = MutableClock()
    private val toClose = mutableListOf<AutoCloseable>()

    @AfterTest
    fun cleanUp() {
        toClose.reversed().forEach { it.close() }
        base.toFile().deleteRecursively()
    }

    private fun <T : AutoCloseable> T.tracked(): T = also { toClose += it }

    private fun dir(name: String): Path = Files.createDirectories(base.resolve(name))

    private fun zip(file: Path) = ZipExportSource(file).tracked()

    private fun backupOf(dataDir: Path, name: String = "backup.zip", includeBlobs: Boolean = true): Path {
        val target = base.resolve(name)
        assertIs<BackupResult.Done>(BackupService(dataDir, crypto).backup(target, includeBlobs))
        return target
    }

    private fun verify(file: Path): BackupVerification {
        val work = Files.createTempDirectory(base, "work-")
        return BackupVerifier.verify(zip(file), crypto, work)
    }

    private fun restore(dataDir: Path, file: Path, force: Boolean = false) = RestoreService(dataDir, crypto, clock).restore(zip(file), force)

    private fun entriesOf(file: Path): Map<String, ByteArray> = zip(file).let { s -> s.entryNames.associateWith { s.read(it, Long.MAX_VALUE)!! } }

    private fun writeZip(file: Path, entries: Map<String, ByteArray>) {
        ZipOutputStream(Files.newOutputStream(file)).use { z -> entries.forEach { (n, b) -> z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
    }

    private fun dbQuery(path: Path, sql: String): List<List<Any?>> =
        DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            c.createStatement().use { s -> s.executeQuery(sql).use { rs -> buildList { while (rs.next()) add((1..rs.metaData.columnCount).map { rs.getObject(it) }) } } }
        }

    /** A host with a full circle in a real data directory. Returns the directory, the fixture and the event count. */
    private fun host(name: String): Triple<Path, CircleFixture, Int> {
        val data = dir(name)
        val server = TestServer(dataDir = data).tracked()
        val fixture = CircleFixture(server.services, server.crypto)
        return Triple(data, fixture, fixture.build())
    }

    @Test
    fun aBackupVerifiesAndHoldsTheWholeServerWithoutSessionsOrChallenges() {
        val (data, fixture, count) = host("a")
        // Leave a challenge and a session in the live database, as a running server would have.
        val services = fixture.services
        services.auth.challenge(fixture.founder.identity.device.v)
        services.store.transaction { it.putSession(ByteString.of(byteArrayOf(1, 2)), fixture.founder.identity.device, clock.millis() + 1_000_000) }
        assertEquals(1, services.store.transaction { tx -> tx.findSession(ByteString.of(byteArrayOf(1, 2))) }?.let { 1 } ?: 0)
        val file = backupOf(data)
        val report = verify(file)
        assertEquals(emptyList(), report.problems)
        val manifest = report.manifest!!
        assertEquals(BACKUP_FORMAT, manifest.format)
        assertEquals(MIGRATIONS.maxOf { it.version }, manifest.schemaVersion)
        assertTrue(manifest.includesBlobs)
        assertEquals(2, manifest.blobs.size)
        val extracted = base.resolve("extracted.db").also { Files.write(it, entriesOf(file).getValue(BACKUP_DB_ENTRY)) }
        assertEquals(count.toLong(), (dbQuery(extracted, "SELECT COUNT(*) FROM events")[0][0] as Number).toLong())
        assertEquals(0, dbQuery(extracted, "SELECT 1 FROM sessions").size, "no session tokens in the archive")
        assertEquals(0, dbQuery(extracted, "SELECT 1 FROM challenges").size, "no challenges in the archive")
        assertEquals(1, dbQuery(data.resolve(Database.FILE_NAME), "SELECT 1 FROM sessions").size, "the live database is untouched")
        assertEquals(1, dbQuery(data.resolve(Database.FILE_NAME), "SELECT 1 FROM challenges").size)
    }

    @Test
    fun aBackupTakenWhileTheServerIsWritingIsConsistent() {
        val (data, fixture, count) = host("a")
        val running = java.util.concurrent.atomic.AtomicBoolean(true)
        val written = java.util.concurrent.atomic.AtomicInteger(0)
        val writer = thread {
            while (running.get()) {
                fixture.addMore(1)
                written.incrementAndGet()
                Thread.sleep(1)
            }
        }
        while (written.get() < 5) Thread.sleep(2)
        val file = backupOf(data, "busy.zip")
        running.set(false)
        writer.join()
        val extracted = base.resolve("busy.db").also { Files.write(it, entriesOf(file).getValue(BACKUP_DB_ENTRY)) }
        val rows = dbQuery(extracted, "SELECT COUNT(*), MAX(server_seq) FROM events").single().map { (it as Number).toLong() }
        assertTrue(rows[0] >= count + 5, "at least what existed when the backup began")
        assertEquals(rows[0], rows[1], "an unbroken log: no gaps")
        assertEquals(emptyList(), verify(file).problems)
    }

    @Test
    fun aDatabaseOnlyBackupHoldsNoBlobs() {
        val (data, _, _) = host("a")
        val file = backupOf(data, "db-only.zip", includeBlobs = false)
        val manifest = verify(file).manifest!!
        assertFalse(manifest.includesBlobs)
        assertEquals(emptyList(), manifest.blobs)
        assertTrue(entriesOf(file).keys.none { it.startsWith(BACKUP_BLOB_PREFIX) })
    }

    @Test
    fun backupRefusesToOverwriteAndNeedsADatabaseAndAllItsBlobs() {
        val (data, fixture, _) = host("a")
        val file = backupOf(data)
        val again = assertIs<BackupResult.Failed>(BackupService(data, crypto).backup(file))
        assertTrue(again.problems.single().contains("refusing to overwrite"))
        val empty = dir("empty")
        assertTrue(assertIs<BackupResult.Failed>(BackupService(empty, crypto).backup(base.resolve("none.zip"))).problems.single().contains("no database"))
        val blobFile = data.resolve("blobs").resolve(fixture.circle.v).resolve(fixture.hash(fixture.blobA).take(2)).resolve(fixture.hash(fixture.blobA))
        Files.delete(blobFile)
        val failed = assertIs<BackupResult.Failed>(BackupService(data, crypto).backup(base.resolve("broken.zip")))
        assertTrue(failed.problems.single().contains("missing"))
        assertFalse(base.resolve("broken.zip").exists(), "no half-written archive")
        assertEquals(emptyList(), Files.list(data).use { s -> s.filter { it.fileName.toString().startsWith(".backup") }.toList() }, "no scratch files left")
    }

    @Test
    fun aDestroyedHostIsRebuiltFromABackupAndExistingDevicesReconnect() {
        // Spec section 8, handover test 1: 3 stewards, 5 members, backup, destroy the host, restore on a new one.
        val oldData = dir("old")
        val old = TestServer(dataDir = oldData)
        val fixture = HandoverFixture(old.services, old.crypto)
        val count = fixture.build()
        val before = old.services.sync.pull(fixture.circle, 0, 1_000).events
        val file = backupOf(oldData, "handover.zip")
        old.close()
        oldData.toFile().deleteRecursively() // the host is destroyed

        val newData = dir("new")
        val done = assertIs<RestoreResult.Done>(restore(newData, file))
        assertEquals(1, done.blobs)
        val live = LiveServer(TestServer(dataDir = newData)).tracked()
        assertEquals(before, live.server.services.sync.pull(fixture.circle, 0, 1_000).events, "the full history, with the same sequence numbers")
        assertEquals(3, live.server.rows("SELECT 1 FROM stewards WHERE removed_seq IS NULL").size)
        assertEquals(5, live.server.rows("SELECT DISTINCT member_id FROM devices").size)
        assertContentEquals(fixture.blob, live.server.services.blobs!!.get(fixture.circle, fixture.crypto.hash256(fixture.blob).toHex()))
        runBlocking {
            fixture.members.forEach { member ->
                val token = live.api.login(member.identity) // no re-enrolment: the key each device already has still works
                val pulled = live.api.pull(token, fixture.circle, after = 0, limit = 1_000).second!!
                assertEquals(count, pulled.events.size)
            }
            val token = live.api.login(fixture.members[3].identity)
            val push = live.api.push(token, fixture.circle, listOf(fixture.members[3].invite(fixture.circle, newInviteKey(fixture.crypto), TODAY + 5, 1, TODAY)))
            assertEquals(PushStatus.ACCEPTED, push.second.single().status)
            assertEquals(count + 1L, push.second.single().serverSeq)
        }
    }

    @Test
    fun restoreRefusesANonEmptyDirectoryUnlessForcedAndThenMovesAsideInsteadOfDeleting() {
        val (data, fixture, _) = host("a")
        val file = backupOf(data)
        val target = dir("target")
        val other = TestServer(dataDir = target)
        val otherFixture = CircleFixture(other.services, other.crypto)
        otherFixture.build()
        other.close()
        val oldDb = Files.readAllBytes(target.resolve(Database.FILE_NAME))
        val refused = assertIs<RestoreResult.Refused>(restore(target, file))
        assertTrue(refused.problems.single().contains("--force"))
        assertContentEquals(oldDb, Files.readAllBytes(target.resolve(Database.FILE_NAME)), "nothing changed")

        val forced = assertIs<RestoreResult.Done>(restore(target, file, force = true))
        val movedNames = forced.movedAside.map { it.fileName.toString() }
        assertTrue(movedNames.any { it.startsWith("circles.db.before-restore-") }, movedNames.toString())
        assertTrue(movedNames.any { it.startsWith("blobs.before-restore-") }, movedNames.toString())
        val kept = forced.movedAside.first { it.fileName.toString().startsWith("circles.db.before-restore-") }
        assertContentEquals(oldDb, Files.readAllBytes(kept), "the old database was moved, not deleted")
        assertEquals(fixture.events.size.toLong(), (dbQuery(target.resolve(Database.FILE_NAME), "SELECT COUNT(*) FROM events")[0][0] as Number).toLong())
        assertTrue(target.resolve("blobs").resolve(fixture.circle.v).exists())
        assertFalse(target.resolve("blobs").resolve(otherFixture.circle.v).exists())
    }

    @Test
    fun aCorruptedOrIncompleteArchiveIsRefusedAndTheTargetIsLeftAlone() {
        val (data, _, _) = host("a")
        val file = backupOf(data)
        val entries = entriesOf(file)
        val target = dir("target")

        val flippedDb = entries.getValue(BACKUP_DB_ENTRY).copyOf().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 1).toByte() }
        val badDb = base.resolve("bad-db.zip").also { writeZip(it, entries + (BACKUP_DB_ENTRY to flippedDb)) }
        assertTrue(assertIs<RestoreResult.Refused>(restore(target, badDb)).problems.any { it.contains("does not match dbHash") })

        val blobName = entries.keys.first { it.startsWith(BACKUP_BLOB_PREFIX) }
        val missingBlob = base.resolve("missing-blob.zip").also { writeZip(it, entries - blobName) }
        assertTrue(assertIs<RestoreResult.Refused>(restore(target, missingBlob)).problems.any { it.contains("listed but missing") })

        val flippedBlob = entries.getValue(blobName).copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val badBlob = base.resolve("bad-blob.zip").also { writeZip(it, entries + (blobName to flippedBlob)) }
        assertTrue(assertIs<RestoreResult.Refused>(restore(target, badBlob)).problems.any { it.contains("does not match its hash") })

        val extra = base.resolve("extra.zip").also { writeZip(it, entries + ("notes.txt" to byteArrayOf(1))) }
        assertTrue(assertIs<RestoreResult.Refused>(restore(target, extra)).problems.any { it.contains("unexpected entry 'notes.txt'") })

        val notADb = byteArrayOf(1, 2, 3, 4)
        val manifest = ApiJson.decodeFromString(BackupManifest.serializer(), entries.getValue("manifest.json").decodeToString())
        val fake = manifest.copy(dbHash = crypto.hash256(notADb).toHex(), dbSize = notADb.size.toLong())
        val notSqlite = base.resolve("not-sqlite.zip").also { writeZip(it, entries + (BACKUP_DB_ENTRY to notADb) + ("manifest.json" to ApiJson.encodeToString(BackupManifest.serializer(), fake).encodeToByteArray())) }
        assertTrue(assertIs<RestoreResult.Refused>(restore(target, notSqlite)).problems.any { it.contains("cannot be opened") || it.contains("integrity") })

        assertFalse(target.resolve(Database.FILE_NAME).exists(), "the target never received a database")
        assertEquals(emptyList(), Files.list(target).use { s -> s.toList() }, "and no scratch files are left")
    }

    @Test
    fun anArchiveFromANewerSchemaIsRefused() {
        val (data, _, _) = host("a")
        val file = backupOf(data)
        val entries = entriesOf(file)
        val manifest = ApiJson.decodeFromString(BackupManifest.serializer(), entries.getValue("manifest.json").decodeToString())
        val newer = base.resolve("newer.zip").also {
            writeZip(it, entries + ("manifest.json" to ApiJson.encodeToString(BackupManifest.serializer(), manifest.copy(schemaVersion = LATEST_SCHEMA + 1)).encodeToByteArray()))
        }
        assertTrue(assertIs<RestoreResult.Refused>(restore(dir("target"), newer)).problems.any { it.contains("use a newer server") })
    }

    @Test
    fun aDatabaseOnlyArchiveReplacesTheDatabaseAndKeepsTheBlobs() {
        val (data, fixture, _) = host("a")
        val file = backupOf(data, "db-only.zip", includeBlobs = false)
        val before = Files.list(data.resolve("blobs")).use { it.toList() }
        val done = assertIs<RestoreResult.Done>(restore(data, file, force = true))
        assertEquals(0, done.blobs)
        assertTrue(done.movedAside.none { it.fileName.toString().startsWith("blobs") }, "blobs stay where they are")
        assertEquals(before, Files.list(data.resolve("blobs")).use { it.toList() })
        assertTrue(data.resolve("blobs").resolve(fixture.circle.v).resolve(fixture.hash(fixture.blobA).take(2)).resolve(fixture.hash(fixture.blobA)).exists())
    }

    @Test
    fun theSchedulerWritesScheduledBackupsAndKeepsOnlyTheNewest() {
        val (data, _, _) = host("a")
        val backups = dir("elsewhere")
        val scheduler = BackupScheduler(BackupService(data, crypto), backups, keep = 2, clock = clock, log = circles.server.logging.NoLog)
        Files.write(backups.resolve("pre-migrate-from-v3-20260101000000.zip"), byteArrayOf(1))
        Files.write(backups.resolve("notes.txt"), byteArrayOf(2))
        repeat(4) {
            assertIs<BackupResult.Done>(scheduler.runOnce())
            clock.millis += MILLIS_PER_DAY
        }
        val scheduled = Files.list(backups).use { s -> s.map { it.fileName.toString() }.filter { it.startsWith("scheduled-") }.sorted().toList() }
        assertEquals(2, scheduled.size, scheduled.toString())
        assertTrue(backups.resolve("pre-migrate-from-v3-20260101000000.zip").exists(), "other files are never pruned")
        assertTrue(backups.resolve("notes.txt").exists())
        assertEquals(emptyList(), verify(backups.resolve(scheduled.last())).problems)
    }

    @Test
    fun twoScheduledRunsInTheSameSecondGetDifferentNames() {
        val (data, _, _) = host("a")
        val backups = dir("elsewhere")
        val scheduler = BackupScheduler(BackupService(data, crypto), backups, keep = 5, clock = clock, log = circles.server.logging.NoLog)
        assertIs<BackupResult.Done>(scheduler.runOnce())
        assertIs<BackupResult.Done>(scheduler.runOnce())
        assertEquals(2, Files.list(backups).use { it.toList() }.size)
    }

    @Test
    fun aFailedScheduledRunPrunesNothing() {
        val (data, fixture, _) = host("a")
        val backups = dir("elsewhere")
        val scheduler = BackupScheduler(BackupService(data, crypto), backups, keep = 1, clock = clock, log = circles.server.logging.NoLog)
        assertIs<BackupResult.Done>(scheduler.runOnce())
        clock.millis += MILLIS_PER_DAY
        Files.delete(data.resolve("blobs").resolve(fixture.circle.v).resolve(fixture.hash(fixture.blobB).take(2)).resolve(fixture.hash(fixture.blobB)))
        assertIs<BackupResult.Failed>(scheduler.runOnce())
        assertEquals(1, Files.list(backups).use { it.toList() }.size, "the last good backup is still there")
    }


}