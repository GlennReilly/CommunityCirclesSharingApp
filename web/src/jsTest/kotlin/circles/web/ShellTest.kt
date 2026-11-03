package circles.web

import circles.sync.SyncState
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenTest {
    @Test fun fragmentsRoundTrip() {
        for (screen in Screen.entries) assertEquals(screen, Screen.fromFragment(screen.fragment))
    }

    @Test fun unknownOrEmptyFragmentIsHome() {
        assertEquals(Screen.Home, Screen.fromFragment(""))
        assertEquals(Screen.Home, Screen.fromFragment("#/nope"))
        assertEquals(Screen.Home, Screen.fromFragment("#/items/extra"))
    }

    @Test fun navigationOmitsSignIn() {
        assertFalse(Screen.SignIn in Screen.navigation)
        assertEquals(Screen.entries.size - 1, Screen.navigation.size)
    }
}

class ReduceTest {
    private val signedIn = AppState(screen = Screen.Home, signedIn = true)

    @Test fun signedOutUsersAreHeldOnSignIn() {
        val state = reduce(AppState(), AppAction.Navigate(Screen.Items))
        assertEquals(Screen.SignIn, state.screen)
    }

    @Test fun signingInLandsOnHome() {
        val state = reduce(AppState(), AppAction.SignedIn(true))
        assertTrue(state.signedIn)
        assertEquals(Screen.Home, state.screen)
    }

    @Test fun signingOutReturnsToSignIn() {
        assertEquals(Screen.SignIn, reduce(signedIn, AppAction.SignedIn(false)).screen)
    }

    @Test fun signedInUsersNeverLandOnSignIn() {
        assertEquals(Screen.Home, reduce(signedIn, AppAction.Navigate(Screen.SignIn)).screen)
    }

    @Test fun signedInNavigationWorks() {
        assertEquals(Screen.Items, reduce(signedIn, AppAction.Navigate(Screen.Items)).screen)
    }

    @Test fun updatePromptShowsAndClears() {
        val ready = reduce(signedIn, AppAction.UpdateReady)
        assertTrue(ready.updateAvailable)
        assertFalse(reduce(ready, AppAction.UpdateAccepted).updateAvailable)
    }

    @Test fun syncStatusIsStored() {
        assertEquals(SyncState.Syncing, reduce(signedIn, AppAction.SyncChanged(SyncState.Syncing)).sync)
    }

    @Test fun statusSentences() {
        assertEquals("Up to date", describe(SyncState.UpToDate))
        assertEquals("Syncing", describe(SyncState.Syncing))
        assertEquals("Offline", describe(SyncState.Offline(0)))
        assertEquals("Offline, 1 change waiting", describe(SyncState.Offline(1)))
        assertEquals("Offline, 3 changes waiting", describe(SyncState.Offline(3)))
        assertEquals("The server is busy, trying again soon, 2 changes waiting", describe(SyncState.Busy(2, 30)))
        assertEquals("Signed out, sign in again to sync", describe(SyncState.SignedOut))
        assertEquals("The server refused the request (403): HTTP 403, 1 change waiting", describe(SyncState.Problem("The server refused the request (403): HTTP 403", 1)))
    }
}

class ShellViewTest {
    private fun render(state: AppState): String = createHTML().div { shell(state) }

    @Test fun signedOutShowsNoNavigation() {
        val html = render(AppState())
        assertFalse("<nav" in html)
        assertTrue("Join or sign in" in html)
    }

    @Test fun signedInNavigationMarksCurrentScreen() {
        val html = render(AppState(screen = Screen.Items, signedIn = true))
        assertTrue("""aria-label="Main"""" in html)
        assertEquals(1, Regex("""aria-current="page"""").findAll(html).count())
        assertTrue("""href="#/items"""" in html)
        assertFalse("""href="#/sign-in"""" in html)
    }

    @Test fun statusLineIsALiveRegion() {
        val html = render(AppState(sync = SyncState.Offline(2)))
        assertTrue("""role="status"""" in html)
        assertTrue("Offline, 2 changes waiting" in html)
    }

    @Test fun updatePromptOnlyWhenReady() {
        assertFalse(UPDATE_BUTTON_ID in render(AppState()))
        val html = render(AppState(updateAvailable = true))
        assertTrue(UPDATE_BUTTON_ID in html)
        assertTrue("""role="alert"""" in html)
    }

    @Test fun mainLandmarkCanTakeFocus() {
        val html = render(AppState())
        assertTrue("""id="$MAIN_ID"""" in html)
        assertTrue("""tabindex="-1"""" in html)
    }
}
