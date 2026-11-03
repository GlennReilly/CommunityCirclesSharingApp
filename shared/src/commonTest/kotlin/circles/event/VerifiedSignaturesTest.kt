package circles.event

import circles.encoding.ByteString
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VerifiedSignaturesTest {
    private val id = EventId("a".repeat(64))
    private val key = ByteString.of(ByteArray(32) { 1 })
    private val other = ByteString.of(ByteArray(32) { 2 })

    @Test fun noneKnowsNothing() = assertFalse(VerifiedSignatures.NONE.isVerified(id, key))

    @Test fun aRecordedCheckCountsOnlyForTheSameKey() {
        val known = VerifiedSignatures.of(mapOf(id to key))
        assertTrue(known.isVerified(id, key))
        assertFalse(known.isVerified(id, other))
        assertFalse(known.isVerified(EventId("b".repeat(64)), key))
    }
}
