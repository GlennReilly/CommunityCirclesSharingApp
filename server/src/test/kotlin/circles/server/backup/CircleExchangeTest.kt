package circles.server.backup

import circles.api.PushStatus
import circles.export.BLOB_ENTRY_PREFIX
import circles.export.CircleExport
import circles.export.EVENTS_ENTRY
import circles.export.ExportBlobRef
import circles.export.ExportVerifier
import circles.event.toDto
import circles.policy.CircleId
import circles.server.domain.Entrance
import circles.server.support.CircleFixture
import circles.server.support.LiveServer
import circles.server.support.TODAY
import circles.server.support.TestServer
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/** `circles-export/1`: export, verify and import through the real server rules (ADR 0030). */
class CircleExchangeTest {
    private val dir: Path = Files.createTempDirectory("circles-exchange")
    private val toClose = mutableListOf<AutoCloseable>()

    @AfterTest
    fun cleanUp() {
        toClose.forEach { it.close() }
        dir.toFile().deleteRecursively()
    }

    private fun <T : AutoCloseable> T.tracked(): T = also { toClose += it }

    private fun source(file: Path) = ZipExportSource(file).tracked()

    private fun exporterOf(server: TestServer) = CircleExporter(server.services.store, server.services.blobs!!, server.crypto)

    private fun importerOf(server: TestServer) = CircleImporter(server.services.sync, server.services.blobs!!, server.crypto)

    private fun pull(server: TestServer, circle: CircleId) = server.services.sync.pull(circle, 0, 1_000).events

    /** The device rows, stewards and epoch the importer must rebuild; compared across hosts. */
    private fun derivedState(server: TestServer): List<List<List<Any?>>> = listOf(
        server.rows("SELECT device_id, member_id, hex(sign_pub), hex(box_pub), added_seq, revoked_seq FROM devices ORDER BY device_id"),
        server.rows("SELECT member_id, added_seq, added_day, removed_seq FROM stewards ORDER BY member_id"),
        server.rows("SELECT id, current_epoch, steward_quorum, steward_inactive_days, steward_fallback_delay_days FROM circles"),
        server.rows("SELECT event_id, author, type, server_seq FROM events ORDER BY server_seq"),
    )

    @Test
    fun anExportVerifiesAndSeedsANewHostWithTheSameLogDevicesAndBlobs() {
        val a = TestServer().tracked()
        val fixture = CircleFixture(a.services, a.crypto)
        val count = fixture.build()
        val file = dir.resolve("circle.zip")
        assertEquals(ExportResult.Done(count, 2), exporterOf(a).export(fixture.circle, file))

        val report = ExportVerifier.verify(source(file), a.crypto)
        assertEquals(emptyList(), report.problems)

        val b = TestServer().tracked()
        val result = assertIs<ImportResult.Done>(importerOf(b).import(source(file)))
        assertEquals(ImportReport(count, 0, 2, 0), result.report)
        assertEquals(pull(a, fixture.circle), pull(b, fixture.circle), "same events with the same sequence numbers")
        assertEquals(derivedState(a), derivedState(b))
        assertContentEquals(fixture.blobA, b.services.blobs!!.get(fixture.circle, fixture.hash(fixture.blobA)))
        assertContentEquals(fixture.blobB, b.services.blobs!!.get(fixture.circle, fixture.hash(fixture.blobB)))
    }

    @Test
    fun existingDevicesLogInOnTheNewHostWithoutReEnrolmentAndResumeFromTheirCursor() {
        val a = TestServer().tracked()
        val fixture = CircleFixture(a.services, a.crypto)
        val count = fixture.build()
        val file = dir.resolve("circle.zip")
        exporterOf(a).export(fixture.circle, file)

        LiveServer(TestServer()).tracked().let { live ->
            assertIs<ImportResult.Done>(importerOf(live.server).import(source(file)))
            runBlocking {
                val token = live.api.login(fixture.bea.identity)
                val resumed = live.api.pull(token, fixture.circle, after = 5).second!!
                assertEquals((6L..count.toLong()).toList(), resumed.events.map { it.serverSeq })
                val founderToken = live.api.login(fixture.founder.identity)
                val push = live.api.push(founderToken, fixture.circle, listOf(fixture.founder.invite(fixture.circle, circles.server.support.newInviteKey(a.crypto), TODAY + 5, 1, TODAY)))
                assertEquals(PushStatus.ACCEPTED, push.second.single().status)
                assertEquals(count + 1L, push.second.single().serverSeq)
            }
        }
    }

