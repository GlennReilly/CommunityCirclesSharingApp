package circles.server.data

import circles.encoding.ByteString
import circles.policy.CircleId
import circles.server.support.TestServer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Import tokens in the store (ADR 0034): bound to a circle, expiring, single use. */
class ImportTokenStoreTest {
    private val server = TestServer()
    private val store = server.services.store
    private val circle = CircleId("11".repeat(16))
    private val hash = ByteString.of(ByteArray(32) { 7 })

    @AfterTest
    fun close() = server.close()

    private fun put(expires: Long = 1_000) = store.transaction { it.putImportToken(hash, circle, expires) }

    private fun consume(token: ByteString = hash, in_: CircleId = circle, now: Long = 500) = store.transaction { it.consumeImportToken(token, in_, now) }

    @Test
    fun aTokenIsConsumedOnce() {
        put()
        assertTrue(consume())
        assertFalse(consume(), "the second use fails")
        assertEquals(0, server.rows("SELECT 1 FROM import_tokens").size)
    }

    @Test
    fun aTokenForAnotherCircleOrAnUnknownTokenFailsAndKeepsTheRealOne() {
        put()
        assertFalse(consume(in_ = CircleId("22".repeat(16))))
        assertFalse(consume(token = ByteString.of(ByteArray(32) { 8 })))
        assertEquals(1, server.rows("SELECT 1 FROM import_tokens").size)
        assertTrue(consume())
    }

    @Test
    fun anExpiredTokenFailsAndIsSweptByDeleteExpired() {
        put(expires = 1_000)
        assertFalse(consume(now = 1_000), "valid only strictly before the expiry")
        assertFalse(consume(now = 5_000))
        store.transaction { it.deleteExpired(2_000) }
        assertEquals(0, server.rows("SELECT 1 FROM import_tokens").size)
    }
}
