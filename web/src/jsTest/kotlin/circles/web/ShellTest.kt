package circles.web

import circles.app.AppModel
import circles.app.HomeData
import circles.app.InviteLink
import circles.app.LoanRole
import circles.app.MemberSummary
import circles.app.LoanSummary
import circles.event.LoanId
import circles.event.LoanStatus
import circles.event.ReturnOutcome
import circles.app.AccessSummary
import circles.app.ItemSummary
import circles.app.StandingSummary
import circles.policy.Tier
import circles.app.WaitingDevice
import circles.encoding.ByteString
import circles.event.DeviceId
import circles.event.EventId
import circles.policy.CircleId
import circles.policy.ItemClass
import circles.policy.ItemId
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
    items: List<ItemSummary> = emptyList(),
    loans: List<LoanSummary> = emptyList(),
    people: List<MemberSummary> = emptyList(),
) = HomeData(circle, me, steward, canRead, members, waiting, changesWaiting = 0, refusedChanges = refused, items = items, loans = loans, today = 20_759, people = people)

private val ladder = ItemSummary(ItemId("1".repeat(32)), "Ladder", ItemClass.EVERYDAY, me, ownedByMe = true, description = "Garage at the back")
private val drill = ItemSummary(ItemId("2".repeat(32)), "Drill", ItemClass.VALUABLE, bea, ownedByMe = false, description = null, access = AccessSummary(true, emptyList(), emptyList()))
private val locked = drill.copy(
    item = ItemId("3".repeat(32)), name = "Trailer", itemClass = ItemClass.HIGH_VALUE,
    access = AccessSummary(false, listOf("Trailer needs TRUSTED; you are NEWCOMER."), listOf("Or get 2 neighbours to vouch for you.")),
)

