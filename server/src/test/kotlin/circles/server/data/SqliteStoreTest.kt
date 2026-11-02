package circles.server.data

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SqliteStoreTest {
    private val connection = Database.openMemory().also { Migrator(it).migrate() }
    private val store = SqliteStore(connection)

    @AfterTest
    fun close() = connection.close()

    @Test
    fun aStoreOverAMigratedDatabaseIsReady() {
        assertTrue(store.isReady())
    }

    @Test
    fun aClosedDatabaseIsNotReady() {
        connection.close()
        assertEquals(false, store.isReady())
    }

    @Test
    fun anExceptionRollsTheWholeTransactionBack() {
        val circle = circles.policy.CircleId("aa".repeat(16))
        assertFailsWith<IllegalStateException> {
            store.transaction { tx ->
                tx.apply(circles.server.domain.Effect.CreateCircle(circle, 1), 1)
                error("boom")
            }
        }
        assertEquals(false, store.transaction { it.circleExists(circle) })
    }

    @Test
    fun committedWorkIsVisibleToTheNextTransaction() {
        val circle = circles.policy.CircleId("bb".repeat(16))
        store.transaction { it.apply(circles.server.domain.Effect.CreateCircle(circle, 1), 1) }
        assertEquals(true, store.transaction { it.circleExists(circle) })
    }
}