    @Test
    fun importingTheSameFileAgainChangesNothing() {
        val a = TestServer().tracked()
        val fixture = CircleFixture(a.services, a.crypto)
        val count = fixture.build()
        val file = dir.resolve("circle.zip")
        exporterOf(a).export(fixture.circle, file)
        val b = TestServer().tracked()
        importerOf(b).import(source(file))
        val before = derivedState(b)
        val again = assertIs<ImportResult.Done>(importerOf(b).import(source(file)))
        assertEquals(ImportReport(0, count, 0, 2), again.report)
        assertEquals(before, derivedState(b))
    }

    @Test
    fun aBiggerExportExtendsAnEarlierImport() {
        val a = TestServer().tracked()
        val fixture = CircleFixture(a.services, a.crypto)
        val count = fixture.build()
        val first = dir.resolve("first.zip")
        exporterOf(a).export(fixture.circle, first)
        val b = TestServer().tracked()
        importerOf(b).import(source(first))
        fixture.addMore(3)
        val second = dir.resolve("second.zip")
        exporterOf(a).export(fixture.circle, second)
        val result = assertIs<ImportResult.Done>(importerOf(b).import(source(second)))
        assertEquals(ImportReport(3, count, 0, 2), result.report)
        assertEquals(pull(a, fixture.circle), pull(b, fixture.circle))
    }

    @Test
    fun anImportIsAcceptedEvenWhenTheHostClosedCircleCreation() {
        val a = TestServer().tracked()
        val fixture = CircleFixture(a.services, a.crypto)
        val count = fixture.build()
        val file = dir.resolve("circle.zip")
        exporterOf(a).export(fixture.circle, file)
        val closed = TestServer(allowCreate = false).tracked()
        val direct = closed.services.sync.submit(listOf(fixture.events.first().toDto()), Entrance.Create).single()
        assertEquals("creation_disabled", direct.reason, "the host really is closed to new circles")
        assertEquals(count, assertIs<ImportResult.Done>(importerOf(closed).import(source(file))).report.eventsAccepted)
    }

    @Test
    fun aMembersReplicaSeedsANewHostJustLikeTheServersExport() {
        val a = TestServer().tracked()
        val fixture = CircleFixture(a.services, a.crypto)
        val count = fixture.build()
        // The host is gone; all that is left is what a member's device pulled and the blobs it downloaded.
        val replica = pull(a, fixture.circle).map { it.envelope }
        val refs = listOf(fixture.blobA, fixture.blobB).map { ExportBlobRef(fixture.hash(it), it.size.toLong()) }
        val built = CircleExport.build(a.crypto, fixture.circle, replica, refs)
        val bytes = mapOf(fixture.hash(fixture.blobA) to fixture.blobA, fixture.hash(fixture.blobB) to fixture.blobB)
        val file = dir.resolve("replica.zip")
        ZipExportWriter.write(file, built) { bytes.getValue(it) }
        val b = TestServer().tracked()
        assertEquals(count, assertIs<ImportResult.Done>(importerOf(b).import(source(file))).report.eventsAccepted)
        assertEquals(derivedState(a), derivedState(b))
    }

    private fun writeZip(file: Path, entries: Map<String, ByteArray>) {
        ZipOutputStream(Files.newOutputStream(file)).use { zip -> entries.forEach { (name, bytes) -> zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry() } }
    }

    private fun entriesOf(file: Path): Map<String, ByteArray> =
        source(file).let { s -> s.entryNames.associateWith { s.read(it, Long.MAX_VALUE)!! } }

    @Test
    fun aTamperedFileIsRefusedAndNothingIsImported() {
        val a = TestServer().tracked()
        val fixture = CircleFixture(a.services, a.crypto)
        fixture.build()
        val file = dir.resolve("circle.zip")
        exporterOf(a).export(fixture.circle, file)
        val entries = entriesOf(file).toMutableMap()
        val flipped = entries.getValue(EVENTS_ENTRY).copyOf().also { it[it.size / 3] = (it[it.size / 3].toInt() xor 1).toByte() }
        val bad = dir.resolve("bad.zip")
        writeZip(bad, entries + (EVENTS_ENTRY to flipped))
        val b = TestServer().tracked()
        val refused = assertIs<ImportResult.Refused>(importerOf(b).import(source(bad)))
        assertTrue(refused.problems.isNotEmpty())
        assertEquals(0, b.rows("SELECT 1 FROM circles").size)
        assertEquals(0, b.rows("SELECT 1 FROM events").size)
    }

