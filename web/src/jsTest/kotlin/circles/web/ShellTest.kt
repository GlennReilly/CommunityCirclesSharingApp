package circles.web

import circles.app.AppModel
import circles.app.HomeData
import circles.app.InviteLink
import circles.app.WaitingDevice
import circles.encoding.ByteString
import circles.event.DeviceId
import circles.event.EventId
import circles.policy.CircleId
import circles.policy.MemberId
import circles.sync.SyncState
import kotlinx.html.div
import kotlinx.html.stream.createHTML
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val circle = CircleId("c".repeat(32))
private val me = MemberId("a".repeat(32))
private val bea = MemberId("b".repeat(32))
private val beaDevice = DeviceId("d".repeat(32))

private fun home(
    steward: Boolean = true,
    canRead: Boolean = true,
    members: Int = 2,
    waiting: List<WaitingDevice> = emptyList(),
    refused: Int = 0,
) = HomeData(circle, me, steward, canRead, members, waiting, changesWaiting = 0, refusedChanges = refused)

private val link = InviteLink(circle, EventId("e".repeat(64)), ByteString.of(ByteArray(32) { it.toByte() }))
private fun open(home: HomeData = home()) = AppState(screen = Screen.Home, model = AppModel.Open(home))

class ScreenTest {
    @Test fun fragmentsRoundTrip() {
        for (screen in Screen.entries) assertEquals(screen, Screen.fromFragment(screen.fragment))
    }

    @Test fun unknownOrEmptyFragmentIsHome() {
        assertEquals(Screen.Home, Screen.fromFragment(""))
        assertEquals(Screen.Home, Screen.fromFragment("#/nope"))
        assertEquals(Screen.Home, Screen.fromFragment("#/items/extra"))
    }

    @Test fun navigationOmitsEveryPageThatComesBeforeSignIn() {
        for (screen in Screen.beforeSignIn) assertFalse(screen in Screen.navigation, screen.name)
        assertEquals(Screen.entries.size - Screen.beforeSignIn.size, Screen.navigation.size)
        assertEquals(setOf(Screen.Welcome, Screen.StartCircle, Screen.JoinCircle), Screen.newcomer)
    }
}

class ReduceTest {
    @Test fun aBrowserWithNoIdentityMayOnlySeeTheNewcomerPages() {
        val fresh = AppState(model = AppModel.NoIdentity)
        assertEquals(Screen.Welcome, reduce(fresh, AppAction.Navigate(Screen.Items)).screen)
        assertEquals(Screen.Welcome, reduce(fresh, AppAction.Navigate(Screen.SignIn)).screen)
        assertEquals(Screen.StartCircle, reduce(fresh, AppAction.Navigate(Screen.StartCircle)).screen)
        assertEquals(Screen.JoinCircle, reduce(fresh, AppAction.Navigate(Screen.JoinCircle)).screen)
    }

    @Test fun aLockedBrowserSeesOnlySignIn() {
        val locked = AppState(model = AppModel.Locked(circle))
        for (screen in Screen.entries) assertEquals(Screen.SignIn, reduce(locked, AppAction.Navigate(screen)).screen, screen.name)
    }

    @Test fun anOpenCircleNeverShowsTheEarlierPages() {
        for (screen in Screen.beforeSignIn) assertEquals(Screen.Home, reduce(open(), AppAction.Navigate(screen)).screen, screen.name)
    }

    @Test fun openingTheCircleLandsOnHome() {
        val state = reduce(AppState(model = AppModel.NoIdentity, screen = Screen.Welcome), AppAction.ModelChanged(AppModel.Open(home())))
        assertTrue(state.signedIn)
        assertEquals(Screen.Home, state.screen)
    }

    @Test fun lockingReturnsToSignInAndForgetsTheShownInvite() {
        val state = reduce(open().copy(newInvite = "https://x/#/join/..."), AppAction.ModelChanged(AppModel.Locked(circle)))
        assertEquals(Screen.SignIn, state.screen)
        assertNull(state.newInvite)
        assertFalse(state.signedIn)
    }

    @Test fun signedInNavigationWorks() {
        assertEquals(Screen.Items, reduce(open(), AppAction.Navigate(Screen.Items)).screen)
    }

    @Test fun anInviteInTheAddressIsKeptOnlyWhileThereIsNoIdentity() {
        val seen = reduce(AppState(model = AppModel.NoIdentity), AppAction.InviteSeen(link))
        assertEquals(link, seen.pendingInvite)
        assertEquals(Screen.JoinCircle, seen.screen, "a link in the address goes straight to the join page")
        // It survives the page that was still loading when the link was read.
        val early = reduce(AppState(), AppAction.InviteSeen(link))
        assertEquals(Screen.JoinCircle, reduce(early, AppAction.ModelChanged(AppModel.NoIdentity)).screen)
        assertNull(reduce(seen, AppAction.ModelChanged(AppModel.Locked(null))).pendingInvite)
        assertEquals(link, reduce(seen, AppAction.ModelChanged(AppModel.NoIdentity)).pendingInvite)
        // The page reads the link before the app reports its first model, which is Loading.
        assertEquals(link, reduce(seen, AppAction.ModelChanged(AppModel.Loading)).pendingInvite)
    }

