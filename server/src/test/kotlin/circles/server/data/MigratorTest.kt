package circles.server.data

import java.nio.file.Files
import java.sql.Connection
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MigratorTest {
    private val connection: Connection = Database.openMemory()
    private val v1 = Migration(1, "V1__init.sql")
    private val v2 = Migration(2, "V2__test_table.sql")
    private val v3 = Migration(3, "V3__broken.sql").copy(resource = "V3__test_broken.sql")

    @AfterTest
    fun close() = connection.close()

    private fun tables(): Set<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rs -> buildSet { while (rs.next()) add(rs.getString(1)) } }
        }

    @Test
    fun aNewDatabaseIsEmptyAndMigratesToTheLatestVersion() {
        val migrator = Migrator(connection)
        assertEquals(SchemaState.Empty, migrator.state())
        assertEquals(0, migrator.currentVersion())
        assertEquals(listOf(1, 2, 3, 4, 5), migrator.migrate())
        assertEquals(SchemaState.Current, migrator.state())
        assertEquals(5, migrator.currentVersion())
    }

    @Test
    fun v1CreatesTheSpecTablesWithoutUniqueDeviceCounter() {
        Migrator(connection).migrate()
        val expected = setOf("schema_version", "circles", "devices", "events", "stewards", "invites", "blobs", "sessions", "challenges")
        assertTrue(tables().containsAll(expected), "missing ${expected - tables()}")
        // ADR 0011: (device, ctr) has an index but it is not unique.
        connection.createStatement().use { statement ->
            statement.executeQuery("PRAGMA index_list('events')").use { rs ->
                val uniqueByName = buildMap { while (rs.next()) put(rs.getString("name"), rs.getInt("unique") == 1) }
                assertEquals(false, uniqueByName["events_by_device_ctr"])
            }
        }
    }

    @Test
    fun migratingAgainDoesNothing() {
        val migrator = Migrator(connection)
        migrator.migrate()
        assertEquals(emptyList(), migrator.migrate())
        assertEquals(5, migrator.currentVersion())
    }

    @Test
    fun anOlderDatabaseIsBehindAndGetsOnlyTheNewMigrations() {
        Migrator(connection, listOf(v1)).migrate()
        val migrator = Migrator(connection, listOf(v1, v2))
        assertEquals(SchemaState.Behind(1, 2), migrator.state())
        assertEquals(listOf(2), migrator.migrate())
        assertTrue("t2" in tables())
        assertEquals(SchemaState.Current, migrator.state())
    }

    @Test
    fun aDatabaseFromANewerBuildIsAhead() {
        Migrator(connection, listOf(v1, v2)).migrate()
        assertEquals(SchemaState.Ahead(2, 1), Migrator(connection, listOf(v1)).state())
    }

    @Test
    fun aFailingMigrationRollsBackCompletelyAndKeepsTheVersion() {
        Migrator(connection, listOf(v1, v2)).migrate()
        val migrator = Migrator(connection, listOf(v1, v2, v3))
        assertFailsWith<java.sql.SQLException> { migrator.migrate() }
        assertFalse("t3" in tables())
        assertEquals(2, migrator.currentVersion())
        assertTrue(connection.autoCommit)
    }

    @Test
    fun aMissingMigrationFileIsAnError() {
        val migrator = Migrator(connection, listOf(Migration(1, "V1__missing.sql")))
        assertFailsWith<IllegalStateException> { migrator.migrate() }
    }

    @Test
    fun splitStatementsDropsCommentsAndBlankPieces() {
        val sql = "-- header\nCREATE TABLE a (x INTEGER); -- trailing\n\n-- note\nCREATE TABLE b (y TEXT);\n"
        val statements = Migrator.splitStatements(sql)
        assertEquals(listOf("CREATE TABLE a (x INTEGER)", "CREATE TABLE b (y TEXT)"), statements)
        assertEquals(emptyList(), Migrator.splitStatements("-- nothing\n\n"))
    }

    @Test
    fun aFileDatabaseIsCreatedInANewDirectoryInWalMode() {
        val dir = Files.createTempDirectory("circles-db").resolve("nested/data")
        Database.openFile(dir).use { file ->
            assertTrue(dir.resolve(Database.FILE_NAME).exists())
            file.createStatement().use { it.executeQuery("PRAGMA journal_mode").use { rs -> rs.next(); assertEquals("wal", rs.getString(1)) } }
            assertEquals(listOf(1, 2, 3, 4, 5), Migrator(file).migrate())
        }
        Database.openFile(dir).use { assertEquals(SchemaState.Current, Migrator(it).state()) }
    }
}
