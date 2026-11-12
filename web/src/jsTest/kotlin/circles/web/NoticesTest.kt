package circles.web

import circles.app.HomeData
import circles.app.NameSummary
import circles.policy.CircleId
import circles.policy.MemberId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NoticesTest {
    private val circle = CircleId("c".repeat(32))
    private val alice = MemberId("a".repeat(32))
    private val bea = MemberId("b".repeat(32))

    private fun home(names: Map<MemberId, NameSummary>) = HomeData(circle, alice, true, true, 2, emptyList(), changesWaiting = 0, refusedChanges = 0, names = names)

    @Test fun aNoticeNamesThePersonAndNeverSaysThey() {
        val all = listOf(Notices.letIn("Bea Jones"), Notices.letInFailed("Bea Jones"), Notices.vouched("Bea Jones"), Notices.vouchRefused("Bea Jones"), Notices.memberRemoved("Bea Jones"))
        for (text in all) {
            assertTrue("Bea Jones" in text, text)
            assertFalse(Regex("""^(They|Them|It)\b""").containsMatchIn(text), text)
        }
        assertEquals("Bea Jones has been let in. The circle opens for their device once it syncs.", Notices.letIn("Bea Jones"))
    }

    @Test fun theNameComesFromTheDisplayNamesAndFallsBackToAPlainPhrase() {
        val named = home(mapOf(bea to NameSummary("Bea Jones", confirmed = false)))
        assertEquals("Bea Jones", Notices.nameFor(named, bea, Notices.NEW_MEMBER))
        assertEquals("the new member", Notices.nameFor(named, alice, Notices.NEW_MEMBER))
        assertEquals("that member", Notices.nameFor(null, bea, Notices.THAT_MEMBER))
        assertEquals("the new member", Notices.nameFor(named, null, Notices.NEW_MEMBER))
        assertEquals("the new member", Notices.nameFor(home(mapOf(bea to NameSummary("  ", confirmed = false))), bea, Notices.NEW_MEMBER))
    }
}