    @Test fun noticesAndMadeInvitesAreStoredAndCleared() {
        val state = reduce(open(), AppAction.Notice("Link copied."))
        assertEquals("Link copied.", state.notice)
        assertNull(reduce(state, AppAction.Notice(null)).notice)
        assertEquals("https://x", reduce(open(), AppAction.InviteMade("https://x")).newInvite)
    }

    @Test fun updatePromptShowsAndClears() {
        val ready = reduce(open(), AppAction.UpdateReady)
        assertTrue(ready.updateAvailable)
        assertFalse(reduce(ready, AppAction.UpdateAccepted).updateAvailable)
    }

    @Test fun syncStatusIsStored() {
        assertEquals(SyncState.Syncing, reduce(open(), AppAction.SyncChanged(SyncState.Syncing)).sync)
    }

    @Test fun anUnchangedActionGivesAnEqualState() {
        assertEquals(open(), reduce(open(), AppAction.Notice(null)))
        assertNotEquals(open(), reduce(open(), AppAction.Notice("x")))
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

    @Test fun loadingShowsAMessageAndNoNavigation() {
        val html = render(AppState())
        assertTrue("Opening your circle" in html)
        assertFalse("<nav" in html)
    }

    private fun newcomer(screen: Screen, invite: InviteLink? = null) = render(AppState(screen = screen, model = AppModel.NoIdentity, pendingInvite = invite))

    @Test fun theWelcomePageOffersTwoChoicesAndNoForm() {
        val html = newcomer(Screen.Welcome)
        assertTrue("""href="${Screen.StartCircle.fragment}"""" in html)
        assertTrue("""href="${Screen.JoinCircle.fragment}"""" in html)
        assertFalse("<form" in html)
        assertFalse("<nav" in html)
        assertFalse("<button" in html, "no submit button belongs on the welcome page")
    }

    @Test fun startingACircleHasItsOwnPageWithOnlyThePassphraseForm() {
        val html = newcomer(Screen.StartCircle)
        assertTrue("""id="$FORM_START"""" in html)
        assertFalse("""id="$FORM_LINK"""" in html)
        assertFalse("""id="$FORM_JOIN"""" in html)
        assertTrue("Start a circle" in html)
        assertTrue("""href="${Screen.Welcome.fragment}"""" in html)
    }

    @Test fun joiningAsksForTheLinkFirstAndThenForAPassphrase() {
        val first = newcomer(Screen.JoinCircle)
        assertTrue("""id="$FORM_LINK"""" in first)
        assertFalse("""id="$FORM_JOIN"""" in first)
        assertFalse("Start a circle" in first, "the join page has no start button")
        val second = newcomer(Screen.JoinCircle, link)
        assertTrue("""id="$FORM_JOIN"""" in second)
        assertFalse("""id="$FORM_LINK"""" in second)
        assertFalse("Start a circle" in second)
    }

    @Test fun theSignInPageHasNoStartOrJoinOption() {
        val html = render(AppState(screen = Screen.SignIn, model = AppModel.Locked(circle)))
        assertFalse("Start a circle" in html)
        assertFalse("Join a circle" in html)
        assertFalse(FORM_START in html)
        assertFalse(FORM_JOIN in html)
    }

    @Test fun everyTextEntryLabelEndsWithAColonAndASpace() {
        val pages = listOf(
            newcomer(Screen.StartCircle), newcomer(Screen.JoinCircle), newcomer(Screen.JoinCircle, link),
            render(AppState(screen = Screen.SignIn, model = AppModel.Locked(circle))),
            render(open().copy(newInvite = "https://x")),
        )
        var labels = 0
        for (html in pages) {
            for (match in Regex("<label[^>]*>([\\s\\S]*?)</label>").findAll(html)) {
                labels++
                assertTrue(match.groupValues[1].endsWith(": "), "label text was '${match.groupValues[1]}'")
            }
        }
        assertEquals(5, labels)
    }

    @Test fun everyPassphraseButtonStartsHiddenUntilThePassphraseIsLongEnough() {
        val pages = listOf(
            newcomer(Screen.StartCircle), newcomer(Screen.JoinCircle, link),
            render(AppState(screen = Screen.SignIn, model = AppModel.Locked(circle))),
        )
        for (html in pages) {
            val button = Regex("<button[^>]*id=\"$SUBMIT_ID\"[^>]*>").find(html)?.value ?: error("no submit button in $html")
            assertTrue("hidden" in button, button)
        }
    }

    @Test fun theLinkFormButtonIsNotAPassphraseButtonAndShowsAtOnce() {
        val html = newcomer(Screen.JoinCircle)
        assertFalse("""id="$SUBMIT_ID"""" in html)
        assertTrue("Continue" in html)
    }

    @Test fun passphraseFieldsAreLabelledMinLengthAndNeverAutofilledAsAnotherSite() {
        val join = newcomer(Screen.JoinCircle, link)
        assertTrue("""for="$FIELD_PASSPHRASE"""" in join)
        assertTrue("""type="password"""" in join)
        assertTrue("""minlength="12"""" in join)
        assertTrue("""autocomplete="new-password"""" in join)
        assertTrue("""aria-describedby="passphrase-help"""" in join)
        assertTrue("cannot be recovered if you forget it" in join)
        val unlock = render(AppState(screen = Screen.SignIn, model = AppModel.Locked(circle)))
        assertTrue("""autocomplete="current-password"""" in unlock)
    }

    @Test fun aLockedBrowserShowsOnlyUnlockAndForget() {
        val html = render(AppState(screen = Screen.SignIn, model = AppModel.Locked(circle)))
        assertTrue("""id="$FORM_UNLOCK"""" in html)
        assertTrue("""$ATTR_ACTION="$ACTION_ERASE"""" in html)
        assertFalse("<nav" in html)
        assertFalse("""$ATTR_ACTION="$ACTION_INVITE"""" in html)
    }

    @Test fun signedInNavigationMarksCurrentScreen() {
        val html = render(open().copy(screen = Screen.Items))
        assertTrue("""aria-label="Main"""" in html)
        assertEquals(1, Regex("""aria-current="page"""").findAll(html).count())
        assertTrue("""href="#/items"""" in html)
        assertFalse("""href="#/sign-in"""" in html)
        assertTrue("This screen is not built yet." in html)
    }

    @Test fun homeSaysWhenYouAreNotLetInYet() {
        assertTrue("A steward needs to let you in" in render(open(home(steward = false, canRead = false))))
        assertFalse("A steward needs to let you in" in render(open(home(canRead = true))))
    }

    @Test fun homeCountsMembers() {
        assertTrue("2 members" in render(open(home(members = 2))))
        assertTrue("1 member<" in render(open(home(members = 1))))
    }

    @Test fun homeOffersAnInviteAndShowsTheMadeLinkReadOnly() {
        val html = render(open().copy(newInvite = "https://circles.example/#/join/x"))
        assertTrue("""$ATTR_ACTION="$ACTION_INVITE"""" in html)
        assertTrue("""value="https://circles.example/#/join/x"""" in html)
        assertTrue("readonly" in html)
        assertTrue("""$ATTR_ACTION="$ACTION_COPY"""" in html)
        assertFalse("""$ATTR_ACTION="$ACTION_COPY"""" in render(open()))
    }

    @Test fun onlyStewardsSeeWhoIsWaitingAndCanLetThemIn() {
        val waiting = listOf(WaitingDevice(beaDevice, bea))
        val steward = render(open(home(steward = true, waiting = waiting)))
        assertTrue("""$ATTR_ACTION="$ACTION_ADMIT"""" in steward)
        assertTrue("""$ATTR_DEVICE="${beaDevice.v}"""" in steward)
        assertTrue("Member bbbbbbbb" in steward)
        assertFalse("""$ATTR_ACTION="$ACTION_ADMIT"""" in render(open(home(steward = false, waiting = waiting))))
        assertFalse("Waiting to be let in" in render(open(home(steward = true))))
    }

    @Test fun refusedChangesAreReported() {
        assertTrue("2 of your changes could not be sent" in render(open(home(refused = 2))))
        assertFalse("could not be sent" in render(open(home(refused = 0))))
    }

    @Test fun homeCanLockTheSessionAndSyncNow() {
        val html = render(open())
        assertTrue("""$ATTR_ACTION="$ACTION_LOCK"""" in html)
        assertTrue("""$ATTR_ACTION="$ACTION_SYNC"""" in html)
    }

    @Test fun statusLineIsALiveRegion() {
        val html = render(AppState(sync = SyncState.Offline(2)))
        assertTrue("""id="sync-status"""" in html)
        assertTrue("""role="status"""" in html)
        assertTrue("Offline, 2 changes waiting" in html)
    }

    @Test fun noticesAreAnnouncedAndCanBeDismissed() {
        val quiet = render(AppState())
        assertTrue("""id="$NOTICE_ID"""" in quiet)
        assertFalse("""$ATTR_ACTION="$ACTION_DISMISS"""" in quiet)
        val loud = render(AppState(notice = "That passphrase did not work. Try again."))
        assertTrue("That passphrase did not work" in loud)
        assertTrue("""$ATTR_ACTION="$ACTION_DISMISS"""" in loud)
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

    @Test fun nothingSecretIsPutInTheMarkupExceptTheInviteTheUserAskedFor() {
        val html = newcomer(Screen.JoinCircle, link)
        assertFalse(link.secretSeed.toBase64Url() in html)
    }
}
