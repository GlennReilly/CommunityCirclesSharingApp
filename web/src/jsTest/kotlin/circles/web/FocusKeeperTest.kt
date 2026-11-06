package circles.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class FocusKeeperTest {
    private fun c(id: String = "", name: String = "", action: String = "", tag: String = "BUTTON", label: String = "") = FocusCandidate(id, name, action, tag, label)

    @Test fun keysAreUniqueEvenForIdenticalControls() {
        val keys = focusKeys(listOf(c(action = "remove", label = "Remove"), c(action = "remove", label = "Remove")))
        assertEquals(2, keys.toSet().size)
    }

    @Test fun idBeatsEverythingElse() {
        assertEquals("id:sync#0", focusKeys(listOf(c(id = "sync", action = "x", label = "Sync now")))[0])
    }

    @Test fun focusReturnsToTheSameControlAfterARebuild() {
        val before = listOf(c(id = "a"), c(name = "passphrase", tag = "INPUT"), c(action = "go", label = "Go"))
        val after = listOf(c(id = "notice-close"), c(id = "a"), c(name = "passphrase", tag = "INPUT"), c(action = "go", label = "Go"))
        assertEquals(2, focusTarget(focusKeys(before)[1], after))
    }

    @Test fun repeatedControlsKeepTheirOrder() {
        val rows = listOf(c(action = "remove", label = "Remove"), c(action = "remove", label = "Remove"))
        assertEquals(1, focusTarget(focusKeys(rows)[1], rows))
    }

    @Test fun nothingIsRestoredWhenTheControlIsGoneOrNothingWasFocused() {
        assertNull(focusTarget(focusKeys(listOf(c(id = "gone")))[0], listOf(c(id = "other"))))
        assertNull(focusTarget(null, listOf(c(id = "a"))))
    }

    @Test fun differentLabelsGiveDifferentKeys() {
        assertNotEquals(focusKeys(listOf(c(action = "a", label = "One")))[0], focusKeys(listOf(c(action = "a", label = "Two")))[0])
    }
}
