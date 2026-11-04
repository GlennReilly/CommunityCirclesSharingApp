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
import circles.app.DeviceLink
import circles.app.RecoveryDraft
import circles.app.RestoreLink
import circles.app.DeviceSummary
import circles.app.ProposalSummary
import circles.app.StewardInfo
import circles.app.StewardSettingsSummary
import circles.event.StewardChange
import circles.app.DisputeStatus
import circles.app.DisputeSummary
import circles.event.DisputeClaim
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
        assertEquals(setOf(Screen.Welcome, Screen.StartCircle, Screen.JoinCircle, Screen.PairDevice, Screen.Restore), Screen.newcomer)
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
        assertEquals("Cannot sign in to the circle. If you were removed, ask a steward", describe(SyncState.SignedOut))
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
            stewards(true, listOf(meSteward)),
            borrow(loan("d", LoanRole.BORROWER, LoanStatus.ACTIVE)),
            borrow(returned(LoanRole.OWNER, { it.copy(canDispute = true) })),
        )
        var labels = 0
        for (html in pages) {
            for (match in Regex("<label[^>]*>([\\s\\S]*?)</label>").findAll(html.replace(Regex("<div class=\"check-row\">[\\s\\S]*?</div>"), ""))) {
                labels++
                assertTrue(match.groupValues[1].endsWith(": "), "label text was '${match.groupValues[1]}'")
            }
        }
        assertEquals(8 + 3 + 1 + 3 + 2 + 4 + 4 + 9 + 1 - 1, labels)
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
        assertTrue("Nobody has offered a skill yet." in html, "every screen in the navigation is built now")
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

    @Test fun aBorrowerWaitingForAnAnswerCanOnlyCancel() {
        val html = borrow(loan("b", LoanRole.BORROWER, LoanStatus.REQUESTED))
        assertTrue("Waiting for an answer" in html)
        assertEquals(1, Regex("<button").findAll(html.substringAfter("<main")).count())
        assertTrue("Cancel my request" in html)
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

    private fun returned(role: LoanRole, vararg tweak: (LoanSummary) -> LoanSummary): LoanSummary =
        tweak.fold(loan("r", role, LoanStatus.RETURNED, outcome = ReturnOutcome.OK).copy(reported = ReturnOutcome.OK)) { acc, f -> f(acc) }

    @Test fun anOwnerCanConfirmAReturnOrReportAProblemAndTheFormHasLabelsAndMaxLength() {
        val html = borrow(returned(LoanRole.OWNER, { it.copy(canConfirm = true, canDispute = true) }))
        assertTrue("""$ATTR_ACTION="$ACTION_CONFIRM_RETURN"""" in html && "It came back fine" in html)
        assertTrue("""id="$FORM_DISPUTE_PREFIX${"r".repeat(32)}"""" in html)
        assertTrue("What went wrong: " in html && "Note: " in html && """maxlength="256"""" in html)
        for (claim in DisputeClaim.entries) assertTrue("""value="${claim.name}"""" in html, claim.name)
        assertTrue(html.indexOf("<details") < html.indexOf(FORM_DISPUTE_PREFIX), "the report sits behind a disclosure")
        assertTrue("Use this only if the borrower did not tell you" in html)
    }

    @Test fun neitherConfirmNorReportIsOfferedWhenNotAllowed() {
        val html = borrow(returned(LoanRole.OWNER), returned(LoanRole.BORROWER))
        assertFalse(ACTION_CONFIRM_RETURN in html)
        assertFalse(FORM_DISPUTE_PREFIX in html)
    }

    private fun disputed(status: DisputeStatus, note: String? = "the handle is cracked") =
        { l: LoanSummary -> l.copy(dispute = DisputeSummary(DisputeClaim.DAMAGE, note, 20_760, status)) }

    @Test fun everyoneSeesPendingReviewWithTheOwnersNoteAndCreditWaiting() {
        for (role in listOf(LoanRole.OWNER, LoanRole.BORROWER)) {
            val html = borrow(returned(role, disputed(DisputeStatus.PENDING)))
            assertTrue("Pending review" in html, role.name)
            assertTrue("Credit for this loan waits." in html)
            assertTrue("Owner's note: the handle is cracked" in html)
        }
    }

    @Test fun settledDisputesSayHowTheyEnded() {
        assertTrue("was upheld" in borrow(returned(LoanRole.BORROWER, disputed(DisputeStatus.UPHELD))))
        assertTrue("dismissed, so the return counts as reported" in borrow(returned(LoanRole.BORROWER, disputed(DisputeStatus.DISMISSED))))
    }

    @Test fun aStewardWhoIsNotAPartySeesTheDisputeAndTheTwoDecisions() {
        val loan = returned(LoanRole.STEWARD, disputed(DisputeStatus.PENDING), { it.copy(canResolve = true) })
        val html = render(open(home(steward = true, loans = listOf(loan))).copy(screen = Screen.Borrow))
        assertTrue("Disputes to resolve" in html)
        assertTrue("The owner says the borrower did not report damage" in html)
        assertTrue("The borrower reported: Returned, all good" in html)
        assertTrue("""$ATTR_ACTION="$ACTION_UPHOLD"""" in html && """$ATTR_ACTION="$ACTION_DISMISS_DISPUTE"""" in html)
        assertFalse("Finished" in html, "the steward's view of someone else's loan is not in their own history")
    }

    @Test fun nobodyElseIsOfferedTheDecision() {
        val html = borrow(returned(LoanRole.OWNER, disputed(DisputeStatus.PENDING)))
        assertFalse("Disputes to resolve" in html)
        assertFalse(ACTION_UPHOLD in html)
    }

    @Test fun theOnlyStewardIsWarnedAboutADisputeTheyAreAPartyTo() {
        val loan = returned(LoanRole.OWNER, disputed(DisputeStatus.PENDING))
        val warned = render(open(home(steward = true, loans = listOf(loan)).copy(stewardCount = 1)).copy(screen = Screen.Borrow))
        assertTrue("You are the only steward, so nobody else can decide this" in warned)
        assertTrue("closes after 30 days" in warned)
        val fine = render(open(home(steward = true, loans = listOf(loan)).copy(stewardCount = 2)).copy(screen = Screen.Borrow))
        assertFalse("only steward" in fine)
        val member = render(open(home(steward = false, loans = listOf(loan)).copy(stewardCount = 1)).copy(screen = Screen.Borrow))
        assertFalse("only steward" in member)
    }

    private val meSteward = StewardInfo(me, isMe = true, sinceDay = null, votesFromDay = null)
    private val beaSteward = StewardInfo(bea, isMe = false, sinceDay = 20_759, votesFromDay = 20_766)

    private fun stewards(steward: Boolean, list: List<StewardInfo>, proposals: List<ProposalSummary> = emptyList(), people: List<MemberSummary> = emptyList()) =
        render(open(home(steward = steward, people = people).copy(stewardList = list, stewardCount = list.size, proposals = proposals)).copy(screen = Screen.Stewards))

    @Test fun theStewardsPageListsStewardsWithTheirSinceDateAndCoolingOff() {
        val html = stewards(true, listOf(meSteward, beaSteward))
        assertTrue("Founding steward" in html)
        assertTrue("Steward since 2026-11-02" in html)
        assertTrue("Can vote from 2026-11-09" in html)
        assertTrue("Stewards who must agree: 2" in html)
    }

    @Test fun aLoneStewardIsToldTheCircleNeedsAnother() {
        assertTrue("This circle has one steward" in stewards(true, listOf(meSteward)))
        assertFalse("This circle has one steward" in stewards(true, listOf(meSteward, beaSteward)))
    }

    @Test fun stewardsCanStepDownOnlyWhileAnotherRemainsAndProposeRemovingOthersBehindADisclosure() {
        val alone = stewards(true, listOf(meSteward))
        assertFalse("""$ATTR_ACTION="$ACTION_STEWARD_REMOVE"""" in alone, "the last steward cannot step down")
        val two = stewards(true, listOf(meSteward, beaSteward))
        assertEquals(2, Regex("""$ATTR_ACTION="$ACTION_STEWARD_REMOVE"""").findAll(two).count())
        assertTrue("Step down as steward" in two && "Propose removing" in two)
        assertTrue(two.indexOf("<details") < two.indexOf(ACTION_STEWARD_REMOVE))
        val member = stewards(false, listOf(meSteward, beaSteward))
        assertFalse(ACTION_STEWARD_REMOVE in member, "ordinary members cannot")
    }

    @Test fun proposalsShowProgressAndOnlyThoseWhoCanVoteSeeAgree() {
        val add = ProposalSummary(StewardChange.ADD, MemberId("c".repeat(32)), null, agreeing = 1, needed = 2, iAgree = false, canAgree = true)
        val html = stewards(true, listOf(meSteward, beaSteward), listOf(add))
        assertTrue("Make member cccccccc a steward" in html)
        assertTrue("1 of 2 stewards agree." in html)
        assertTrue("""$ATTR_ACTION="$ACTION_STEWARD_ADD" $ATTR_SUBJECT="${"c".repeat(32)}"""" in html)
        val mine = stewards(true, listOf(meSteward, beaSteward), listOf(add.copy(iAgree = true, canAgree = false)))
        assertTrue("You agree." in mine)
        assertFalse("""$ATTR_SUBJECT="${"c".repeat(32)}"""" in mine.substringAfter("Waiting for agreement"))
        val cooling = stewards(true, listOf(meSteward, beaSteward), listOf(add.copy(canAgree = false)))
        assertFalse(">Agree<" in cooling)
    }

    @Test fun settingsProposalsCarryTheirSettingsAndRemovalsSayWho() {
        val rules = ProposalSummary(StewardChange.POLICY, null, StewardSettingsSummary(1, 60, 14), 1, 2, iAgree = false, canAgree = true)
        val html = stewards(true, listOf(meSteward, beaSteward), listOf(rules))
        assertTrue("Change the rules: 1 to agree, 60 quiet days, 14 days to wait, no recovery shares" in html)
        val withShares = ProposalSummary(StewardChange.POLICY, null, StewardSettingsSummary(1, 60, 14, 3), 1, 2, iAgree = false, canAgree = true)
        val sharesHtml = stewards(true, listOf(meSteward, beaSteward), listOf(withShares))
        assertTrue("3 stewards to rebuild the key" in sharesHtml)
        assertTrue("""$ATTR_SUBJECT="1:60:14:3"""" in sharesHtml)
        assertTrue("""$ATTR_ACTION="$ACTION_STEWARD_SETTINGS" $ATTR_SUBJECT="1:60:14:0"""" in html)
        val removal = ProposalSummary(StewardChange.REMOVE, bea, null, 1, 2, false, true)
        assertTrue("Remove member bbbbbbbb as a steward" in stewards(true, listOf(meSteward, beaSteward), listOf(removal)))
    }

    @Test fun onlyStewardsSeeWhoCanBeProposedAndTheRulesForm() {
        val people = listOf(
            MemberSummary(me, isMe = true, isSteward = true, devices = 1, fingerprint = "x"),
            MemberSummary(bea, isMe = false, isSteward = false, devices = 1, fingerprint = "y"),
        )
        val steward = stewards(true, listOf(meSteward), people = people)
        assertTrue("Propose a new steward" in steward && "Propose as steward" in steward)
        assertEquals(1, Regex("""$ATTR_ACTION="$ACTION_STEWARD_ADD"""").findAll(steward).count(), "not for yourself")
        assertTrue("""id="$FORM_STEWARD_RULES"""" in steward)
        val member = stewards(false, listOf(meSteward), people = people)
        assertFalse("Propose a new steward" in member)
        assertFalse(FORM_STEWARD_RULES in member)
        assertTrue("Stewards who must agree: 2" in member, "everyone can read the rules")
    }

    @Test fun theRulesFormHasFourLabelledNumbersWithTheirRangesAndAHiddenButton() {
        val html = stewards(true, listOf(meSteward))
        for (label in listOf("Stewards who must agree: ", "Quiet days: ", "Days to wait: ", "Stewards to rebuild the key (0 for none): ")) assertTrue(label in html, label)
        assertEquals(4, Regex("""type="number"""").findAll(html.substringAfter(FORM_STEWARD_RULES)).count())
        assertTrue("""min="1"""" in html && """max="9"""" in html && """min="7"""" in html && """max="365"""" in html && """max="90"""" in html)
        val button = Regex("<button[^>]*id=\"$SUBMIT_ID\"[^>]*>").find(html.substringAfter(FORM_STEWARD_RULES))?.value ?: error("no button")
        assertTrue("hidden" in button)
    }

    private val removable = MemberSummary(bea, isMe = false, isSteward = false, devices = 1, fingerprint = "y", canRemove = true)
    private val mine2 = MemberSummary(me, isMe = true, isSteward = true, devices = 1, fingerprint = "x")

    @Test fun aStewardCanRemoveAnOrdinaryMemberBehindAWarning() {
        val html = members(home(people = listOf(mine2, removable)))
        assertEquals(1, Regex("""$ATTR_ACTION="$ACTION_REMOVE_MEMBER"""").findAll(html).count())
        assertTrue("""$ATTR_MEMBER="${bea.v}"""" in html)
        assertTrue("cuts off all their devices at once and changes the circle's key" in html)
        assertTrue("What they already read stays with them" in html)
        assertTrue(html.indexOf("<details") < html.indexOf(ACTION_REMOVE_MEMBER), "a second click is needed")
        assertFalse(ACTION_REMOVE_MEMBER in members(home(people = listOf(mine2, removable.copy(canRemove = false)))))
    }

    @Test fun stewardsAreToldToChangeTheKeyAfterSomeoneLeavesAndOthersAreNot() {
        val prompted = members(home(people = listOf(mine2)).copy(needsRotation = true))
        assertTrue("Someone left the circle." in prompted)
        assertTrue("""$ATTR_ACTION="$ACTION_ROTATE_KEY"""" in prompted)
        assertFalse(ACTION_ROTATE_KEY in members(home(people = listOf(mine2))))
    }

    @Test fun settingsOffersLeavingToMembersAndTellsStewardsToStepDownFirst() {
        val member = render(open(home(steward = false)).copy(screen = Screen.Settings))
        assertTrue("""$ATTR_ACTION="$ACTION_LEAVE"""" in member)
        assertTrue(member.indexOf("<details") < member.indexOf(ACTION_LEAVE))
        val steward = render(open(home(steward = true)).copy(screen = Screen.Settings))
        assertFalse("""$ATTR_ACTION="$ACTION_LEAVE"""" in steward)
        assertTrue("Step down on the Stewards page first" in steward)
    }

    private val deviceLink = DeviceLink(circle, me)
    private val waiting = AppModel.AwaitingApproval(circle, "d".repeat(32) + ".AAAA.BBBB", "ab12 cd34 ef56 7890 1234")

    @Test fun aDeviceLinkLeadsToTheAddThisDevicePage() {
        val seen = reduce(AppState(model = AppModel.NoIdentity), AppAction.DeviceLinkSeen(deviceLink))
        assertEquals(Screen.PairDevice, seen.screen)
        assertEquals(deviceLink, seen.pendingDevice)
        // It is read before the app reports its first model (Loading) and must survive that.
        val early = reduce(AppState(), AppAction.DeviceLinkSeen(deviceLink))
        assertEquals(Screen.PairDevice, reduce(early, AppAction.ModelChanged(AppModel.NoIdentity)).screen)
        assertEquals(deviceLink, reduce(early, AppAction.ModelChanged(AppModel.Loading)).pendingDevice)
        assertNull(reduce(seen, AppAction.ModelChanged(AppModel.Locked(circle))).pendingDevice)
    }

    @Test fun aWaitingDeviceSeesOnlyItsOwnPage() {
        val state = AppState(model = waiting)
        for (screen in Screen.entries) assertEquals(Screen.PairDevice, reduce(state, AppAction.Navigate(screen)).screen, screen.name)
        assertEquals(Screen.PairDevice, reduce(AppState(model = AppModel.NoIdentity), AppAction.ModelChanged(waiting)).screen)
        assertEquals(Screen.Home, reduce(state, AppAction.ModelChanged(AppModel.Open(home()))).screen, "once added it opens the circle")
    }

    @Test fun thePairPageAsksForAPassphraseOnlyWhenALinkWasOpenedAndHasNoStartOption() {
        val without = newcomer(Screen.PairDevice)
        assertFalse("""id="$FORM_PAIR"""" in without)
        assertTrue("open the link shown on your other device" in without)
        val withLink = render(AppState(screen = Screen.PairDevice, model = AppModel.NoIdentity, pendingDevice = deviceLink))
        assertTrue("""id="$FORM_PAIR"""" in withLink)
        assertTrue("""$ATTR_MIN="12"""" in withLink && "new-password" in withLink)
        val button = Regex("<button[^>]*id=\"$SUBMIT_ID\"[^>]*>").find(withLink)?.value ?: error("no button")
        assertTrue("hidden" in button)
        assertFalse("Start a circle" in withLink)
    }

    @Test fun aWaitingDeviceShowsItsCodeAndFingerprintAndCanCheckOrForgetItself() {
        val html = render(AppState(screen = Screen.PairDevice, model = waiting))
        assertTrue("""value="${waiting.code}"""" in html && "readonly" in html)
        assertTrue("Device code: " in html)
        assertTrue("Fingerprint: ab12 cd34 ef56 7890 1234" in html)
        assertTrue("Waiting for your other device" in html)
        assertTrue("""$ATTR_ACTION="$ACTION_CHECK_NOW"""" in html && """$ATTR_ACTION="$ACTION_COPY_FIELD"""" in html && """$ATTR_ACTION="$ACTION_ERASE"""" in html)
        assertFalse("<nav" in html)
        assertFalse("""$ATTR_ACTION="$ACTION_SYNC"""" in html, "no header buttons before the device is added")
    }

    private fun settingsWith(devices: List<DeviceSummary>, origin: String = "https://circles.example") =
        render(open(home().copy(myDevices = devices)).copy(screen = Screen.Settings, origin = origin))

    @Test fun settingsListsMyDevicesAndOffersRemovalOnlyForTheOthers() {
        val here = DeviceSummary(DeviceId("1".repeat(32)), isThisOne = true, name = null)
        val phone = DeviceSummary(DeviceId("2".repeat(32)), isThisOne = false, name = "Phone")
        val html = settingsWith(listOf(here, phone))
        assertTrue("Unnamed device (this device)" in html)
        assertTrue("Phone" in html)
        assertEquals(1, Regex("""$ATTR_ACTION="$ACTION_REMOVE_DEVICE"""").findAll(html).count())
        assertTrue("""$ATTR_DEVICE_ID="${phone.device.v}"""" in html)
        assertTrue(html.indexOf("<details") < html.indexOf(ACTION_REMOVE_DEVICE), "a second click is needed")
        assertTrue("the circle's key is changed" in html)
    }

    @Test fun settingsShowsTheDeviceLinkAndTheAddDeviceForm() {
        val html = settingsWith(emptyList())
        assertTrue("""value="https://circles.example/#/device/${circle.v}/${me.v}"""" in html)
        assertTrue("""id="$FORM_ADD_DEVICE"""" in html)
        for (label in listOf("Link: ", "Device code: ", "Name of the device: ", "The fingerprint above matches the one on the new device")) assertTrue(label in html, label)
        assertTrue("""id="$ID_DEVICE_PREVIEW"""" in html && """aria-live="polite"""" in html)
        assertTrue("""type="checkbox"""" in html && "required" in html.substringAfter(FIELD_CHECKED))
        assertTrue("""maxlength="40"""" in html)
        val button = Regex("<button[^>]*id=\"$SUBMIT_ID\"[^>]*>").find(html.substringAfter(FORM_ADD_DEVICE))?.value ?: error("no button")
        assertTrue("hidden" in button)
    }

    private val draft = RecoveryDraft(
        words = listOf("abandon", "ability", "able", "about", "above", "absent", "absorb", "abstract", "absurd", "abuse", "access", "accident"),
        askFor = listOf(2, 5, 11), restoreLink = "https://circles.example/#/restore/${circle.v}/${me.v}",
    )

    @Test fun aRestoreLinkLeadsToTheRestorePage() {
        val link = RestoreLink(circle, me)
        val seen = reduce(AppState(model = AppModel.NoIdentity), AppAction.RestoreLinkSeen(link))
        assertEquals(Screen.Restore, seen.screen)
        assertEquals(link, seen.pendingRestore)
        val early = reduce(AppState(), AppAction.RestoreLinkSeen(link))
        assertEquals(Screen.Restore, reduce(early, AppAction.ModelChanged(AppModel.NoIdentity)).screen, "read before the first model arrives")
        assertNull(reduce(seen, AppAction.ModelChanged(AppModel.Open(home()))).pendingRestore)
    }

    @Test fun aRecoveryDraftIsKeptOnlyWhileTheCircleIsOpenAndDroppedOnLock() {
        val shown = reduce(open().copy(screen = Screen.Settings), AppAction.RecoveryDraftReady(draft))
        assertEquals(Screen.RecoveryCode, shown.screen)
        assertEquals(draft, shown.recoveryDraft)
        assertEquals(Screen.Settings, reduce(open(), AppAction.Navigate(Screen.RecoveryCode)).screen, "no draft, so back to settings")
        assertEquals(Screen.RecoveryCode, reduce(shown, AppAction.Navigate(Screen.RecoveryCode)).screen)
        assertNull(reduce(shown, AppAction.ModelChanged(AppModel.Locked(circle))).recoveryDraft, "locking forgets the words")
        assertNull(reduce(shown, AppAction.RecoveryDraftReady(null)).recoveryDraft)
        assertFalse(Screen.RecoveryCode in Screen.navigation)
    }

    @Test fun theRecoveryPageShowsTwelveNumberedWordsTheLinkAndAThreeWordCheck() {
        val html = render(open().copy(screen = Screen.RecoveryCode, recoveryDraft = draft))
        assertTrue("<ol>" in html)
        assertEquals(12, Regex("<li>(abandon|ability|able|about|above|absent|absorb|abstract|absurd|abuse|access|accident)</li>").findAll(html).count())
        assertTrue("""value="${draft.restoreLink}"""" in html && "readonly" in html)
        assertTrue("like a house key" in html)
        assertTrue("""id="$FORM_RECOVERY_CHECK"""" in html)
        for (place in draft.askFor) assertTrue("Word $place: " in html && """name="word-$place"""" in html, "$place")
        assertFalse("word-1\"" in html)
        val button = Regex("<button[^>]*id=\"$SUBMIT_ID\"[^>]*>").find(html)?.value ?: error("no button")
        assertTrue("hidden" in button)
        assertTrue("""$ATTR_ACTION="$ACTION_RECOVERY_CANCEL"""" in html)
    }

    @Test fun theRecoveryPageWithoutADraftSendsYouToSettings() {
        val html = render(open().copy(screen = Screen.RecoveryCode))
        assertTrue("There is no recovery code being made." in html)
        assertFalse(FORM_RECOVERY_CHECK in html)
    }

    @Test fun theRestorePageAsksForWordsAndAPassphraseOnlyWhenALinkWasOpened() {
        assertFalse("""id="$FORM_RESTORE"""" in render(AppState(screen = Screen.Restore, model = AppModel.NoIdentity)))
        val html = render(AppState(screen = Screen.Restore, model = AppModel.NoIdentity, pendingRestore = RestoreLink(circle, me)))
        assertTrue("""id="$FORM_RESTORE"""" in html)
        assertTrue("Your 12 words: " in html && "Passphrase: " in html)
        assertTrue("<textarea" in html && """$ATTR_MIN="1"""" in html && """$ATTR_MIN="12"""" in html)
        val button = Regex("<button[^>]*id=\"$SUBMIT_ID\"[^>]*>").find(html)?.value ?: error("no button")
        assertTrue("hidden" in button)
        assertTrue("the paper stops working" in html)
        assertFalse("Start a circle" in html)
    }

    @Test fun settingsOffersToMakeOrReplaceARecoveryCodeAndHomeSuggestsItUntilThen() {
        val without = render(open(home()).copy(screen = Screen.Settings))
        assertTrue("Make a recovery code" in without && """$ATTR_ACTION="$ACTION_RECOVERY_START"""" in without)
        assertTrue("No recovery code yet" in without)
        val withCode = render(open(home().copy(hasRecoveryCode = true)).copy(screen = Screen.Settings))
        assertTrue("Make a new recovery code" in withCode && "A new code replaces the old one" in withCode)
        assertTrue("Make a recovery code, in case you lose every device" in render(open(home())))
        assertFalse("recovery code" in render(open(home().copy(hasRecoveryCode = true))).substringAfter("<main"))
        assertFalse("Make a recovery code, in case" in render(open(home(canRead = false))), "nothing to protect before being let in")
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

    // ---- recovery shares (ADR 0048) ----

    private fun sharesPage(summary: circles.app.RecoverySharesSummary, steward: Boolean = true, shown: Map<Int, String> = emptyMap(), stewardCount: Int = 3) =
        render(open(home(steward = steward).copy(recoveryShares = summary, stewardCount = stewardCount)).copy(screen = Screen.RecoveryShares, shareCodes = shown))

    @Test fun theStewardsPageLinksToRecoverySharesOnlyForStewardsWhenThereIsMoreThanOne() {
        assertTrue(Screen.RecoveryShares.fragment in stewards(true, listOf(meSteward, beaSteward)))
        assertFalse(Screen.RecoveryShares.fragment in stewards(true, listOf(meSteward)))
        assertFalse(Screen.RecoveryShares.fragment in stewards(false, listOf(meSteward, beaSteward)))
        assertFalse(Screen.RecoveryShares in Screen.navigation)
    }

    @Test fun theSharesPageOffersMakingSharesWithTheRightRangeAndHidesTheButtonUntilFilled() {
        val html = sharesPage(circles.app.RecoverySharesSummary(currentEpoch = 1, mayMake = true, minThreshold = 2, maxThreshold = 3))
        assertTrue("""id="$FORM_MAKE_SHARES"""" in html)
        assertTrue("""min="2"""" in html && """max="3"""" in html)
        assertTrue("""$ATTR_MIN="1"""" in html, "the button waits for the number")
        assertTrue("Not every steward has a share of the current key yet." in html)
        assertFalse(FORM_REBUILD_KEY in html)
        assertFalse("Your share" in html)
        assertTrue("Every steward has a share of the current key." in sharesPage(circles.app.RecoverySharesSummary(currentEpoch = 1, currentSetSent = true, mayMake = true)))
    }

    @Test fun aShareCodeIsHiddenUntilAskedForAndLostOnLock() {
        val summary = circles.app.RecoverySharesSummary(heldEpochs = listOf(1), currentEpoch = 1)
        val hidden = sharesPage(summary)
        assertTrue("""$ATTR_ACTION="$ACTION_SHARE_SHOW"""" in hidden)
        assertFalse("rs1 " in hidden)
        val shown = sharesPage(summary, shown = mapOf(1 to "rs1 0000 0001"))
        assertTrue("rs1 0000 0001" in shown)
        assertTrue("""$ATTR_ACTION="$ACTION_SHARE_HIDE"""" in shown)
        val state = reduce(open().copy(screen = Screen.RecoveryShares), AppAction.ShareCodeShown(1, "rs1 abcd"))
        assertEquals(mapOf(1 to "rs1 abcd"), state.shareCodes)
        assertEquals(emptyMap(), reduce(state, AppAction.ShareCodeShown(1, null)).shareCodes)
        assertEquals(emptyMap(), reduce(state, AppAction.ModelChanged(AppModel.Locked(circle))).shareCodes, "locking forgets the codes")
    }

    @Test fun theRebuildFormAppearsOnlyWhenTheKeyIsMissing() {
        val missing = sharesPage(circles.app.RecoverySharesSummary(currentEpoch = 2, missingKeyEpochs = listOf(2)))
        assertTrue("""id="$FORM_REBUILD_KEY"""" in missing)
        assertTrue("key number 2" in missing)
        assertTrue("""$ATTR_MIN="1"""" in missing)
        assertFalse(FORM_REBUILD_KEY in sharesPage(circles.app.RecoverySharesSummary(currentEpoch = 2)))
    }

    @Test fun aMemberWhoIsNotAStewardSeesNoSharesControls() {
        val html = sharesPage(circles.app.RecoverySharesSummary(heldEpochs = listOf(1), mayMake = true), steward = false)
        assertTrue("Only stewards hold recovery shares." in html)
        assertFalse(FORM_MAKE_SHARES in html)
    }

    @Test fun settingsOffersAnExportWithAPlainWarning() {
        val html = render(open(home()).copy(screen = Screen.Settings))
        assertTrue("""$ATTR_ACTION="$ACTION_EXPORT"""" in html)
        assertTrue("Keep it private all the same" in html)
        assertEquals(1, Regex("""$ATTR_ACTION="$ACTION_EXPORT"""").findAll(html).count())
    }

    // ---- pauses (ADR 0050) ----

    private val pauseId = circles.event.PauseId("a".repeat(32))

    private fun pauseSummary(role: circles.app.PauseRole = circles.app.PauseRole.REVIEWER, status: circles.event.PauseStatus = circles.event.PauseStatus.PROPOSED, init: circles.app.PauseSummary.() -> circles.app.PauseSummary = { this }) =
        circles.app.PauseSummary(
            pauseId, role, bea, "keeps returning things damaged", 30, status, agreeing = 1, needed = 2, iAgree = null,
            untilDay = if (status == circles.event.PauseStatus.LIVE) 20_790 else null, appeal = null, proposedByMe = false, reviewers = emptyList(),
            canReview = false, canStepBack = false, canAppeal = false, canLift = false,
        ).init()

    private fun pausesPage(vararg pauses: circles.app.PauseSummary, candidates: List<circles.policy.MemberId> = emptyList(), canPropose: Boolean = false) =
        render(open(home().copy(pauses = pauses.toList(), canProposePause = canPropose, pauseCandidates = candidates)).copy(screen = Screen.Pauses))

    @Test fun theSubjectIsToldPlainlyAndMayAppealOnceWithTheEssentialsReminder() {
        val html = pausesPage(pauseSummary(circles.app.PauseRole.SUBJECT, circles.event.PauseStatus.LIVE) { copy(canAppeal = true, reviewers = listOf(me)) })
        assertTrue("Your access is paused until" in html)
        assertTrue("Essentials stay open to you." in html)
        assertTrue("Reason given: keeps returning things damaged" in html)
        assertTrue("""id="$FORM_PAUSE_APPEAL"""" in html && """$ATTR_PAUSE="${pauseId.v}"""" in html)
        val button = Regex("<button[^>]*id=\"$SUBMIT_ID\"[^>]*>").find(html.substringAfter(FORM_PAUSE_APPEAL))?.value ?: error("no button")
        assertTrue("hidden" in button, "the appeal button waits for text")
        assertFalse(ACTION_PAUSE_REVIEW in html)
        val appealed = pausesPage(pauseSummary(circles.app.PauseRole.SUBJECT) { copy(appeal = "I was away") })
        assertTrue("Your appeal: I was away" in appealed)
        assertFalse(FORM_PAUSE_APPEAL in appealed)
    }

    @Test fun reviewersSeeCountsAndButtonsButNotWhoAgreedAndCanLiftBehindADisclosure() {
        val html = pausesPage(pauseSummary { copy(canReview = true) }, pauseSummary(status = circles.event.PauseStatus.LIVE) { copy(canStepBack = true, canLift = true, iAgree = true, appeal = "I was away") })
        assertTrue("1 of 2 reviewers have agreed." in html)
        assertEquals(1, Regex("""$ATTR_ACTION="$ACTION_PAUSE_REVIEW" $ATTR_PAUSE="${pauseId.v}" $ATTR_AGREE="true"""").findAll(html).count())
        assertTrue("Do not agree" in html && "Step back from this pause" in html)
        assertTrue("The member's appeal: I was away" in html && "You agreed." in html)
        assertTrue("<summary>Lift this pause</summary>" in html && """$ATTR_ACTION="$ACTION_PAUSE_LIFT"""" in html)
        assertFalse("Agreed so far" in html)
    }

    @Test fun theProposeFormIsOnlyForTrustedMembersWithSomeoneToPropose() {
        assertFalse(FORM_PAUSE_PROPOSE in pausesPage(canPropose = true))
        assertFalse(FORM_PAUSE_PROPOSE in pausesPage(candidates = listOf(bea)))
        val html = pausesPage(canPropose = true, candidates = listOf(bea))
        assertTrue("""id="$FORM_PAUSE_PROPOSE"""" in html && """value="30"""" in html)
        assertTrue("No pauses are proposed or in force that you can see." in html)
    }

    @Test fun homeAndStandingPointToPausesOnlyWhenThereIsSomethingToDo() {
        assertFalse("Pauses" in render(open(home())).substringAfter("<main"))
        assertTrue("Your access is paused" in render(open(home().copy(pauses = listOf(pauseSummary(circles.app.PauseRole.SUBJECT, circles.event.PauseStatus.LIVE))))))
        assertTrue("1 pause proposal waiting for your answer" in render(open(home().copy(pauses = listOf(pauseSummary { copy(canReview = true) })))))
        assertTrue(Screen.Pauses.fragment in render(open(home().copy(canProposePause = true)).copy(screen = Screen.Standing, model = AppModel.Open(home(steward = false).copy(canProposePause = true, standing = StandingSummary(circles.policy.Tier.TRUSTED, "3.0", emptyList(), emptyList()))))))
        assertFalse(Screen.Pauses in Screen.navigation)
    }

    // ---- circle rules (ADR 0051) ----

    private val defaultRules = circles.event.ConfigSettings.of(circles.policy.CircleConfig(circles.policy.CircleId(""), ""))

    @Test fun theStewardsPageShowsTheCircleRulesInWordsAndProposalsToAgreeTo() {
        val changed = defaultRules.copy(halfLifeDays = 90)
        val summary = circles.app.RulesSummary(defaultRules, listOf(circles.app.RulesProposal(changed, 1, 2, iAgree = false, canAgree = true)))
        val html = render(open(home().copy(rules = summary, stewardCount = 2)).copy(screen = Screen.Stewards))
        assertTrue("Points for Neighbour, Trusted and Steward level: 3.0, 10.0 and 25.0" in html)
        assertTrue("Points fade by half every 180 days" in html)
        assertTrue("1 of 2 stewards agree." in html)
        assertTrue("""$ATTR_ACTION="$ACTION_RULES_AGREE" $ATTR_RULES="30:100:250:90:2:180:3:30:90"""" in html)
        assertTrue("Things decided before then keep the rules they were decided under." in html)
        assertTrue("""id="$FORM_RULES"""" in html)
        val voted = circles.app.RulesProposal(changed, 1, 2, iAgree = true, canAgree = false)
        assertFalse(ACTION_RULES_AGREE in render(open(home().copy(rules = summary.copy(proposals = listOf(voted)))).copy(screen = Screen.Stewards)))
    }

    @Test fun aMemberWhoIsNotAStewardSeesTheRulesButNoForm() {
        val html = render(open(home(steward = false).copy(rules = circles.app.RulesSummary(defaultRules))).copy(screen = Screen.Stewards))
        assertTrue("Points fade by half every 180 days" in html)
        assertFalse(FORM_RULES in html)
    }

    @Test fun theItemFormOffersToRaiseWhoMayAskButNotLowerIt() {
        val html = render(open(home()).copy(screen = Screen.AddItem))
        assertTrue("""for="$FIELD_MIN_TIER"""" in html && "Who may ask: " in html)
        assertTrue("Neighbour and above" in html && "Trusted and above" in html)
        assertFalse("Newcomer and above" in html)
    }

    // ---- loan windows (ADR 0052) ----

    private fun loanLine(role: LoanRole, status: LoanStatus, iConfirmed: Boolean = false) =
        LoanSummary(circles.event.LoanId("c".repeat(32)), circles.policy.ItemId("e".repeat(32)), "Ladder", role, bea, 20_760, 20_762, status, iConfirmed, false, null, null)

    @Test fun aBorrowerCanCancelARequestOrAnApprovalBeforeHandOverButNotAfterConfirming() {
        fun borrow(vararg loans: LoanSummary) = render(open(home().copy(loans = loans.toList())).copy(screen = Screen.Borrow))
        assertTrue("Cancel my request" in borrow(loanLine(LoanRole.BORROWER, LoanStatus.REQUESTED)))
        assertTrue("Cancel this loan" in borrow(loanLine(LoanRole.BORROWER, LoanStatus.APPROVED)))
        assertFalse("Cancel this loan" in borrow(loanLine(LoanRole.BORROWER, LoanStatus.APPROVED, iConfirmed = true)))
        assertFalse(ACTION_CANCEL_LOAN in borrow(loanLine(LoanRole.OWNER, LoanStatus.APPROVED)))
        assertFalse(ACTION_CANCEL_LOAN in borrow(loanLine(LoanRole.BORROWER, LoanStatus.ACTIVE)))
        val finished = borrow(loanLine(LoanRole.BORROWER, LoanStatus.CANCELLED))
        assertTrue("Cancelled." in finished && "Finished" in finished)
    }

    // ---- contribution notes (ADR 0053) ----

    private fun standingPage(c: circles.app.ContributionsSummary) =
        render(open(home().copy(contributions = c, standing = StandingSummary(circles.policy.Tier.NEWCOMER, "0.0", emptyList(), emptyList()))).copy(screen = Screen.Standing))

    @Test fun theStandingPageOffersToLogAContributionAndListsNotesToConfirm() {
        val note = circles.app.NoteSummary(circles.event.NoteId("d".repeat(32)), circles.event.ContributionKind.SKILL_SHARE_HELPED, bea, "fixed the gate", 20_759, circles.app.NoteStatus.WAITING)
        val html = standingPage(circles.app.ContributionsSummary(toConfirm = listOf(note), mine = listOf(note.copy(status = circles.app.NoteStatus.COUNTED)), candidates = listOf(bea), notesLeft = 2))
        assertTrue("Waiting for your confirmation" in html && "says they helped you with a skill." in html && "fixed the gate" in html)
        assertTrue("""$ATTR_ACTION="$ACTION_NOTE_CONFIRM" $ATTR_NOTE_ID="${note.note.v}"""" in html)
        assertTrue("""id="$FORM_NOTE_ADD"""" in html && "You can make 2 more in the next 30 days." in html)
        assertTrue("I mentored them as a newcomer" in html)
        assertTrue("confirmed and counted." in html)
    }

    @Test fun theFormIsHiddenWhenNoNotesAreLeftOrThereIsNoOneToName() {
        assertFalse(FORM_NOTE_ADD in standingPage(circles.app.ContributionsSummary(candidates = listOf(bea), notesLeft = 0)))
        assertFalse("Log a contribution" in standingPage(circles.app.ContributionsSummary(candidates = emptyList(), notesLeft = 3)))
        assertFalse("Waiting for your confirmation" in standingPage(circles.app.ContributionsSummary(candidates = listOf(bea), notesLeft = 3)))
    }

    // ---- skill offers (ADR 0055) ----

    private fun skillLine(name: String, details: String, mine: Boolean, vararg terms: circles.event.OpenTo) =
        circles.app.SkillSummary(circles.event.SkillId((if (mine) "1" else "2").repeat(32)), name, details, terms.toSet(), if (mine) me else bea, mine)

    private fun skillsPage(vararg skills: circles.app.SkillSummary, canRead: Boolean = true) =
        render(open(home(canRead = canRead).copy(skills = skills.toList(), people = listOf(MemberSummary(bea, isMe = false, isSteward = false, devices = 1, fingerprint = "ab12 cd34")))).copy(screen = Screen.Skills))

    @Test fun theSkillsPageListsOffersWithSearchWhoToAskAndHowToCheckThem() {
        val html = skillsPage(skillLine("Bike repair", "Punctures and brakes", false, circles.event.OpenTo.GIFT, circles.event.OpenTo.THANKS), skillLine("Bread baking", "", true))
        assertTrue("2 offers" in html && """id="$ID_SKILL_SEARCH"""" in html && "Search: " in html)
        assertTrue("""$ATTR_SKILL_TEXT="bike repair punctures and brakes"""" in html)
        assertTrue("Happy to help for nothing. A thank-you is enough." in html)
        assertTrue("Check who they are: ab12 cd34" in html)
        assertTrue("Offered by you" in html && """$ATTR_ACTION="$ACTION_EDIT_SKILL"""" in html && """$ATTR_ACTION="$ACTION_RETIRE_SKILL"""" in html)
        assertTrue("The app does not send messages" in html)
        assertEquals(1, Regex("""$ATTR_ACTION="$ACTION_EDIT_SKILL"""").findAll(html).count(), "only on my own offer")
    }

    @Test fun theSkillsPageHasEmptyAndNotYetLetInStates() {
        assertTrue("Nobody has offered a skill yet." in skillsPage())
        assertFalse(ID_SKILL_SEARCH in skillsPage())
        assertTrue("A steward needs to let you in" in skillsPage(canRead = false))
        assertTrue(Screen.AddSkill.fragment in skillsPage())
        assertFalse(Screen.AddSkill in Screen.navigation || Screen.EditSkill in Screen.navigation)
        assertTrue(Screen.Skills in Screen.navigation)
    }

    @Test fun theSkillFormWaitsForANameAndPreselectsWhatWasChosen() {
        val add = render(open(home()).copy(screen = Screen.AddSkill))
        assertTrue("""id="$FORM_SKILL_ADD"""" in add && "What you can help with: " in add)
        val button = Regex("<button[^>]*id=\"$SUBMIT_ID\"[^>]*>").find(add.substringAfter(FORM_SKILL_ADD))?.value ?: error("no button")
        assertTrue("hidden" in button)
        assertFalse(Regex("""\schecked""").containsMatchIn(add.substringAfter("What would be welcome")))
        val edit = render(open(home().copy(skills = listOf(skillLine("Bike repair", "x", true, circles.event.OpenTo.SWAP)))).copy(screen = Screen.EditSkill, editingSkill = circles.event.SkillId("1".repeat(32))))
        assertTrue("""id="$FORM_SKILL_EDIT"""" in edit && """value="Bike repair"""" in edit)
        assertEquals(1, Regex("""\schecked""").findAll(edit.substringAfter("What would be welcome")).count())
        assertEquals(Screen.Skills, reduce(open(), AppAction.Navigate(Screen.EditSkill)).screen, "no offer chosen, so back to the list")
    }

    @Test fun everyPassphraseFieldHasAShowHideButtonAndPagesOfferPasswordManagersAUsername() {
        for (html in listOf(newcomer(Screen.StartCircle), newcomer(Screen.JoinCircle, link), render(AppState(screen = Screen.SignIn, model = AppModel.Locked(circle))))) {
            assertEquals(1, Regex("""$ATTR_ACTION="$ACTION_TOGGLE_SECRET"""").findAll(html).count())
            assertTrue("""$ATTR_TARGET="$FIELD_PASSPHRASE"""" in html && """aria-label="Show passphrase"""" in html && """aria-pressed="false"""" in html)
            assertFalse(">Show<" in html, "an icon, not a word")
            assertTrue("""autocomplete="username"""" in html && USERNAME_FOR_PASSWORD_MANAGERS in html)
        }
        val settings = render(open(home()).copy(screen = Screen.Settings))
        assertEquals(2, Regex("""$ATTR_ACTION="$ACTION_TOGGLE_SECRET"""").findAll(settings).count(), "current and new passphrase")
        assertFalse("You type it each time" in newcomer(Screen.StartCircle))
        assertTrue("password manager" in newcomer(Screen.StartCircle))
    }
}
