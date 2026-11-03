package circles.server

import circles.crypto.JvmCrypto
import circles.server.config.ENV_DATA_DIR
import circles.server.data.Database
import circles.server.data.Migrator
import circles.server.support.CircleFixture
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `export`, `import` and `verify` on the command line (ADR 0030). */
class CliExchangeTest {
    private class Run(val code: Int, val out: String, val err: String)

    private val base: Path = Files.createTempDirectory("circles-cli-exchange")

    @AfterTest
    fun cleanUp() {
        base.toFile().deleteRecursively()
    }

    private fun run(dataDir: Path?, vararg args: String): Run {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val env = if (dataDir == null) emptyMap() else mapOf(ENV_DATA_DIR to dataDir.toString())
        val code = Cli(env, PrintStream(out), PrintStream(err), launcher = { _, _ -> }).run(args.toList())
        return Run(code, out.toString(), err.toString())
    }

    /** A host with a full circle in it, ready to export. */
    private fun host(name: String): Pair<Path, CircleFixture> {
        val dir = Files.createDirectories(base.resolve(name))
        val crypto = JvmCrypto()
        val fixture = Database.openFile(dir).use { connection ->
            Migrator(connection).migrate()
            val services = buildServices(connection, crypto, java.time.Clock.systemUTC(), true, blobsDir = dir.resolve("blobs"))
            CircleFixture(services, crypto).also { it.build() }
        }
        return dir to fixture
    }

    private fun migrated(name: String): Path {
        val dir = Files.createDirectories(base.resolve(name))
        assertEquals(EXIT_OK, run(dir, "migrate").code)
        return dir
    }

    @Test
    fun exportThenImportMovesACircleToANewHost() {
        val (from, fixture) = host("from")
        val file = base.resolve("circle.zip")
        val exported = run(from, "export", fixture.circle.v, file.toString())
        assertEquals(EXIT_OK, exported.code, exported.err)
        assertTrue(exported.out.contains("Exported 13 events and 2 blobs"), exported.out)

        val verified = run(null, "verify", file.toString())
        assertEquals(EXIT_OK, verified.code, verified.err)
        assertTrue(verified.out.contains("OK: 13 events and 2 blobs for circle ${fixture.circle.v}"), verified.out)

        val to = migrated("to")
        val imported = run(to, "import", file.toString())
        assertEquals(EXIT_OK, imported.code, imported.err)
        assertTrue(imported.out.contains("Imported 13 new events (0 already there) and 2 new blobs (0 already there)"), imported.out)
        val again = run(to, "import", file.toString())
        assertEquals(EXIT_OK, again.code)
        assertTrue(again.out.contains("Imported 0 new events (13 already there)"), again.out)
    }

    @Test
    fun exportRefusesToOverwriteAnUnknownCircleOrABadId() {
        val (from, fixture) = host("from")
        val file = base.resolve("circle.zip")
        assertEquals(EXIT_OK, run(from, "export", fixture.circle.v, file.toString()).code)
        val overwrite = run(from, "export", fixture.circle.v, file.toString())
        assertEquals(EXIT_FAILURE, overwrite.code)
        assertTrue(overwrite.err.contains("refusing to overwrite"))
        val unknown = run(from, "export", "ab".repeat(16), base.resolve("other.zip").toString())
        assertEquals(EXIT_FAILURE, unknown.code)
        assertTrue(unknown.err.contains("no circle"))
        val bad = run(from, "export", "not-an-id", base.resolve("bad.zip").toString())
        assertEquals(EXIT_USAGE, bad.code)
    }

    @Test
    fun verifyReportsEveryProblemOfAnUnsoundFile() {
        val (from, fixture) = host("from")
        val file = base.resolve("circle.zip")
        run(from, "export", fixture.circle.v, file.toString())
        val bad = base.resolve("bad.zip")
        ZipOutputStream(Files.newOutputStream(bad)).use { zip ->
            zip.putNextEntry(ZipEntry("notes.txt"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
        }
        val result = run(null, "verify", bad.toString())
        assertEquals(EXIT_FAILURE, result.code)
        assertTrue(result.err.contains("problem: manifest.json is missing"), result.err)
        assertTrue(result.err.contains("cannot be imported"))
    }

    @Test
    fun aFileThatIsNotAZipOrNotThereIsAnError() {
        val junk = base.resolve("junk.zip")
        Files.write(junk, "this is not a zip".encodeToByteArray())
        assertEquals(EXIT_FAILURE, run(null, "verify", junk.toString()).code)
        assertTrue(run(null, "verify", junk.toString()).err.contains("not a valid zip"))
        assertTrue(run(null, "verify", base.resolve("missing.zip").toString()).err.contains("no such file"))
        val to = migrated("to")
        assertEquals(EXIT_FAILURE, run(to, "import", junk.toString()).code)
    }

    @Test
    fun importNeedsAMigratedDatabaseAndTheRightNumberOfArguments() {
        val (from, fixture) = host("from")
        val file = base.resolve("circle.zip")
        run(from, "export", fixture.circle.v, file.toString())
        val fresh = Files.createDirectories(base.resolve("fresh"))
        val notMigrated = run(fresh, "import", file.toString())
        assertEquals(EXIT_FAILURE, notMigrated.code)
        assertTrue(notMigrated.err.contains("run 'migrate' first"), notMigrated.err)
        assertEquals(EXIT_USAGE, run(fresh, "import").code)
        assertEquals(EXIT_USAGE, run(fresh, "import", "a", "b").code)
        assertEquals(EXIT_USAGE, run(fresh, "export", "only-one").code)
        assertEquals(EXIT_USAGE, run(null, "verify").code)
    }

    @Test
    fun usageListsTheNewCommands() {
        val out = run(null, "help").out
        listOf("export <circleId> <file>", "import <file>", "verify <file>").forEach { assertTrue(out.contains(it), it) }
    }
}
