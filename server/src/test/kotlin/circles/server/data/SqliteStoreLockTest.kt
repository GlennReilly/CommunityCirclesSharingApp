package circles.server.data

import circles.encoding.ByteString
import circles.event.DeviceId
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The store must not hold SQLite's write lock between transactions: a running server shares its database file with
 * `export`, `import-token`, `backup` and the admin tools, and found out the hard way (load test) that it did.
 */
class SqliteStoreLockTest {
    private val dir = Files.createTempDirectory("circles-lock")
    private val serverConnection = Database.openFile(dir).also { Migrator(it).migrate() }
    private val store = SqliteStore(serverConnection)
    private val device = DeviceId("ab".repeat(16))

    @AfterTest
    fun cleanUp() {
        serverConnection.close()
        dir.toFile().deleteRecursively()
    }

    private fun rows(sql: String): Int =
        Database.openFile(dir).use { c -> c.createStatement().use { s -> s.executeQuery(sql).use { rs -> var n = 0; while (rs.next()) n++; n } } }

    @Test
    fun anotherProcessCanWriteWhileTheStoreIsIdle() {
        store.transaction { it.putChallenge(ByteString.of(byteArrayOf(1)), device, 100) }
        val started = System.nanoTime()
        Database.openFile(dir).use { other ->
            other.createStatement().use { it.executeUpdate("INSERT INTO challenges (nonce, device_id, expires_at) VALUES (x'02', 'other', 1)") }
        }
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue(millis < 2_000, "the second connection waited $millis ms: the store is holding the write lock")
        assertEquals(2, rows("SELECT 1 FROM challenges"))
    }

    @Test
    fun theStoreStillWorksAfterAnotherConnectionWroteAndAfterAFailedTransaction() {
        store.transaction { it.putChallenge(ByteString.of(byteArrayOf(1)), device, 100) }
        Database.openFile(dir).use { other -> other.createStatement().use { it.executeUpdate("INSERT INTO challenges (nonce, device_id, expires_at) VALUES (x'02', 'other', 1)") } }
        assertFailsWith<IllegalStateException> {
            store.transaction {
                it.putChallenge(ByteString.of(byteArrayOf(3)), device, 100)
                error("boom")
            }
        }
        assertEquals(2, rows("SELECT 1 FROM challenges"), "the failed transaction was rolled back")
        store.transaction { it.putChallenge(ByteString.of(byteArrayOf(4)), device, 100) }
        assertEquals(3, rows("SELECT 1 FROM challenges"))
    }

    @Test
    fun aTransactionIsAtomicAndVisibleToOthersOnlyOnceCommitted() {
        val seenInside = mutableListOf<Int>()
        store.transaction {
            it.putChallenge(ByteString.of(byteArrayOf(9)), device, 100)
            seenInside += rows("SELECT 1 FROM challenges") // a reader sees the last committed state (WAL), not this write
        }
        assertEquals(listOf(0), seenInside)
        assertEquals(1, rows("SELECT 1 FROM challenges"))
    }
}