    @Test
    fun aForgedEventWithAMatchingHashIsRefusedByVerifyBeforeAnythingIsWritten() {
        val a = TestServer().tracked()
        val fixture = CircleFixture(a.services, a.crypto)
        fixture.build()
        val forged = pull(a, fixture.circle).map { it.envelope }.toMutableList().also { it[2] = it[2].copy(day = it[2].day + 1) }
        val built = CircleExport.build(a.crypto, fixture.circle, forged, emptyList())
        val file = dir.resolve("forged.zip")
        ZipExportWriter.write(file, built) { error("no blobs") }
        val b = TestServer().tracked()
        val refused = assertIs<ImportResult.Refused>(importerOf(b).import(source(file)))
        assertTrue(refused.problems.any { it.contains("refused by the circle's rules") }, refused.problems.toString())
        assertEquals(0, b.rows("SELECT 1 FROM events").size)
    }

    @Test
    fun aDifferentCircleWithTheSameIdStopsTheImportAndIsLeftAlone() {
        val a = TestServer().tracked()
        val fixture = CircleFixture(a.services, a.crypto)
        fixture.build()
        val file = dir.resolve("circle.zip")
        exporterOf(a).export(fixture.circle, file)
        val b = TestServer().tracked()
        val squatter = CircleFixture(b.services, b.crypto)
        val rival = squatter.founder.circleCreate(fixture.circle, TODAY)
        assertEquals(PushStatus.ACCEPTED, b.services.sync.submit(listOf(rival.toDto()), Entrance.Create).single().status)
        val stopped = assertIs<ImportResult.Stopped>(importerOf(b).import(source(file)))
        assertTrue(stopped.problems.single().contains("event 1"), stopped.problems.toString())
        assertEquals(1, b.rows("SELECT 1 FROM events").size, "the existing circle is untouched")
    }

    @Test
    fun exportRefusesAnUnknownCircleAndABrokenBlobStore() {
        val a = TestServer().tracked()
        val fixture = CircleFixture(a.services, a.crypto)
        fixture.build()
        assertEquals(ExportResult.UnknownCircle, exporterOf(a).export(CircleId("ab".repeat(16)), dir.resolve("x.zip")))
        val blobFile = a.blobsDir.resolve(fixture.circle.v).resolve(fixture.hash(fixture.blobA).take(2)).resolve(fixture.hash(fixture.blobA))
        Files.delete(blobFile)
        val failed = assertIs<ExportResult.Failed>(exporterOf(a).export(fixture.circle, dir.resolve("y.zip")))
        assertTrue(failed.problems.single().contains("missing"))
        assertTrue(!dir.resolve("y.zip").exists(), "no half-written file is left")
    }

    @Test
    fun zipEntriesAreReadOnlyUpToTheLimitAndOtherNamesAreVerifyProblems() {
        val file = dir.resolve("odd.zip")
        writeZip(file, mapOf("a.txt" to ByteArray(100) { 1 }))
        val s = source(file)
        assertNull(s.read("a.txt", 10))
        assertEquals(100, s.read("a.txt", 100)!!.size)
        assertNull(s.read("missing", 100))
        assertEquals(100L, s.sizeOf("a.txt"))
        val report = ExportVerifier.verify(s, TestServer().tracked().crypto)
        assertTrue(report.problems.any { it.contains("manifest.json is missing") })
        assertTrue(report.problems.any { it.contains("unexpected entry 'a.txt'") } || report.manifest == null)
    }

    @Test
    fun theBlobEntriesAreNamedByHash() {
        val a = TestServer().tracked()
        val fixture = CircleFixture(a.services, a.crypto)
        fixture.build()
        val file = dir.resolve("circle.zip")
        exporterOf(a).export(fixture.circle, file)
        val names = source(file).entryNames
        assertTrue(names.contains(BLOB_ENTRY_PREFIX + fixture.hash(fixture.blobA)))
        assertTrue(names.contains(BLOB_ENTRY_PREFIX + fixture.hash(fixture.blobB)))
        assertEquals(setOf("manifest.json", EVENTS_ENTRY), names.filterNot { it.startsWith(BLOB_ENTRY_PREFIX) }.toSet())
    }
}