private fun loan(
    id: String, role: LoanRole, status: LoanStatus, mine: Boolean = false, theirs: Boolean = false, outcome: ReturnOutcome? = null,
    description: String? = null, note: String? = null,
) = LoanSummary(LoanId(id.repeat(32)), ladder.item, "Ladder", role, bea, 20_760, 20_762, status, mine, theirs, outcome, description, note)

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
        for (screen in Screen.subPages) assertFalse(screen in Screen.navigation, screen.name)
        assertEquals(Screen.entries.size - Screen.beforeSignIn.size - Screen.subPages.size, Screen.navigation.size)
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

    @Test fun changingAnItemRemembersWhichOneAndGoesToItsPage() {
        val state = reduce(open(), AppAction.EditItem(ladder.item))
        assertEquals(ladder.item, state.editing)
        assertEquals(Screen.EditItem, state.screen)
        assertEquals(Screen.Items, reduce(open(), AppAction.Navigate(Screen.EditItem)).screen, "no item chosen, so back to the list")
        assertEquals(Screen.EditItem, reduce(state, AppAction.Navigate(Screen.EditItem)).screen)
        assertEquals(Screen.AddItem, reduce(open(), AppAction.Navigate(Screen.AddItem)).screen)
        assertEquals(AppState(model = AppModel.NoIdentity), reduce(AppState(model = AppModel.NoIdentity), AppAction.EditItem(ladder.item)))
    }

    @Test fun askingToBorrowRemembersTheItemAndGoesToItsPage() {
        val state = reduce(open(), AppAction.BorrowItem(drill.item))
        assertEquals(drill.item, state.borrowing)
        assertEquals(Screen.RequestLoan, state.screen)
        assertEquals(Screen.Items, reduce(open(), AppAction.Navigate(Screen.RequestLoan)).screen, "no item chosen, so back to the list")
        assertEquals(Screen.RequestLoan, reduce(state, AppAction.Navigate(Screen.RequestLoan)).screen)
        assertFalse(Screen.RequestLoan in Screen.navigation)
        assertEquals(AppState(model = AppModel.Locked(circle)), reduce(AppState(model = AppModel.Locked(circle)), AppAction.BorrowItem(drill.item)))
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
            render(open().copy(screen = Screen.Members, newInvite = "https://x")),
            render(open().copy(screen = Screen.AddItem)),
            render(open(home(items = listOf(drill))).copy(screen = Screen.RequestLoan, borrowing = drill.item)),
            settings(),
            borrow(loan("d", LoanRole.BORROWER, LoanStatus.ACTIVE)),
        )
        var labels = 0
        for (html in pages) {
            for (match in Regex("<label[^>]*>([\\s\\S]*?)</label>").findAll(html)) {
                labels++
                assertTrue(match.groupValues[1].endsWith(": "), "label text was '${match.groupValues[1]}'")
            }
        }
        assertEquals(8 + 3 + 1 + 3, labels)
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

    @Test fun theLinkFormButtonAlsoWaitsForSomeInput() {
        val html = newcomer(Screen.JoinCircle)
        val button = Regex("<button[^>]*id=\"$SUBMIT_ID\"[^>]*>").find(html)?.value ?: error("no button")
        assertTrue("hidden" in button)
        assertTrue("""$ATTR_MIN="1"""" in html)
        assertTrue("Continue" in html)
    }

    @Test fun theMinimumLengthOfEveryRequiredFieldIsMarked() {
        assertTrue("""$ATTR_MIN="12"""" in newcomer(Screen.StartCircle))
        assertTrue("""$ATTR_MIN="1"""" in render(open().copy(screen = Screen.AddItem)))
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
        val html = render(open().copy(screen = Screen.Skills))
        assertTrue("""aria-label="Main"""" in html)
        assertEquals(1, Regex("""aria-current="page"""").findAll(html).count())
        assertTrue("""href="#/skills"""" in html)
        assertFalse("""href="#/sign-in"""" in html)
        assertTrue("This screen is not built yet." in html)
    }

    @Test fun homeSaysWhenYouAreNotLetInYet() {
        assertTrue("A steward needs to let you in" in render(open(home(steward = false, canRead = false))))
        assertFalse("A steward needs to let you in" in render(open(home(canRead = true))))
    }

    @Test fun homeSummarisesAndLinksToThePagesThatDoTheWork() {
        val html = render(open(home(members = 2, items = listOf(ladder, drill), loans = listOf(loan("a", LoanRole.OWNER, LoanStatus.REQUESTED)), waiting = listOf(WaitingDevice(beaDevice, bea)))))
        assertTrue(Regex("""<a href="#/members">2 members</a>""").containsMatchIn(html))
        assertTrue(Regex("""<a href="#/items">2 items shared</a>""").containsMatchIn(html))
        assertTrue(Regex("""<a href="#/borrow">1 request waiting for your answer</a>""").containsMatchIn(html))
        assertTrue(Regex("""<a href="#/members">1 person waiting to be let in</a>""").containsMatchIn(html))
    }

    @Test fun homeCountsOneMemberInTheSingular() {
        assertTrue(">1 member</a>" in render(open(home(members = 1))))
    }

    @Test fun homeHasNoActionsOfItsOwn() {
        val html = render(open(home(waiting = listOf(WaitingDevice(beaDevice, bea)))).copy(newInvite = "https://x"))
        for (action in listOf(ACTION_INVITE, ACTION_ADMIT, ACTION_COPY)) assertFalse("""$ATTR_ACTION="$action"""" in html, action)
    }

    @Test fun homeHidesTheWaitingLineFromPeopleWhoAreNotStewardsAndTheItemsLineBeforeTheyCanRead() {
        val html = render(open(home(steward = false, canRead = false, waiting = listOf(WaitingDevice(beaDevice, bea)))))
        assertFalse("waiting to be let in" in html)
        assertFalse("items shared" in html)
    }

    private fun members(home: HomeData, newInvite: String? = null) = render(open(home).copy(screen = Screen.Members, newInvite = newInvite))

    private val aliceSummary = MemberSummary(me, isMe = true, isSteward = true, devices = 2, fingerprint = "ab12 cd34 ef56 7890 1234")
    private val beaSummary = MemberSummary(bea, isMe = false, isSteward = false, devices = 1, fingerprint = "1111 2222 3333 4444 5555")

    @Test fun theMembersPageListsEveryoneWithRoleDevicesAndFingerprint() {
        val html = members(home(people = listOf(aliceSummary, beaSummary)))
        assertTrue("You" in html && "Member bbbbbbbb" in html)
        assertTrue("Steward, 2 devices" in html)
        assertTrue("1 device<" in html)
        assertTrue("Key fingerprint: ab12 cd34 ef56 7890 1234" in html)
        assertTrue("Key fingerprint: 1111 2222 3333 4444 5555" in html)
        assertTrue("read the fingerprints out to each other in person" in html)
    }

    @Test fun theMembersPageOffersAnInviteAndShowsTheMadeLinkReadOnly() {
        val html = members(home(), newInvite = "https://circles.example/#/join/x")
        assertTrue("""$ATTR_ACTION="$ACTION_INVITE"""" in html)
        assertTrue("""value="https://circles.example/#/join/x"""" in html)
        assertTrue("readonly" in html)
        assertTrue("""$ATTR_ACTION="$ACTION_COPY"""" in html)
        assertFalse("""$ATTR_ACTION="$ACTION_COPY"""" in members(home()))
    }

    @Test fun onlyStewardsSeeWhoIsWaitingAndCanLetThemIn() {
        val waiting = listOf(WaitingDevice(beaDevice, bea))
        val steward = members(home(steward = true, waiting = waiting))
        assertTrue("""$ATTR_ACTION="$ACTION_ADMIT"""" in steward)
        assertTrue("""$ATTR_DEVICE="${beaDevice.v}"""" in steward)
        assertTrue("Member bbbbbbbb" in steward)
        assertFalse("""$ATTR_ACTION="$ACTION_ADMIT"""" in members(home(steward = false, waiting = waiting)))
        assertFalse("Waiting to be let in" in members(home(steward = true)))
    }

    @Test fun refusedChangesAreReported() {
        assertTrue("2 of your changes could not be sent" in render(open(home(refused = 2))))
        assertFalse("could not be sent" in render(open(home(refused = 0))))
    }

    private fun settings(autoLock: Int = 15) = render(open(home().copy(autoLockMinutes = autoLock)).copy(screen = Screen.Settings))

    @Test fun theSettingsPageHasPassphraseLockingAndForgetting() {
        val html = settings()
        assertTrue("""id="$FORM_CHANGE_PASSPHRASE"""" in html && """id="$FORM_AUTOLOCK"""" in html)
        assertTrue("Current passphrase: " in html && "New passphrase: " in html && "Lock this device: " in html)
        assertTrue("""autocomplete="current-password"""" in html && """autocomplete="new-password"""" in html)
        assertTrue("""$ATTR_ACTION="$ACTION_ERASE"""" in html)
        assertTrue(html.indexOf("<details") < html.indexOf(ACTION_ERASE), "forgetting needs a second click")
    }

    @Test fun theChangePassphraseButtonWaitsForBothFields() {
        val html = settings()
        val button = Regex("<button[^>]*id=\"$SUBMIT_ID\"[^>]*>").find(html.substringAfter(FORM_CHANGE_PASSPHRASE))?.value ?: error("no button")
        assertTrue("hidden" in button)
        assertTrue("""$ATTR_MIN="1"""" in html && """$ATTR_MIN="12"""" in html)
    }

    @Test fun theLockingChoicesIncludeTheCurrentOneAsSelected() {
        for (minutes in listOf(5, 15, 60, 0)) {
            val html = settings(minutes)
            assertTrue(Regex("""<option[^>]*value="$minutes"[^>]*selected""").containsMatchIn(html), "$minutes")
            assertEquals(1, Regex("<option[^>]*selected").findAll(html.substringAfter(FORM_AUTOLOCK)).count(), "only one is selected")
        }
        assertTrue("Only when I close the tab" in settings())
        assertTrue("After 15 minutes without use" in settings())
    }

    @Test fun syncNowAndLockNowAreInTheHeaderOfEverySignedInPageAndNowhereElse() {
        for (screen in listOf(Screen.Home, Screen.Items, Screen.AddItem, Screen.Borrow, Screen.Skills)) {
            val html = render(open().copy(screen = screen))
            assertTrue("""$ATTR_ACTION="$ACTION_SYNC"""" in html, screen.name)
            assertTrue("""$ATTR_ACTION="$ACTION_LOCK"""" in html, screen.name)
            assertTrue(html.indexOf(ACTION_LOCK) < html.indexOf("<nav"), "they sit in the header, above the navigation")
        }
        for (other in listOf(AppState(model = AppModel.Locked(circle), screen = Screen.SignIn), AppState(model = AppModel.NoIdentity))) {
            val html = render(other)
            assertFalse("""$ATTR_ACTION="$ACTION_SYNC"""" in html)
            assertFalse("""$ATTR_ACTION="$ACTION_LOCK"""" in html)
        }
    }

    private fun items(home: HomeData) = render(open(home).copy(screen = Screen.Items))

    @Test fun theItemsPageSaysWhenYouCannotSeeItemsYet() {
        val html = items(home(canRead = false))
        assertTrue("A steward needs to let you in" in html)
        assertFalse(Screen.AddItem.fragment in html)
    }

    @Test fun anEmptyItemsPageInvitesYouToAddTheFirst() {
        val html = items(home())
        assertTrue("Nothing has been shared yet." in html)
        assertTrue("""href="${Screen.AddItem.fragment}"""" in html)
    }

    @Test fun theItemsPageListsItemsAndOffersChangesOnlyOnYourOwn() {
        val html = items(home(items = listOf(ladder, drill)))
        assertTrue("Ladder" in html && "Drill" in html)
        assertTrue("Yours" in html)
        assertTrue("Shared by member bbbbbbbb" in html)
        assertTrue("Details: Garage at the back" in html)
        assertEquals(1, Regex("""$ATTR_ACTION="$ACTION_EDIT_ITEM"""").findAll(html).count())
        assertEquals(1, Regex("""$ATTR_ACTION="$ACTION_RETIRE_ITEM"""").findAll(html).count())
        assertTrue("""$ATTR_ITEM="${ladder.item.v}"""" in html)
        // Someone else's item may be asked for, but is never offered for change or removal (the counts above are 1).
        assertEquals(1, Regex("Details:").findAll(html).count(), "someone else's item shows no details")
    }

    @Test fun removingAnItemNeedsASecondDeliberateClick() {
        val html = items(home(items = listOf(ladder)))
        assertTrue(html.indexOf("<details") < html.indexOf(ACTION_RETIRE_ITEM))
        assertTrue("cannot be put back" in html)
    }

    @Test fun theAddItemPageHasNameKindAndDetailsAndAHiddenButton() {
        val html = render(open().copy(screen = Screen.AddItem))
        assertTrue("""id="$FORM_ITEM_ADD"""" in html)
        assertTrue("""for="$FIELD_NAME"""" in html && """for="$FIELD_KIND"""" in html && """for="$FIELD_DETAILS"""" in html)
        for (kind in ItemClass.entries) assertTrue("""value="${kind.name}"""" in html, kind.name)
        assertTrue("""maxlength="80"""" in html && """maxlength="500"""" in html)
        val button = Regex("<button[^>]*id=\"$SUBMIT_ID\"[^>]*>").find(html)?.value ?: error("no button")
        assertTrue("hidden" in button)
        assertTrue("""href="${Screen.Items.fragment}"""" in html)
        assertFalse(FORM_ITEM_EDIT in html)
    }

    @Test fun theChangeItemPageIsPrefilledAndItsButtonShowsAtOnce() {
        val html = render(open(home(items = listOf(ladder))).copy(screen = Screen.EditItem, editing = ladder.item))
        assertTrue("""id="$FORM_ITEM_EDIT"""" in html)
        assertTrue("""value="Ladder"""" in html)
        assertTrue(">Garage at the back</textarea>" in html)
        assertTrue(Regex("""<option[^>]*value="EVERYDAY"[^>]*selected""").containsMatchIn(html))
        val button = Regex("<button[^>]*id=\"$SUBMIT_ID\"[^>]*>").find(html)?.value ?: error("no button")
        assertFalse("hidden" in button)
        assertFalse(FORM_ITEM_ADD in html)
    }

    @Test fun theNavigationBarListsItemsButNotItsSubPages() {
        val html = render(open().copy(screen = Screen.Items))
        assertTrue("""href="#/items"""" in html)
        assertFalse("""href="#/items/add"""" in html.substringBefore("</nav>"))
        assertFalse("""href="#/items/edit"""" in html)
    }

    @Test fun otherPeoplesItemsOfferToBorrowAndYourOwnDoNot() {
        val html = items(home(items = listOf(ladder, drill)))
        assertEquals(1, Regex("""$ATTR_ACTION="$ACTION_BORROW_ITEM"""").findAll(html).count())
        assertTrue("""$ATTR_ITEM="${drill.item.v}"""" in html)
    }

    @Test fun theRequestPageHasTwoDatesAndANoteAndAHiddenButton() {
        val html = render(open(home(items = listOf(drill))).copy(screen = Screen.RequestLoan, borrowing = drill.item))
        assertTrue("""id="$FORM_LOAN_REQUEST"""" in html)
        assertTrue("You are asking to borrow: Drill" in html)
        assertEquals(2, Regex("""type="date"""").findAll(html).count())
        assertTrue("""min="2026-11-02"""" in html, "earliest date is today")
        assertTrue("""for="$FIELD_FROM"""" in html && """for="$FIELD_TO"""" in html && """for="$FIELD_NOTE"""" in html)
        assertTrue("""maxlength="256"""" in html)
        val button = Regex("<button[^>]*id=\"$SUBMIT_ID\"[^>]*>").find(html)?.value ?: error("no button")
        assertTrue("hidden" in button)
        assertTrue("""$ATTR_MIN="10"""" in html)
    }

    @Test fun theRequestPageForAnItemThatIsGoneSaysSo() {
        val html = render(open().copy(screen = Screen.RequestLoan, borrowing = drill.item))
        assertTrue("no longer on the list" in html)
        assertFalse("""id="$FORM_LOAN_REQUEST"""" in html)
    }

    @Test fun theBorrowPageSaysWhenYouAreNotLetInYetAndWhenThereAreNoLoans() {
        assertTrue("A steward needs to let you in" in render(open(home(canRead = false)).copy(screen = Screen.Borrow)))
        val empty = render(open(home()).copy(screen = Screen.Borrow))
        assertTrue("No loans yet." in empty)
        assertTrue("""href="${Screen.Items.fragment}"""" in empty)
    }

    private fun borrow(vararg loans: LoanSummary) = render(open(home(loans = loans.toList())).copy(screen = Screen.Borrow))

    @Test fun anOwnerSeesRequestsWithTheNoteAndCanApproveOrDecline() {
        val html = borrow(loan("a", LoanRole.OWNER, LoanStatus.REQUESTED, note = "for the fence"))
        assertTrue("Requests to answer" in html)
        assertTrue("Note: for the fence" in html)
        assertTrue("2026-11-03 to 2026-11-05" in html)
        assertTrue("""$ATTR_ACTION="$ACTION_APPROVE_LOAN"""" in html && """$ATTR_ACTION="$ACTION_DECLINE_LOAN"""" in html)
        assertTrue("""$ATTR_LOAN="${"a".repeat(32)}"""" in html)
    }

    @Test fun aBorrowerWaitingForAnAnswerHasNoButtons() {
        val html = borrow(loan("b", LoanRole.BORROWER, LoanStatus.REQUESTED))
        assertTrue("Waiting for an answer" in html)
        assertFalse("<button" in html.substringAfter("<main"))
    }

    @Test fun handoverNeedsBothPeopleAndTheBorrowerSeesTheDetailsOnceApproved() {
        val first = borrow(loan("c", LoanRole.BORROWER, LoanStatus.APPROVED, description = "Garage at the back"))
        assertTrue("Ready to hand over" in first)
        assertTrue("Details: Garage at the back" in first)
        assertTrue("""$ATTR_ACTION="$ACTION_HANDOVER"""" in first)
        val waiting = borrow(loan("c", LoanRole.BORROWER, LoanStatus.APPROVED, mine = true))
        assertTrue("You have confirmed" in waiting)
        assertFalse(ACTION_HANDOVER in waiting)
        val theirTurn = borrow(loan("c", LoanRole.OWNER, LoanStatus.APPROVED, theirs = true))
        assertTrue("has confirmed" in theirTurn)
        assertTrue("""$ATTR_ACTION="$ACTION_HANDOVER"""" in theirTurn)
        assertFalse("Details:" in theirTurn, "the owner's own details are on the Items page")
    }

    @Test fun onlyTheBorrowerGetsTheReturnFormAndItNamesNoPenaltyForTellingTheTruth() {
        val mine = borrow(loan("d", LoanRole.BORROWER, LoanStatus.ACTIVE, description = "Garage"))
        assertTrue("""id="$FORM_RETURN_PREFIX${"d".repeat(32)}"""" in mine)
        for (outcome in ReturnOutcome.entries) assertTrue("""value="${outcome.name}"""" in mine, outcome.name)
        assertTrue("no penalty for telling" in mine)
        assertTrue("How did it go: " in mine)
        val button = Regex("<button[^>]*id=\"$SUBMIT_ID\"[^>]*>").find(mine)?.value ?: error("no button")
        assertFalse("hidden" in button, "a choice is already selected, so the button shows")
        val theirs = borrow(loan("d", LoanRole.OWNER, LoanStatus.ACTIVE))
        assertFalse(FORM_RETURN_PREFIX in theirs)
        assertTrue("They will report it back" in theirs)
    }

    @Test fun finishedLoansShowHowTheyEnded() {
        val html = borrow(
            loan("e", LoanRole.BORROWER, LoanStatus.DECLINED),
            loan("f", LoanRole.BORROWER, LoanStatus.RETURNED, outcome = ReturnOutcome.DAMAGED_REPORTED),
        )
        assertTrue("Finished" in html)
        assertTrue("Declined." in html)
        assertTrue("damage reported" in html)
    }

    @Test fun anItemYouCannotAskForYetShowsWhyAndHowToUnlockInsteadOfAButton() {
        val html = items(home(items = listOf(drill, locked)))
        assertEquals(1, Regex("""$ATTR_ACTION="$ACTION_BORROW_ITEM"""").findAll(html).count(), "only the open item has the button")
        assertTrue("""$ATTR_ITEM="${drill.item.v}"""" in html)
        assertFalse("""$ATTR_ITEM="${locked.item.v}"""" in html)
        assertTrue("Not yet." in html)
        assertTrue("Trailer needs TRUSTED; you are NEWCOMER." in html)
        assertTrue("Or get 2 neighbours to vouch for you." in html)
        assertTrue("Why, and how to unlock" in html)
    }

    private val myStanding = StandingSummary(Tier.NEIGHBOUR, "3.5", listOf("Activity in this circle gives 3.5 standing points."), listOf("About 6.5 more standing points opens trusted access."))

    @Test fun theStandingPageShowsOnlyYourOwnTierPointsReasonsAndNextSteps() {
        val html = render(open(home().copy(standing = myStanding)).copy(screen = Screen.Standing))
        assertTrue("Your tier: Neighbour" in html && "Standing points: 3.5" in html)
        assertTrue("Activity in this circle gives 3.5 standing points." in html)
        assertTrue("About 6.5 more standing points opens trusted access." in html)
        assertTrue("nobody is ever banned for low standing" in html)
        assertFalse("This screen is not built yet." in html)
    }

    @Test fun theStandingPageBeforeTheCircleIsReadSaysSo() {
        assertTrue("appears once the circle has been read" in render(open(home()).copy(screen = Screen.Standing)))
    }

    @Test fun membersWhoMayVouchSeeAButtonForPeopleTheyHaveNotVouchedFor() {
        val mine = MemberSummary(me, isMe = true, isSteward = true, devices = 1, fingerprint = "aaaa aaaa aaaa aaaa aaaa")
        val theirs = beaSummary
        val html = members(home(people = listOf(mine, theirs)).copy(canVouch = true))
        assertEquals(1, Regex("""$ATTR_ACTION="$ACTION_VOUCH"""").findAll(html).count(), "not for yourself")
        assertTrue("""$ATTR_MEMBER="${bea.v}"""" in html)
        val already = members(home(people = listOf(mine, theirs.copy(vouchedByMeUntil = 20_939))).copy(canVouch = true))
        assertFalse("""$ATTR_ACTION="$ACTION_VOUCH"""" in already)
        assertTrue("You vouch for them until 2027-05-01." in already)
        assertFalse("""$ATTR_ACTION="$ACTION_VOUCH"""" in members(home(people = listOf(mine, theirs)).copy(canVouch = false)), "newcomers see no vouch button")
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
        assertTrue("""$ATTR_ACTION="$ACTION_UPDATE"""" in html)
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
