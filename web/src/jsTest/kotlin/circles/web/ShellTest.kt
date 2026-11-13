package circles.web

import circles.app.AppModel
import circles.app.ArchivedItem
import circles.app.HomeData
import circles.app.PauseHistoryEntry
import circles.app.InviteLink
import circles.app.LoanRole
import circles.app.MemberSummary
import circles.app.NameSummary
import circles.app.nameTakenMessage
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
/** A day in the default (long) date format, which every rendered page uses unless the state says otherwise (ADR 0080). */
private fun ld(day: Int): String = circles.app.DateFormats.show(day, circles.app.DateFormat.LONG)

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
    archived: List<ArchivedItem> = emptyList(),
    people: List<MemberSummary> = emptyList(),
    myName: String? = "Alice",
    names: Map<MemberId, NameSummary> = mapOf(me to NameSummary("Alice", confirmed = true), bea to NameSummary("Bea", confirmed = false)),
) = HomeData(
    circle, me, steward, canRead, members, waiting, changesWaiting = 0, refusedChanges = refused, items = items, archivedItems = archived, loans = loans, today = 20_759, people = people,
    myName = myName, names = if (canRead) names else emptyMap(),
)

private val ladder = ItemSummary(ItemId("1".repeat(32)), "Ladder", ItemClass.EVERYDAY, me, ownedByMe = true, description = "Garage at the back")
private val drill = ItemSummary(ItemId("2".repeat(32)), "Drill", ItemClass.VALUABLE, bea, ownedByMe = false, description = null, access = AccessSummary(true, emptyList(), emptyList()))
private val locked = drill.copy(
    item = ItemId("3".repeat(32)), name = "Trailer", itemClass = ItemClass.HIGH_VALUE,
    access = AccessSummary(false, listOf("Trailer needs Level 3; you are Level 1."), listOf("Or get 2 members at Level 2 or above to vouch for you.")),
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
        assertEquals(Screen.entries.size - Screen.beforeSignIn.size - Screen.subPages.size - Screen.accountMenu.size, Screen.navigation.size)
        assertEquals(setOf(Screen.Welcome, Screen.StartCircle, Screen.JoinCircle, Screen.PairDevice, Screen.Restore), Screen.signedOut)
    }
}

class ReduceTest {
    @Test fun aBrowserWithNoIdentityMayOnlySeeTheSignedOutPages() {
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
        assertEquals("The server refused the request (error 403). The server did not say why, 1 change waiting", describe(SyncState.Problem("The server refused the request (error 403). The server did not say why.", 1)))
    }
}

class ShellViewTest {
    private fun render(state: AppState): String = createHTML().div { shell(state) }

    @Test fun loadingShowsAMessageAndNoNavigation() {
        val html = render(AppState())
        assertTrue("Opening your circle" in html)
        assertFalse("<nav" in html)
    }

    private fun signedOut(screen: Screen, invite: InviteLink? = null) = render(AppState(screen = screen, model = AppModel.NoIdentity, pendingInvite = invite))

    @Test fun theWelcomePageOffersTwoChoicesAndNoForm() {
        val html = signedOut(Screen.Welcome)
        assertTrue("""href="${Screen.StartCircle.fragment}"""" in html)
        assertTrue("""href="${Screen.JoinCircle.fragment}"""" in html)
        assertFalse("<form" in html)
        assertFalse("<nav" in html)
        assertFalse("<button" in html, "no submit button belongs on the welcome page")
    }

    @Test fun startingACircleHasItsOwnPageWithOnlyThePassphraseForm() {
        val html = signedOut(Screen.StartCircle)
        assertTrue("""id="$FORM_START"""" in html)
        assertFalse("""id="$FORM_LINK"""" in html)
        assertFalse("""id="$FORM_JOIN"""" in html)
        assertTrue("Start a circle" in html)
        assertTrue("""href="${Screen.Welcome.fragment}"""" in html)
    }

    @Test fun joiningAsksForTheLinkFirstAndThenForAPassphrase() {
        val first = signedOut(Screen.JoinCircle)
        assertTrue("""id="$FORM_LINK"""" in first)
        assertFalse("""id="$FORM_JOIN"""" in first)
        assertFalse("Start a circle" in first, "the join page has no start button")
        val second = signedOut(Screen.JoinCircle, link)
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
            signedOut(Screen.StartCircle), signedOut(Screen.JoinCircle), signedOut(Screen.JoinCircle, link),
            render(AppState(screen = Screen.SignIn, model = AppModel.Locked(circle))),
            render(open().copy(screen = Screen.Members, newInvite = "https://x")),
            render(open().copy(screen = Screen.AddItem)),
            render(open(home(items = listOf(drill))).copy(screen = Screen.RequestLoan, borrowing = drill.item)),
            settings(),
            stewards(true, listOf(meSteward)),
            borrow(loan("d", LoanRole.BORROWER, LoanStatus.ACTIVE)),
            borrow(returned(LoanRole.OWNER, { it.copy(canDispute = true) })),
            render(open(home(myName = null)).copy(screen = Screen.YourName)),
        )
        var labels = 0
        for (html in pages) {
            for (match in Regex("<label[^>]*>([\\s\\S]*?)</label>").findAll(html.replace(Regex("<div class=\"check-row\">[\\s\\S]*?</div>"), ""))) {
                labels++
                assertTrue(match.groupValues[1].endsWith(": "), "label text was '${match.groupValues[1]}'")
            }
        }
        assertEquals(8 + 3 + 1 + 3 + 2 + 4 + 4 + 9 + 1 - 1 + 2 + 2 + 1, labels) // + the name on Settings and on the first-name page (ADR 0068) + the date format on Settings (ADR 0080)
    }

    @Test fun everyPassphraseButtonStartsHiddenUntilThePassphraseIsLongEnough() {
        val pages = listOf(
            signedOut(Screen.StartCircle), signedOut(Screen.JoinCircle, link),
            render(AppState(screen = Screen.SignIn, model = AppModel.Locked(circle))),
        )
        for (html in pages) {
            val button = Regex("<button[^>]*id=\"$SUBMIT_ID\"[^>]*>").find(html)?.value ?: error("no submit button in $html")
            assertTrue("hidden" in button, button)
        }
    }

    @Test fun theLinkFormButtonAlsoWaitsForSomeInput() {
        val html = signedOut(Screen.JoinCircle)
        val button = Regex("<button[^>]*id=\"$SUBMIT_ID\"[^>]*>").find(html)?.value ?: error("no button")
        assertTrue("hidden" in button)
        assertTrue("""$ATTR_MIN="1"""" in html)
        assertTrue("Continue" in html)
    }

    @Test fun theMinimumLengthOfEveryRequiredFieldIsMarked() {
        assertTrue("""$ATTR_MIN="12"""" in signedOut(Screen.StartCircle))
        assertTrue("""$ATTR_MIN="1"""" in render(open().copy(screen = Screen.AddItem)))
    }

    @Test fun passphraseFieldsAreLabelledMinLengthAndNeverAutofilledAsAnotherSite() {
        val join = signedOut(Screen.JoinCircle, link)
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
        assertTrue("A steward needs to approve your access" in render(open(home(steward = false, canRead = false))))
        assertFalse("A steward needs to approve your access" in render(open(home(canRead = true))))
    }

    @Test fun homeSummarisesAndLinksToThePagesThatDoTheWork() {
        val html = render(open(home(members = 2, items = listOf(ladder, drill), loans = listOf(loan("a", LoanRole.OWNER, LoanStatus.REQUESTED)), waiting = listOf(WaitingDevice(beaDevice, bea, "5555 6666 7777 8888 9999")))))
        assertTrue(Regex("""<a href="#/members">2 members</a>""").containsMatchIn(html))
        assertTrue(Regex("""<a href="#/items">2 items shared</a>""").containsMatchIn(html))
        assertTrue(Regex("""<a href="#/borrow">1 request waiting for your answer</a>""").containsMatchIn(html))
        assertTrue(Regex("""<a href="#/members">1 person waiting for approval</a>""").containsMatchIn(html))
    }

    @Test fun homeCountsPeopleWaitingForApprovalInTheSingularAndThePlural() {
        val one = listOf(WaitingDevice(beaDevice, bea, "5555 6666"))
        val two = one + WaitingDevice(DeviceId("e".repeat(32)), MemberId("c".repeat(32)), "7777 8888")
        assertTrue(Regex("""<a href="#/members">1 person waiting for approval</a>""").containsMatchIn(render(open(home(waiting = one)))))
        assertTrue(Regex("""<a href="#/members">2 people waiting for approval</a>""").containsMatchIn(render(open(home(waiting = two)))))
        assertFalse("waiting for approval" in render(open(home())), "nothing waiting, no link")
        assertFalse("waiting for approval" in render(open(home(steward = false, waiting = one))), "only a steward is told")
    }

    @Test fun homeCountsOneMemberInTheSingular() {
        assertTrue(">1 member</a>" in render(open(home(members = 1))))
    }

    @Test fun homeHasNoActionsOfItsOwn() {
        val html = render(open(home(waiting = listOf(WaitingDevice(beaDevice, bea, "5555 6666 7777 8888 9999")))).copy(newInvite = "https://x"))
        for (action in listOf(ACTION_INVITE, ACTION_CHECK_PERSON, ACTION_COPY)) assertFalse("""$ATTR_ACTION="$action"""" in html, action)
    }

    @Test fun homeHidesTheWaitingLineFromPeopleWhoAreNotStewardsAndTheItemsLineBeforeTheyCanRead() {
        val html = render(open(home(steward = false, canRead = false, waiting = listOf(WaitingDevice(beaDevice, bea, "5555 6666 7777 8888 9999")))))
        assertFalse("waiting for approval" in html)
        assertFalse("items shared" in html)
    }

    private fun members(home: HomeData, newInvite: String? = null) = render(open(home).copy(screen = Screen.Members, newInvite = newInvite))

    private val aliceSummary = MemberSummary(me, isMe = true, isSteward = true, devices = 2, fingerprint = "ab12 cd34 ef56 7890 1234")
    private val beaSummary = MemberSummary(bea, isMe = false, isSteward = false, devices = 1, fingerprint = "1111 2222 3333 4444 5555")

    @Test fun theMembersPageListsEveryoneWithRoleAndDevicesButNoFingerprintOnTheCards() {
        val html = members(home(people = listOf(aliceSummary, beaSummary)))
        assertTrue("(you)" in html && ">Bea<" in html && NAME_NOT_CONFIRMED in html && NAME_CONFIRMED in html)
        assertTrue("Steward, 2 devices" in html)
        assertTrue("1 device<" in html)
        // Only my own QR disclosure carries characters (mine); nobody else's are on the page (ADR 0071).
        assertFalse("1111 2222 3333 4444 5555" in html)
        assertEquals("ab12 cd34 ef56 7890 1234", html.substringAfter("<summary>Show my QR code</summary>").substringAfter("""<p class="fingerprint">""").substringBefore("</p>"))
        assertFalse("Key fingerprint" in html)
        assertTrue("choose Check identity on their card" in html)
        assertTrue("""$ATTR_ACTION="$ACTION_CHECK_PERSON" $ATTR_MEMBER="${bea.v}">Check identity<""" in html)
        assertEquals(1, Regex("""$ATTR_ACTION="$ACTION_CHECK_PERSON"""").findAll(html).count(), "not for myself")
    }

    @Test fun theMembersPageOffersAnInviteAndShowsTheMadeLinkReadOnly() {
        val html = members(home(), newInvite = "https://circles.example/#/join/x")
        assertTrue("""$ATTR_ACTION="$ACTION_INVITE"""" in html)
        assertTrue("""value="https://circles.example/#/join/x"""" in html)
        assertTrue("readonly" in html)
        assertTrue("""$ATTR_ACTION="$ACTION_COPY"""" in html)
        assertFalse("""$ATTR_ACTION="$ACTION_COPY"""" in members(home()))
    }

    @Test fun onlyStewardsSeeWhoIsWaitingAndGoToTheCheckToLetThemIn() {
        val waiting = listOf(WaitingDevice(beaDevice, bea, "5555 6666 7777 8888 9999"))
        val steward = members(home(steward = true, waiting = waiting))
        val line = steward.substringAfter("Waiting for approval")
        assertTrue(""">Check and approve access<""" in line && """$ATTR_DEVICE="${beaDevice.v}"""" in line && """$ATTR_MEMBER="${bea.v}"""" in line)
        assertTrue(">Bea<" in line)
        assertFalse("5555 6666" in steward, "no characters on the waiting line")
        assertFalse(FORM_LET_IN_PREFIX in steward, "letting in happens on the check page, not here")
        assertFalse("Check and approve access" in members(home(steward = false, waiting = waiting)))
        assertFalse("Waiting for approval" in members(home(steward = true)))
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

    @Test fun theAppearanceChoicesShowTheSavedOnesAsSelected() {
        val html = render(open().copy(screen = Screen.Settings, appearance = Appearance(TextSize.LARGE, ThemeChoice.DARK)))
        val form = html.substringAfter(FORM_APPEARANCE).substringBefore("</form>")
        assertTrue("Text size: " in form && "Colours: " in form)
        assertTrue(Regex("""<option[^>]*value="large"[^>]*selected""").containsMatchIn(form))
        assertTrue(Regex("""<option[^>]*value="dark"[^>]*selected""").containsMatchIn(form))
        assertEquals(3, Regex("<option[^>]*selected").findAll(form).count(), "text size, colours and the date format")
        assertTrue("Date format: " in form)
    }

    @Test fun unknownStoredAppearanceFallsBackToTheDefaults() {
        assertEquals(Appearance(), Appearance.fromKeys(null, null))
        assertEquals(Appearance(), Appearance.fromKeys("huge", "purple"))
        assertEquals(Appearance(TextSize.LARGER, ThemeChoice.LIGHT), Appearance.fromKeys("larger", "light"))
    }

    @Test fun anAppearanceChangeIsKeptByTheReducer() {
        val chosen = Appearance(TextSize.LARGE)
        assertEquals(chosen, reduce(AppState(), AppAction.AppearanceChanged(chosen)).appearance)
    }

    private fun earlier(status: circles.event.PauseStatus, started: Int?, ended: Int, role: circles.app.PauseRole = circles.app.PauseRole.REVIEWER) = PauseHistoryEntry(
        circles.event.PauseId("p1"), role, circles.policy.MemberId("0123456789abcdef"), "left things damaged", 30, status, proposedDay = 20_000, startedDay = started, endedDay = ended, appeal = null,
    )

    @Test fun earlierPausesAreListedWithTheirDatesAndHowTheyEnded() {
        val lifted = pauseHistoryLine(earlier(circles.event.PauseStatus.LIFTED, 20_001, 20_005))
        assertTrue("lifted on" in lifted && "paused from" in lifted, lifted)
        assertTrue("cancelled on" in pauseHistoryLine(earlier(circles.event.PauseStatus.LIFTED, null, 20_002)))
        assertTrue("ended on its date" in pauseHistoryLine(earlier(circles.event.PauseStatus.ENDED, 20_001, 20_030)))
        assertTrue("nothing happened" in pauseHistoryLine(earlier(circles.event.PauseStatus.LAPSED, null, 20_014)))
        val html = render(open(home().copy(pauseHistory = listOf(earlier(circles.event.PauseStatus.ENDED, 20_001, 20_030)))).copy(screen = Screen.Pauses))
        assertTrue("Earlier pauses" in html && "left things damaged" in html && UNNAMED_MEMBER in html)
        assertFalse("Earlier pauses" in render(open(home()).copy(screen = Screen.Pauses)), "no heading when there is no history")
    }

    @Test fun theExportNoticeCountsPhotosAndSaysWhenSomeWereMissing() {
        fun ready(photos: Int, missing: Int) = circles.app.ExportResult.Ready("f.zip", ByteArray(0), 12, photos, missing)
        assertEquals("Downloaded f.zip with 12 events.", exportNotice(ready(0, 0)))
        assertEquals("Downloaded f.zip with 12 events and 4 photo files.", exportNotice(ready(4, 0)))
        assertTrue("2 photo files could not be fetched" in exportNotice(ready(4, 2)))
    }

    @Test fun theOpeningScreenShowsASpinnerWithAStatusRole() {
        val html = render(AppState(model = AppModel.Loading))
        assertTrue(Regex("""<p[^>]*class="loading"[^>]*role="status"|<p[^>]*role="status"[^>]*class="loading"""").containsMatchIn(html), html)
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
        assertTrue("A steward needs to approve your access" in html)
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
        assertTrue("""Shared by <a href="#/members/person/${bea.v}" class="person-name">Bea</a>""" in html)
        assertTrue("Details: Garage at the back" in html)
        assertEquals(1, Regex("""$ATTR_ACTION="$ACTION_EDIT_ITEM"""").findAll(html).count())
        assertEquals(1, Regex("""$ATTR_ACTION="$ACTION_RETIRE_ITEM"""").findAll(html).count())
        assertTrue("""$ATTR_ITEM="${ladder.item.v}"""" in html)
        // Someone else's item may be asked for, but is never offered for change or removal (the counts above are 1).
        assertEquals(1, Regex("Details:").findAll(html).count(), "someone else's item shows no details")
    }

    @Test fun archivingAnItemNeedsASecondDeliberateClick() {
        val html = items(home(items = listOf(ladder)))
        assertTrue(html.indexOf("<details") < html.indexOf(ACTION_RETIRE_ITEM))
        assertTrue("Archive this item" in html && "Remove from the list" !in html)
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
        assertTrue("A steward needs to approve your access" in render(open(home(canRead = false)).copy(screen = Screen.Borrow)))
        val empty = render(open(home()).copy(screen = Screen.Borrow))
        assertTrue("No loans yet." in empty)
        assertTrue("""href="${Screen.Items.fragment}"""" in empty)
    }

    private fun borrow(vararg loans: LoanSummary) = render(open(home(loans = loans.toList())).copy(screen = Screen.Borrow))

    @Test fun anOwnerSeesRequestsWithTheNoteAndCanApproveOrDecline() {
        val html = borrow(loan("a", LoanRole.OWNER, LoanStatus.REQUESTED, note = "for the fence"))
        assertTrue("Requests to answer" in html)
        assertTrue("Note: for the fence" in html)
        assertTrue("3 Nov 2026 to 5 Nov 2026" in html)
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
        assertTrue("The borrower will report it back" in theirs)
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
        assertTrue("Trailer needs Level 3; you are Level 1." in html)
        assertTrue("Or get 2 members at Level 2 or above to vouch for you." in html)
        assertTrue("Why, and how to unlock" in html)
    }

    private val myStanding = StandingSummary(Tier.REGULAR, "3.5", listOf("Your activity in this circle gives you 3.5 points."), listOf("About 6.5 more points opens Level 3 access."))

    @Test fun theStandingPageShowsOnlyYourOwnTierPointsReasonsAndNextSteps() {
        val html = render(open(home().copy(standing = myStanding)).copy(screen = Screen.Standing))
        assertTrue("Your level: 2" in html && "Your points: 3.5" in html && "Your points and the vouches you have decide what you can borrow." in html)
        assertTrue("It never drops below Level 1," in html)
        assertTrue("Your activity in this circle gives you 3.5 points." in html)
        assertTrue("About 6.5 more points opens Level 3 access." in html)
        assertTrue("nobody is ever banned for a low level" in html)
        assertFalse("This screen is not built yet." in html)
    }

    @Test fun theStandingPageBeforeTheCircleIsReadSaysSo() {
        assertTrue("appears once the circle has been read" in render(open(home()).copy(screen = Screen.Standing)))
    }

    private fun checkPage(home: HomeData, check: PersonCheck) = render(open(home).copy(screen = Screen.CheckIdentity, checking = check))

    @Test fun membersWhoMayVouchSeeAButtonOnTheCheckPageForPeopleTheyHaveNotVouchedFor() {
        val mine = MemberSummary(me, isMe = true, isSteward = true, devices = 1, fingerprint = "aaaa aaaa aaaa aaaa aaaa")
        val theirs = beaSummary
        assertFalse("""$ATTR_ACTION="$ACTION_VOUCH"""" in members(home(people = listOf(mine, theirs)).copy(canVouch = true)), "not on the card (ADR 0071)")
        val html = checkPage(home(people = listOf(mine, theirs)).copy(canVouch = true), PersonCheck(bea))
        assertEquals(1, Regex("""$ATTR_ACTION="$ACTION_VOUCH"""").findAll(html).count())
        assertTrue("""$ATTR_MEMBER="${bea.v}"""" in html && "2 vouches from members at Level 2 or above give a new member Level 2 access." in html)
        val already = home(people = listOf(mine, theirs.copy(vouchedByMeUntil = 20_939))).copy(canVouch = true)
        assertFalse("""$ATTR_ACTION="$ACTION_VOUCH"""" in checkPage(already, PersonCheck(bea)))
        assertTrue("You vouch for them until 1 May 2027." in checkPage(already, PersonCheck(bea)))
        assertTrue("You vouch for them until 1 May 2027." in members(already), "the card still says so")
        assertFalse("""$ATTR_ACTION="$ACTION_VOUCH"""" in checkPage(home(people = listOf(mine, theirs)).copy(canVouch = false), PersonCheck(bea)), "new members see no vouch button")
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
        assertTrue("Steward since 2 Nov 2026" in html)
        assertTrue("Can vote from 9 Nov 2026" in html)
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
        assertTrue(">$UNNAMED_MEMBER</a> a steward" in html && "Make <a" in html)
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
        assertTrue(">Bea</a> as a steward" in stewards(true, listOf(meSteward, beaSteward), listOf(removal)))
    }

    @Test fun onlyStewardsSeeWhoCanBeProposedAndTheRulesForm() {
        val people = listOf(
            MemberSummary(me, isMe = true, isSteward = true, devices = 1, fingerprint = "x"),
            MemberSummary(bea, isMe = false, isSteward = false, devices = 1, fingerprint = "y", displayName = "Bea", nameConfirmedBy = listOf(me)),
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
        assertTrue("cuts off all of their devices at once and changes the circle's key" in html)
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
        val without = signedOut(Screen.PairDevice)
        assertFalse("""id="$FORM_PAIR"""" in without)
        assertTrue("open the link shown on your other device" in without)
        val withLink = render(AppState(screen = Screen.PairDevice, model = AppModel.NoIdentity, pendingDevice = deviceLink))
        assertTrue("""id="$FORM_PAIR"""" in withLink)
        assertTrue("""$ATTR_MIN="12"""" in withLink && "new-password" in withLink)
        val button = Regex("<button[^>]*id=\"$SUBMIT_ID\"[^>]*>").find(withLink)?.value ?: error("no button")
        assertTrue("hidden" in button)
        assertFalse("Start a circle" in withLink)
    }

    @Test fun aWaitingDeviceShowsItsCodeAndTheCharactersToCompareAndCanCheckOrForgetItself() {
        val html = render(AppState(screen = Screen.PairDevice, model = waiting))
        assertTrue("""value="${waiting.code}"""" in html && "readonly" in html)
        assertTrue("Device code: " in html)
        assertTrue("They must match these exactly:" in html && """<p class="fingerprint">ab12 cd34 ef56 7890 1234</p>""" in html)
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
        assertTrue("changes the circle's key" in html)
    }

    @Test fun settingsShowsTheDeviceLinkAndTheAddDeviceFormWhichLeadsToTheCheck() {
        val html = settingsWith(emptyList())
        assertTrue("""value="https://circles.example/#/device/${circle.v}/${me.v}"""" in html)
        assertTrue("""id="$FORM_ADD_DEVICE"""" in html)
        for (label in listOf("Link: ", "Device code: ", "Name of the device: ")) assertTrue(label in html, label)
        assertFalse("""type="checkbox"""" in html.substringAfter(FORM_ADD_DEVICE).substringBefore("</form>"), "the tick is on the check page")
        assertFalse("fingerprint" in html, "no characters on Settings (ADR 0071)")
        assertTrue("""maxlength="40"""" in html)
        val button = Regex("<button[^>]*id=\"$SUBMIT_ID\"[^>]*>Next<").find(html.substringAfter(FORM_ADD_DEVICE))?.value ?: error("no Next button")
        assertTrue("hidden" in button)
    }

    @Test fun theNewDeviceCheckShowsTheCharactersAndAddsOnlyAfterTheTick() {
        val adding = DeviceToAdd("code", "Phone", "ab12 cd34 ef56 7890 1234")
        val state = reduce(open(home()).copy(screen = Screen.Settings), AppAction.CheckNewDevice(adding))
        assertEquals(Screen.CheckDevice, state.screen)
        val html = render(state)
        assertTrue("Adding: Phone" in html && """<p class="fingerprint">ab12 cd34 ef56 7890 1234</p>""" in html)
        val form = html.substringAfter("""id="$FORM_CONFIRM_DEVICE"""").substringBefore("</form>")
        assertTrue("""$ATTR_MUST_CHECK="true"""" in form && "These characters match the ones on the new device" in form)
        assertTrue(Regex("<button[^>]*hidden[^>]*>Add this device<").containsMatchIn(form), "the button waits for the tick")
        assertEquals(null, reduce(state, AppAction.Navigate(Screen.Settings)).addingDevice, "leaving forgets the pasted code")
        assertEquals(Screen.Settings, reduce(open(home()), AppAction.Navigate(Screen.CheckDevice)).screen, "nothing pasted, back to settings")
        assertEquals(null, reduce(state, AppAction.ModelChanged(AppModel.Locked(circle))).addingDevice, "locking forgets it")
        assertFalse(Screen.CheckDevice in Screen.navigation)
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
        assertTrue("""<ol class="words">""" in html)
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
        val html = signedOut(Screen.JoinCircle, link)
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
        assertTrue("Points for Level 2, Level 3 and Level 4: 3.0, 10.0 and 25.0" in html)
        assertTrue("Vouches needed to reach Level 2: 2," in html)
        assertTrue("Proposed: points for levels 2, 3 and 4 3.0 / 10.0 / 25.0," in html)
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
        assertTrue(""">Level 2 or above<""" in html && """>Level 3 or above<""" in html)
        // The option values stay the internal names (ADR 0070): only the words people read changed.
        assertTrue("""value="REGULAR"""" in html && """value="TRUSTED"""" in html)
        assertFalse("Level 1 or above" in html)
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
        render(open(home().copy(contributions = c, standing = StandingSummary(circles.policy.Tier.NEW, "0.0", emptyList(), emptyList()))).copy(screen = Screen.Standing))

    @Test fun theStandingPageOffersToLogAContributionAndListsNotesToConfirm() {
        val note = circles.app.NoteSummary(circles.event.NoteId("d".repeat(32)), circles.event.ContributionKind.SKILL_SHARE_HELPED, bea, "fixed the gate", 20_759, circles.app.NoteStatus.WAITING)
        val html = standingPage(circles.app.ContributionsSummary(toConfirm = listOf(note), mine = listOf(note.copy(status = circles.app.NoteStatus.COUNTED)), candidates = listOf(bea), notesLeft = 2))
        assertTrue("Waiting for your confirmation" in html && "says they helped you with a skill." in html && "fixed the gate" in html)
        assertTrue("""$ATTR_ACTION="$ACTION_NOTE_CONFIRM" $ATTR_NOTE_ID="${note.note.v}"""" in html)
        assertTrue("""id="$FORM_NOTE_ADD"""" in html && "You can make 2 more in the next 30 days." in html)
        assertTrue("I mentored them as a new member" in html)
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
        assertFalse("ab12 cd34" in html, "no characters on skill offers (ADR 0071)")
        assertTrue("Offered by you" in html && """$ATTR_ACTION="$ACTION_EDIT_SKILL"""" in html && """$ATTR_ACTION="$ACTION_RETIRE_SKILL"""" in html)
        assertTrue("The app does not send messages" in html)
        assertEquals(1, Regex("""$ATTR_ACTION="$ACTION_EDIT_SKILL"""").findAll(html).count(), "only on my own offer")
    }

    @Test fun theSkillsPageHasEmptyAndNotYetLetInStates() {
        assertTrue("Nobody has offered a skill yet." in skillsPage())
        assertFalse(ID_SKILL_SEARCH in skillsPage())
        assertTrue("A steward needs to approve your access" in skillsPage(canRead = false))
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
        for (html in listOf(signedOut(Screen.StartCircle), signedOut(Screen.JoinCircle, link), render(AppState(screen = Screen.SignIn, model = AppModel.Locked(circle))))) {
            assertEquals(1, Regex("""$ATTR_ACTION="$ACTION_TOGGLE_SECRET"""").findAll(html).count())
            assertTrue("""$ATTR_TARGET="$FIELD_PASSPHRASE"""" in html && """aria-label="Show passphrase"""" in html && """aria-pressed="false"""" in html)
            assertFalse(">Show<" in html, "an icon, not a word")
            assertTrue("""autocomplete="username"""" in html && USERNAME_FOR_PASSWORD_MANAGERS in html)
        }
        val settings = render(open(home()).copy(screen = Screen.Settings))
        assertEquals(2, Regex("""$ATTR_ACTION="$ACTION_TOGGLE_SECRET"""").findAll(settings).count(), "current and new passphrase")
        assertFalse("You type it each time" in signedOut(Screen.StartCircle))
        assertTrue("password manager" in signedOut(Screen.StartCircle))
    }

    @Test fun theHeaderHasIconOnlySyncAndLockButtonsWithSpokenNames() {
        val html = render(open())
        for ((action, name) in listOf(ACTION_SYNC to "Sync now", ACTION_LOCK to "Lock now")) {
            assertTrue("""$ATTR_ACTION="$action"""" in html && """aria-label="$name"""" in html && """title="$name"""" in html)
            assertFalse(">$name<" in html, "an icon, not words")
        }
        assertTrue("icon-sync" in html && "icon-lock" in html)
        assertFalse("tool-button" in render(AppState(screen = Screen.Welcome, model = AppModel.NoIdentity)), "nothing to sync or lock before signing in")
    }

    @Test fun theWelcomePageOffersTwoButtonsAndMainActionsAreButtons() {
        val welcome = render(AppState(screen = Screen.Welcome, model = AppModel.NoIdentity))
        assertTrue("""class="button primary"""" in welcome && "Start a circle" in welcome && "Join a circle" in welcome)
        assertFalse("<ul>" in welcome.substringAfter("<main"))
        assertTrue("""class="button primary"""" in render(open(home()).copy(screen = Screen.Items)))
    }

    // ---- return reminders (ADR 0059) ----

    @Test fun dueLinesUseKindPlainWords() {
        DateText.current = circles.app.DateFormat.LONG
        assertEquals("Due back today (4 Nov 2026).", dueLine(20_761, 20_761))
        assertEquals("Due back in 1 day, on 5 Nov 2026.", dueLine(20_762, 20_761))
        assertEquals("Due back in 3 days, on 7 Nov 2026.", dueLine(20_764, 20_761))
        assertEquals("Back was expected 2 days ago, on 2 Nov 2026.", dueLine(20_759, 20_761))
        assertEquals("Back was expected 1 day ago, on 3 Nov 2026.", dueLine(20_760, 20_761))
    }

    @Test fun theBorrowPageShowsWhenThingsAreDueAndTheOwnerGetsAnInPersonNudgeOnlyWhenLate() {
        val late = loan("a", LoanRole.OWNER, LoanStatus.ACTIVE, mine = true, theirs = true)
        val html = render(open(home(people = listOf(MemberSummary(bea, isMe = false, isSteward = false, devices = 1, fingerprint = "ab12 cd34"))).copy(loans = listOf(late), today = 20_763)).copy(screen = Screen.Borrow))
        assertTrue("Back was expected 1 day ago" in html && "Ask them to return it" in html && "Lendger sends nothing" in html)
        assertFalse("ab12 cd34" in html, "no characters in the reminder (ADR 0071)")
        val notYet = render(open(home().copy(loans = listOf(late), today = 20_761)).copy(screen = Screen.Borrow))
        assertTrue("Due back in 1 day" in notYet)
        assertFalse("Ask them to return it" in notYet)
        val borrowerLate = render(open(home().copy(loans = listOf(loan("b", LoanRole.BORROWER, LoanStatus.ACTIVE, mine = true, theirs = true)), today = 20_763)).copy(screen = Screen.Borrow))
        assertFalse("Ask them to return it" in borrowerLate, "the borrower is not nudged")
    }

    @Test fun homeCountsLoansDueTodayOrPastTheirDayAndTheTitleSaysSo() {
        val active = loan("a", LoanRole.BORROWER, LoanStatus.ACTIVE, mine = true, theirs = true)
        val pending = loan("b", LoanRole.BORROWER, LoanStatus.APPROVED)
        assertEquals(0, dueCount(home().copy(loans = listOf(active, pending), today = 20_761)))
        assertEquals(1, dueCount(home().copy(loans = listOf(active, pending), today = 20_762)))
        assertEquals(1, dueCount(home().copy(loans = listOf(active, pending), today = 20_800)))
        assertTrue("1 loan is due back today or past its day" in render(open(home().copy(loans = listOf(active), today = 20_763))))
        assertFalse("due back today" in render(open(home().copy(loans = listOf(active), today = 20_761))))
    }

    // ---- item photos (ADR 0060) ----

    private fun photoOf(n: Int, alt: String = "Front view") = circles.event.ItemPhoto(n.toString(16).padStart(64, 'a'), n.toString(16).padStart(64, 'b'), 1, alt)

    @Test fun theItemListShowsThumbnailsWithDescriptionsAndLargerPhotosBehindADisclosure() {
        val item = ItemSummary(ladder.item, "Ladder", ItemClass.EVERYDAY, me, true, null, photos = listOf(photoOf(1), photoOf(2, "Hinge")))
        val html = render(open(home(items = listOf(item))).copy(screen = Screen.Items))
        assertEquals(2, Regex("""class="photo thumb"""").findAll(html).count())
        assertTrue("""alt="Front view"""" in html && """alt="Hinge"""" in html)
        assertTrue("""$ATTR_HASH="${photoOf(1).thumb}"""" in html && """$ATTR_HASH="${photoOf(1).photo}"""" in html)
        assertTrue("<summary>Larger photos</summary>" in html)
        assertFalse("src=" in html.substringAfter("class=\"photos\""), "the page script fills the picture in after fetching it")
        assertFalse("class=\"photos\"" in render(open(home(items = listOf(item.copy(photos = emptyList())))).copy(screen = Screen.Items)))
    }

    @Test fun theChangePageListsPhotosWithRemoveAndOffersTheAddFormOnlyWhileThereIsRoom() {
        fun page(count: Int) = render(
            open(home(items = listOf(ItemSummary(ladder.item, "Ladder", ItemClass.EVERYDAY, me, true, null, photos = List(count) { photoOf(it + 1) })))).copy(screen = Screen.EditItem, editing = ladder.item),
        )
        val none = page(0)
        assertTrue("No photos yet." in none && """id="$FORM_PHOTO_ADD"""" in none && "Description (optional): " in none)
        assertTrue("""type="file"""" in none && """accept="image/*"""" in none)
        assertTrue("Left blank, it is the item's name." in none)
        val two = page(2)
        assertEquals(2, Regex("""$ATTR_ACTION="$ACTION_REMOVE_PHOTO"""").findAll(two).count())
        assertTrue("""id="$FORM_PHOTO_ADD"""" in two)
        val full = page(3)
        assertFalse(FORM_PHOTO_ADD in full)
        assertTrue("An item can have 3 photos. Remove one to add another." in full)
        assertFalse(FORM_PHOTO_ADD in render(open(home()).copy(screen = Screen.AddItem)), "photos are added once the item exists")
    }

    // ---- approving other dates (ADR 0061) ----

    @Test fun theOwnerCanApproveOtherDatesAndTheBorrowerIsToldWhenTheyWereChanged() {
        val request = loan("a", LoanRole.OWNER, LoanStatus.REQUESTED)
        val html = render(open(home().copy(loans = listOf(request))).copy(screen = Screen.Borrow))
        assertTrue("Approve with different dates" in html && """id="$FORM_LOAN_CHANGE_PREFIX${request.loan.v}"""" in html)
        assertTrue("""value="${circles.app.CalendarDay.format(request.fromDay)}"""" in html && "Approve with these dates" in html)
        val changed = loan("b", LoanRole.BORROWER, LoanStatus.APPROVED).copy(datesChanged = true)
        assertTrue("The owner approved other dates than you asked for" in render(open(home().copy(loans = listOf(changed))).copy(screen = Screen.Borrow)))
        assertFalse("other dates than you asked" in render(open(home().copy(loans = listOf(changed.copy(datesChanged = false)))).copy(screen = Screen.Borrow)))
    }

    // ---- QR codes (ADR 0062) ----

    private val peopleForQr = listOf(
        MemberSummary(me, isMe = true, isSteward = true, devices = 1, fingerprint = "0123 4567 89ab cdef 0123"),
        MemberSummary(bea, isMe = false, isSteward = false, devices = 1, fingerprint = "aaaa bbbb cccc dddd eeee"),
    )
    private fun verifyHtml(link: circles.app.VerifyLink?, circleId: CircleId = circle) =
        render(open(home(people = peopleForQr).copy(circle = circleId)).copy(screen = Screen.Verify, verifying = link))
    private fun linkFor(member: circles.policy.MemberId, fingerprint: String, circleId: CircleId = circle) = circles.app.VerifyLink(circleId, member, fingerprint)

    @Test fun myMemberCardShowsAQrCodeOfMyVerifyLinkAndTheCharactersForWhenThereIsNoCamera() {
        val html = render(open(home(people = peopleForQr)).copy(screen = Screen.Members, origin = "https://circles.example"))
        assertTrue("<summary>Show my QR code</summary>" in html)
        assertEquals(1, Regex("""<svg class="qr"""").findAll(html).count(), "only mine")
        assertTrue("""role="img"""" in html && "QR code of the link that checks your identity" in html && """fill="#ffffff"""" in html && """fill="#000000"""" in html)
        val code = html.substringAfter("<summary>Show my QR code</summary>")
        assertTrue(code.indexOf("<svg") < code.indexOf("No camera? Read these characters out to each other."), "the characters come after the QR code")
        assertTrue("""<p class="fingerprint">0123 4567 89ab cdef 0123</p>""" in code)
    }

    @Test fun openingAMembersCodeSaysMatchesOrDoesNotMatchAfterWorkingOutTheFingerprintItself() {
        assertTrue("""Matches. This is <a href="#/members/person/${bea.v}" class="person-name">Bea</a>""" in verifyHtml(linkFor(bea, "aaaabbbbccccddddeeee")))
        val wrong = verifyHtml(linkFor(bea, "ffffffffffffffffffff"))
        assertTrue("Does not match" in wrong && """role="alert"""" in wrong && "aaaa bbbb cccc dddd eeee" in wrong && "ffff ffff ffff ffff ffff" in wrong)
        assertTrue("That is your own code." in verifyHtml(linkFor(me, "0123456789abcdef0123")))
        assertTrue("not in your circle" in verifyHtml(linkFor(circles.policy.MemberId("9".repeat(32)), "aaaabbbbccccddddeeee")))
        assertTrue("different circle" in verifyHtml(linkFor(bea, "aaaabbbbccccddddeeee", CircleId("8".repeat(32)))))
        assertTrue("Nothing to check." in verifyHtml(null))
    }

    @Test fun anItemTagHasTheNameAQrCodeAPrintButtonAndNoPrintedControls() {
        val item = ItemSummary(ladder.item, "Ladder", ItemClass.EVERYDAY, me, true, null)
        val html = render(open(home(items = listOf(item))).copy(screen = Screen.ItemTag, tagging = ladder.item, origin = "https://circles.example"))
        assertTrue("""<div class="tag">""" in html && "<h2>Ladder</h2>" in html && "QR code that opens Ladder in Lendger" in html)
        assertTrue("""$ATTR_ACTION="$ACTION_PRINT"""" in html && """class="no-print"""" in html)
        assertTrue("not on the list any more" in render(open(home()).copy(screen = Screen.ItemTag, tagging = ladder.item)))
        assertTrue("""$ATTR_ACTION="$ACTION_TAG_ITEM"""" in render(open(home(items = listOf(item))).copy(screen = Screen.Items)), "owners can print a tag")
    }

    @Test fun anItemOpenedFromATagShowsOnlyThatItemUntilAllAreAsked() {
        val one = ItemSummary(ladder.item, "Ladder", ItemClass.EVERYDAY, me, true, null)
        val other = ItemSummary(circles.policy.ItemId("7".repeat(32)), "Drill", ItemClass.VALUABLE, bea, false, null)
        val focused = render(open(home(items = listOf(one, other))).copy(screen = Screen.Items, focusItem = other.item))
        assertTrue("Showing the item from the tag." in focused && "Drill" in focused && "<h2>Ladder</h2>" !in focused && """$ATTR_ACTION="$ACTION_SHOW_ALL_ITEMS"""" in focused)
        assertTrue("<h2>Ladder</h2>" in render(open(home(items = listOf(one, other))).copy(screen = Screen.Items)))
        assertEquals(null, reduce(open().copy(screen = Screen.Items, focusItem = other.item), AppAction.ShowAllItems).focusItem)
    }

    @Test fun linksSurviveSigningInAndAreForgottenWhenYouLeaveTheirPage() {
        val link = linkFor(bea, "aaaabbbbccccddddeeee")
        val locked = AppState(screen = Screen.SignIn, model = AppModel.Locked(circle))
        val seen = reduce(locked, AppAction.VerifyLinkSeen(link))
        assertEquals(link, seen.verifying)
        assertEquals(link, reduce(seen, AppAction.Navigate(Screen.SignIn)).verifying, "signed out, a hash change does not forget it")
        val signedIn = reduce(seen, AppAction.ModelChanged(AppModel.Open(home())))
        assertEquals(Screen.Verify, signedIn.screen)
        assertEquals(null, reduce(signedIn, AppAction.Navigate(Screen.Members)).verifying)
        assertEquals(Screen.Members, reduce(open(), AppAction.Navigate(Screen.Verify)).screen, "no link, back to members")
        val item = reduce(locked, AppAction.ItemLinkSeen(ladder.item))
        assertEquals(Screen.Items, reduce(item, AppAction.ModelChanged(AppModel.Open(home()))).screen)
        assertEquals(Screen.Items, reduce(open(), AppAction.Navigate(Screen.ItemTag)).screen, "no item chosen, back to items")
        assertFalse(Screen.Verify in Screen.navigation || Screen.ItemTag in Screen.navigation)
    }
}

/** Display names (ADR 0068): asked for first, shown everywhere with whether they are confirmed, confirmed only after an in-person check. */
class NamesViewTest {
    private fun render(state: AppState): String = createHTML().div { shell(state) }

    private val alice = MemberSummary(me, isMe = true, isSteward = true, devices = 1, fingerprint = "ab12 cd34 ef56 7890 1234", displayName = "Alice", nameConfirmedBy = listOf(bea))
    private val beaToConfirm = MemberSummary(bea, isMe = false, isSteward = false, devices = 1, fingerprint = "1111 2222 3333 4444 5555", displayName = "Bea", canConfirmName = true)

    @Test fun aMemberWhoCanReadButHasNoNameIsAskedForItBeforeAnythingElse() {
        val unnamed = AppState(model = AppModel.Open(home(myName = null)))
        for (screen in listOf(Screen.Home, Screen.Items, Screen.Members, Screen.Settings, Screen.Verify)) {
            assertEquals(Screen.YourName, reduce(unnamed, AppAction.Navigate(screen)).screen, screen.name)
        }
        assertEquals(Screen.YourName, reduce(AppState(model = AppModel.Locked(circle)), AppAction.ModelChanged(AppModel.Open(home(myName = null)))).screen, "after unlocking")
        // Once the name is saved the page moves on, and the name page is not reachable any more.
        val named = reduce(unnamed.copy(screen = Screen.YourName), AppAction.ModelChanged(AppModel.Open(home())))
        assertEquals(Screen.Home, named.screen)
        assertEquals(Screen.Home, reduce(named, AppAction.Navigate(Screen.YourName)).screen)
        // Someone still waiting for approval cannot seal a name yet, so they are not asked.
        assertEquals(Screen.Home, reduce(AppState(model = AppModel.Open(home(canRead = false, myName = null))), AppAction.Navigate(Screen.Home)).screen)
        assertFalse(Screen.YourName in Screen.navigation)
    }

    @Test fun theNamePageAsksOneQuestionAndItsButtonWaitsForText() {
        val html = render(open(home(myName = null)).copy(screen = Screen.YourName))
        assertEquals(1, Regex("<form").findAll(html).count(), "one topic: one form")
        assertFalse("<nav" in html, "nothing else to wander off to")
        assertTrue(">Your name: </label>" in html)
        assertTrue("""id="$FORM_MY_NAME"""" in html && """name="$FIELD_DISPLAY_NAME"""" in html && """$ATTR_MIN="1"""" in html)
        val button = Regex("<button[^>]*id=\"$SUBMIT_ID\"[^>]*>").find(html)?.value.orEmpty()
        assertTrue("hidden" in button, button)
    }

    @Test fun aNameThatWasTakenIsExplainedOnTheNamePageAndInSettingsUntilAnotherWorks() {
        val message = nameTakenMessage("Bea")
        val unnamed = render(open(home(myName = null).copy(nameRefusal = message)).copy(screen = Screen.YourName))
        assertTrue("""id="$ID_NAME_REFUSAL"""" in unnamed && """role="alert"""" in unnamed && "Bea is already used in this circle. Choose another, for example add your surname or initial." in unnamed, unnamed)
        assertTrue("""id="$FORM_MY_NAME"""" in unnamed, "the form is still there to try again")
        val settings = render(open(home().copy(nameRefusal = message)).copy(screen = Screen.Settings))
        assertTrue("""id="$ID_NAME_REFUSAL"""" in settings)
        assertFalse(ID_NAME_REFUSAL in render(open(home(myName = null)).copy(screen = Screen.YourName)), "no message when nothing was refused")
        assertFalse(ID_NAME_REFUSAL in render(open(home()).copy(screen = Screen.Settings)))
    }

    @Test fun membersShowNamesWithTheirStateAndTheConfirmFormNeedsTheInPersonTick() {
        val html = render(open(home(people = listOf(alice, beaToConfirm))).copy(screen = Screen.Members))
        assertTrue("""<a href="#/members/person/${me.v}" class="person-name">Alice</a> <span class="name-status confirmed">$NAME_CONFIRMED</span> (you)""" in html)
        assertTrue("""<a href="#/members/person/${bea.v}" class="person-name">Bea</a> <span class="name-status unconfirmed">$NAME_NOT_CONFIRMED</span>""" in html)
        assertTrue("""Identity confirmed by <a href="#/members/person/${bea.v}" class="person-name">Bea</a>.""" in html)
        assertTrue("Nobody has confirmed this member's identity yet." in html)
        assertFalse(FORM_CONFIRM_NAME_PREFIX in html, "the form is on the check page, not on the card (ADR 0071)")
        val check = render(open(home(people = listOf(alice, beaToConfirm))).copy(screen = Screen.CheckIdentity, checking = PersonCheck(bea)))
        assertTrue("Checking: " in check && "No camera?" in check && """<p class="fingerprint">1111 2222 3333 4444 5555</p>""" in check)
        val form = check.substringAfter("""id="$FORM_CONFIRM_NAME_PREFIX${bea.v}"""").substringBefore("</form>")
        assertTrue("""type="checkbox"""" in form && """$ATTR_MUST_CHECK="true"""" in form && CHECKED_IN_PERSON in form)
        assertFalse("fingerprint" in form.lowercase(), "the form itself has no characters")
        assertTrue(Regex("<button[^>]*hidden[^>]*>Confirm identity<").containsMatchIn(form), "the button waits for the tick")
        val noRight = render(open(home(people = listOf(alice, beaToConfirm.copy(canConfirmName = false)))).copy(screen = Screen.CheckIdentity, checking = PersonCheck(bea)))
        assertFalse(FORM_CONFIRM_NAME_PREFIX in noRight)
        assertTrue("Only members at Level 2 or above, or a steward, can confirm someone's identity." in noRight)
    }

    @Test fun myOwnCardSaysPlainlyWhenNobodyHasConfirmedMyIdentity() {
        val html = render(open(home(people = listOf(alice.copy(nameConfirmedBy = emptyList()), beaToConfirm))).copy(screen = Screen.Members))
        assertTrue("Nobody has confirmed your identity yet. Meet a member in person and let them check your QR code." in html)
        assertFalse("your name yet" in html)
    }

    @Test fun anUnnamedMemberWaitingToBeLetInIsShownPlainlyAndLetInOnlyFromTheCheckAfterTheTick() {
        val stranger = MemberId("9".repeat(32))
        val device = DeviceId("8".repeat(32))
        val waiting = listOf(WaitingDevice(device, stranger, "7777 7777 7777 7777 7777"))
        val people = listOf(alice, MemberSummary(stranger, isMe = false, isSteward = false, devices = 2, fingerprint = "9999 8888 7777 6666 5555"))
        val html = render(open(home(waiting = waiting, people = people)).copy(screen = Screen.Members))
        assertTrue(html.substringAfter("Waiting for approval").let { "$UNNAMED_MEMBER</a> <button" in it && ">Check and approve access<" in it })
        assertFalse(html.substringAfter("Waiting for approval").contains(NAME_NOT_CONFIRMED), "no confirmation state without a name")
        val state = reduce(open(home(waiting = waiting, people = people)).copy(screen = Screen.Members), AppAction.CheckPerson(PersonCheck(stranger, device)))
        assertEquals(Screen.CheckIdentity, state.screen)
        val check = render(state)
        assertTrue("Approving access: " in check && "Circle home page shows a QR code" in check)
        assertTrue("""<p class="fingerprint">7777 7777 7777 7777 7777</p>""" in check, "the waiting device's own characters, not the member's first device")
        assertFalse("9999 8888" in check)
        val form = check.substringAfter("""id="$FORM_LET_IN_PREFIX${device.v}"""").substringBefore("</form>")
        assertTrue("""$ATTR_DEVICE="${device.v}"""" in check && """$ATTR_MUST_CHECK="true"""" in form && CHECKED_IN_PERSON in form)
        assertTrue(Regex("<button[^>]*hidden[^>]*>Approve access<").containsMatchIn(form), "the button waits for the tick")
        assertFalse(FORM_CONFIRM_NAME_PREFIX in check || ACTION_VOUCH in check, "letting in is the only thing on this page")
        // Already let in (or gone): the page says so and offers nothing.
        val stale = render(state.copy(model = AppModel.Open(home(people = people))))
        assertTrue("Nobody is waiting for approval with that device any more" in stale && FORM_LET_IN_PREFIX !in stale)
        // A non-steward never gets the let-in form.
        assertFalse(FORM_LET_IN_PREFIX in render(state.copy(model = AppModel.Open(home(steward = false, waiting = waiting, people = people)))))
        // Leaving the page forgets who was being checked; without a check the page falls back to Members.
        assertEquals(null, reduce(state, AppAction.Navigate(Screen.Members)).checking)
        assertEquals(Screen.Members, reduce(open(home()), AppAction.Navigate(Screen.CheckIdentity)).screen)
        assertEquals(null, reduce(state, AppAction.ModelChanged(AppModel.Locked(circle))).checking)
        assertFalse(Screen.CheckIdentity in Screen.navigation)
    }

    @Test fun someoneWaitingSeesTheirOwnQrCodeAndCharactersOnHome() {
        val waitingMe = MemberSummary(me, isMe = true, isSteward = false, devices = 1, fingerprint = "4444 3333 2222 1111 0000")
        val html = render(open(home(steward = false, canRead = false, people = listOf(waitingMe))).copy(origin = "https://circles.example"))
        assertTrue("Show a steward who you are" in html && """<svg class="qr"""" in html)
        assertTrue("/#/verify/${circle.v}/${me.v}/44443333222211110000" in html)
        assertTrue("""<p class="fingerprint">4444 3333 2222 1111 0000</p>""" in html)
        assertFalse("Show a steward who you are" in render(open(home(people = listOf(waitingMe)))), "not once let in")
    }

    @Test fun settingsShowsMyNameAndLetsMeChangeIt() {
        val html = render(open(home()).copy(screen = Screen.Settings))
        assertTrue("Others see you as: " in html && ">Alice<" in html)
        assertTrue("""id="$FORM_RENAME"""" in html && ">New name: </label>" in html)
        assertTrue("your identity needs confirming again" in html)
        assertFalse("fingerprint" in html)
    }

    @Test fun aVerifiedCodeOffersToConfirmTheIdentity() {
        val link = circles.app.VerifyLink(circle, bea, "11112222333344445555")
        val html = render(open(home(people = listOf(alice, beaToConfirm))).copy(screen = Screen.Verify, verifying = link))
        assertTrue("""Matches. This is <a href="#/members/person/${bea.v}" class="person-name">Bea</a>""" in html)
        assertTrue("""id="$FORM_CONFIRM_NAME_PREFIX${bea.v}"""" in html && "Confirm identity<" in html)
        assertFalse("1111 2222" in html, "after a match the characters are not needed")
    }

    @Test fun aVerifiedCodeOfSomeoneWaitingLetsAStewardLetInTheMatchingDeviceOnly() {
        val newcomer = MemberSummary(bea, isMe = false, isSteward = false, devices = 1, fingerprint = "1111 2222 3333 4444 5555")
        val matching = WaitingDevice(DeviceId("6".repeat(32)), bea, "1111 2222 3333 4444 5555")
        val other = WaitingDevice(DeviceId("7".repeat(32)), bea, "0000 0000 0000 0000 0000")
        val link = circles.app.VerifyLink(circle, bea, "11112222333344445555")
        val html = render(open(home(people = listOf(alice, newcomer), waiting = listOf(matching, other))).copy(screen = Screen.Verify, verifying = link))
        assertTrue("Matches." in html && """id="$FORM_LET_IN_PREFIX${matching.device.v}"""" in html)
        assertFalse(FORM_LET_IN_PREFIX + other.device.v in html, "a device whose characters do not match the code is not offered")
        assertFalse(FORM_LET_IN_PREFIX in render(open(home(steward = false, people = listOf(alice, newcomer), waiting = listOf(matching))).copy(screen = Screen.Verify, verifying = link)))
    }

    // ---- loan history (ADR 0072) ----

    private fun past(id: String, role: LoanRole, status: LoanStatus, from: Int, to: Int, outcome: ReturnOutcome? = null, returned: Int? = null, item: ItemSummary = ladder, dispute: DisputeSummary? = null) =
        LoanSummary(LoanId(id.repeat(32)), item.item, item.name, role, bea, from, to, status, true, true, outcome, "PRIVATE-DETAILS", returnedDay = returned, dispute = dispute)

    @Test fun anOwnerSeesEveryLoanOfTheirItemWithBorrowerDatesAndOutcome() {
        val loans = listOf(
            past("1", LoanRole.OWNER, LoanStatus.RETURNED, 20_700, 20_702, ReturnOutcome.OK, returned = 20_704),
            past("2", LoanRole.OWNER, LoanStatus.RETURNED, 20_710, 20_712, ReturnOutcome.DAMAGED_REPORTED, returned = 20_712,
                dispute = DisputeSummary(DisputeClaim.DAMAGE, null, 20_713, DisputeStatus.UPHELD)),
            past("3", LoanRole.OWNER, LoanStatus.DECLINED, 20_720, 20_721),
            past("4", LoanRole.BORROWER, LoanStatus.RETURNED, 20_730, 20_731, item = drill),
        )
        val html = render(open(home(items = listOf(ladder, drill), loans = loans)).copy(screen = Screen.ItemHistory, historyOf = ladder.item))
        assertTrue("Loan history" in html && "Ladder" in html && "Bea" in html)
        assertTrue("Returned late." in html && "Reported back: ${ld(20_704)}" in html)
        assertTrue("damage reported (no penalty for telling)" in html && "report of damage was upheld" in html)
        assertTrue("Declined." in html)
        assertEquals(3, Regex("Dates: ").findAll(html).count(), "only loans of this item that I own")
        assertFalse("PRIVATE-DETAILS" in html)
        assertTrue(html.indexOf(ld(20_720)) < html.indexOf(ld(20_700)), "newest first")
    }

    @Test fun anOwnerHistoryWithNoLoansSaysSo() {
        val html = render(open(home(items = listOf(ladder))).copy(screen = Screen.ItemHistory, historyOf = ladder.item))
        assertTrue("Nobody has asked to borrow this yet." in html)
    }

    @Test fun historyIsOnlyForOwnedItemsAndOnlyOffersAButtonOnThem() {
        val owned = render(open(home(items = listOf(ladder, drill))).copy(screen = Screen.Items))
        assertEquals(1, Regex(ACTION_ITEM_HISTORY).findAll(owned).count(), "one button, for the item I own")
        val notMine = render(open(home(items = listOf(drill))).copy(screen = Screen.ItemHistory, historyOf = drill.item))
        assertTrue("no longer on your list" in notMine)
        assertEquals(Screen.Items, reduce(open(), AppAction.Navigate(Screen.ItemHistory)).screen, "no item chosen, back to Items")
        val chosen = reduce(open(), AppAction.ShowItemHistory(ladder.item))
        assertEquals(Screen.ItemHistory, chosen.screen)
        assertEquals(ladder.item, chosen.historyOf)
    }

    @Test fun aBorrowerSeesPastItemsAndAskAgainOnlyWhereItCanWork() {
        val loans = listOf(
            past("1", LoanRole.BORROWER, LoanStatus.RETURNED, 20_700, 20_702, ReturnOutcome.OK, returned = 20_702, item = drill),
            past("2", LoanRole.BORROWER, LoanStatus.RETURNED, 20_710, 20_712, ReturnOutcome.OK, returned = 20_712, item = locked),
            past("3", LoanRole.BORROWER, LoanStatus.CANCELLED, 20_720, 20_721, item = ladder.copy(item = ItemId("9".repeat(32)), name = "Gone")),
        )
        val html = render(open(home(items = listOf(drill, locked), loans = loans)).copy(screen = Screen.BorrowHistory))
        assertTrue("Drill" in html && "Trailer" in html && "Gone" in html)
        assertEquals(1, Regex("Ask to borrow again").findAll(html).count(), "only the item whose rules allow it")
        assertTrue(ACTION_BORROW_ITEM in html && drill.item.v in html)
        assertTrue("Trailer needs Level 3" in html && "Why, and how to unlock" in html)
        assertTrue("No longer on the list." in html)
        assertFalse("PRIVATE-DETAILS" in html)
        assertTrue("Returned on time." in html && "Cancelled." in html)
    }

    @Test fun theBorrowPageLinksToBorrowingHistoryOnlyOnceThereIsSome() {
        val none = render(open(home(items = listOf(drill), loans = listOf(past("1", LoanRole.BORROWER, LoanStatus.ACTIVE, 1, 2, item = drill)))).copy(screen = Screen.Borrow))
        assertFalse(Screen.BorrowHistory.fragment in none)
        val some = render(open(home(items = listOf(drill), loans = listOf(past("1", LoanRole.BORROWER, LoanStatus.RETURNED, 1, 2, ReturnOutcome.OK, 2, drill)))).copy(screen = Screen.Borrow))
        assertTrue(Screen.BorrowHistory.fragment in some)
    }

    @Test fun anEmptyBorrowingHistorySaysSo() {
        assertTrue("You have not borrowed anything yet." in render(open().copy(screen = Screen.BorrowHistory)))
    }

    // ---- history search (ADR 0073) ----

    @Test fun theSearchTextHoldsItemPersonDatesAndOutcomeInLowerCase() {
        val entry = circles.app.LoanHistory.entryOf(past("1", LoanRole.OWNER, LoanStatus.RETURNED, 20_700, 20_702, ReturnOutcome.OK, returned = 20_704))
        val text = historySearchText(entry, "Long Ladder", "Bea")
        assertEquals(text.lowercase(), text)
        assertTrue("long ladder" in text && "bea" in text && "returned late" in text && ld(20_700).lowercase() in text)
        val disputed = circles.app.LoanHistory.entryOf(past("2", LoanRole.OWNER, LoanStatus.RETURNED, 1, 2, ReturnOutcome.DAMAGED_REPORTED, 2, dispute = DisputeSummary(DisputeClaim.DAMAGE, null, 3, DisputeStatus.UPHELD)))
        assertTrue("upheld" in historySearchText(disputed, "x", "y"))
    }

    @Test fun bothHistoryPagesOfferASearchOnlyWithMoreThanOneLoanAndTagEachLoan() {
        val two = listOf(
            past("1", LoanRole.OWNER, LoanStatus.RETURNED, 20_700, 20_702, ReturnOutcome.OK, returned = 20_702),
            past("2", LoanRole.OWNER, LoanStatus.DECLINED, 20_710, 20_711),
        )
        val owner = render(open(home(items = listOf(ladder), loans = two)).copy(screen = Screen.ItemHistory, historyOf = ladder.item))
        assertTrue("""id="$ID_HISTORY_SEARCH"""" in owner && "Search: " in owner)
        assertEquals(2, Regex(ATTR_HISTORY_TEXT).findAll(owner).count())
        val one = render(open(home(items = listOf(ladder), loans = two.take(1))).copy(screen = Screen.ItemHistory, historyOf = ladder.item))
        assertFalse(ID_HISTORY_SEARCH in one)
        val borrowed = listOf(
            past("3", LoanRole.BORROWER, LoanStatus.RETURNED, 20_700, 20_702, ReturnOutcome.OK, returned = 20_702, item = drill),
            past("4", LoanRole.BORROWER, LoanStatus.CANCELLED, 20_710, 20_711, item = drill),
        )
        val mine = render(open(home(items = listOf(drill), loans = borrowed)).copy(screen = Screen.BorrowHistory))
        assertTrue("""id="$ID_HISTORY_SEARCH"""" in mine && ID_HISTORY_COUNT in mine)
        assertEquals(2, Regex(ATTR_HISTORY_TEXT).findAll(mine).count())
        assertEquals(1, Regex(ATTR_HISTORY_GROUP).findAll(mine).count(), "one group per borrowed item")
        assertFalse(ID_HISTORY_SEARCH in render(open().copy(screen = Screen.BorrowHistory)))
    }

    // ---- archived items (ADR 0073, wording 0074, restore 0075) ----

    private val archivedLadder = listOf(ArchivedItem(ladder.item, "Ladder"))

    @Test fun anArchivedItemIsListedOnTheItemsPageWithRestoreAndHistoryEvenWhenNothingElseIsListed() {
        val loans = listOf(past("1", LoanRole.OWNER, LoanStatus.RETURNED, 20_700, 20_702, ReturnOutcome.OK, returned = 20_702))
        val html = render(open(home(items = emptyList(), loans = loans, archived = archivedLadder)).copy(screen = Screen.Items))
        assertTrue("Nothing has been shared yet." in html && "Archived items" in html && "Ladder" in html)
        assertTrue(ACTION_ITEM_HISTORY in html && ACTION_RESTORE_ITEM in html && ladder.item.v in html && "1 loan" in html && ">Restore<" in html)
        val withItems = render(open(home(items = listOf(drill), loans = loans, archived = archivedLadder)).copy(screen = Screen.Items))
        assertTrue("Archived items" in withItems)
        assertFalse("Archived items" in render(open(home(items = listOf(ladder), loans = loans)).copy(screen = Screen.Items)), "a listed item is not archived")
        assertFalse("Archived items" in render(open(home(items = emptyList())).copy(screen = Screen.Items)))
    }

    @Test fun anArchivedItemNobodyAskedForCanStillBeRestoredButHasNoHistoryButton() {
        val html = render(open(home(items = emptyList(), archived = archivedLadder)).copy(screen = Screen.Items))
        assertTrue(ACTION_RESTORE_ITEM in html && "0 loans" in html)
        assertFalse(ACTION_ITEM_HISTORY in html)
    }

    @Test fun theHistoryOfAnArchivedItemOffersRestoreAndSaysItCanBeRestoredWithoutPrivateDetails() {
        val loans = listOf(
            past("1", LoanRole.OWNER, LoanStatus.RETURNED, 20_700, 20_702, ReturnOutcome.OK, returned = 20_702),
            past("2", LoanRole.OWNER, LoanStatus.RETURNED, 20_710, 20_712, ReturnOutcome.LATE, returned = 20_713),
        )
        val html = render(open(home(items = emptyList(), loans = loans, archived = archivedLadder)).copy(screen = Screen.ItemHistory, historyOf = ladder.item))
        assertTrue("Ladder" in html && "You archived this item." in html && "You can restore it to put it back on the list." in html)
        assertFalse("cannot be restored" in html, "the old wording is gone")
        assertTrue(ACTION_RESTORE_ITEM in html)
        assertEquals(2, Regex("Dates: ").findAll(html).count())
        assertTrue("Returned late." in html)
        assertFalse("PRIVATE-DETAILS" in html)
        assertTrue("no longer on your list" in render(open(home(items = emptyList(), loans = loans, archived = archivedLadder)).copy(screen = Screen.ItemHistory, historyOf = drill.item)), "an item I never owned has no history")
        assertFalse(ACTION_RESTORE_ITEM in render(open(home(items = listOf(ladder), loans = loans)).copy(screen = Screen.ItemHistory, historyOf = ladder.item)), "a listed item has nothing to restore")
    }

    @Test fun anArchivedItemWithNoNameIsCalledWhatItIs() {
        val html = render(open(home(items = emptyList(), archived = listOf(ArchivedItem(ladder.item, "")))).copy(screen = Screen.ItemHistory, historyOf = ladder.item))
        assertEquals("An archived item", UNREADABLE_ITEM_NAME)
        assertTrue(UNREADABLE_ITEM_NAME in html)
        assertTrue(UNREADABLE_ITEM_NAME in render(open(home(items = emptyList(), archived = listOf(ArchivedItem(ladder.item, "")))).copy(screen = Screen.Items)))
    }

    @Test fun theArchiveDisclosureNoLongerSaysItCannotBePutBack() {
        val html = render(open(home(items = listOf(ladder))).copy(screen = Screen.Items))
        assertTrue("You can restore an archived item later." in html)
        assertFalse("cannot be restored" in html || "cannot be put back" in html)
    }

    // ---- person pages (ADR 0076) ----

    private val beaSummary = MemberSummary(bea, isMe = false, isSteward = true, devices = 1, fingerprint = "ab12 cd34", displayName = "Bea", nameConfirmedBy = listOf(me))
    private val meSummary = MemberSummary(me, isMe = true, isSteward = true, devices = 1, fingerprint = "ef56 7890", displayName = "Alice")
    private val beaSkill = circles.app.SkillSummary(circles.event.SkillId("5".repeat(32)), "Bike repair", "Punctures and brakes", setOf(circles.event.OpenTo.GIFT), bea, ownedByMe = false)

    private fun personState(member: MemberId, home: HomeData) = open(home).copy(screen = Screen.Person, viewing = member)

    @Test fun everyNameOnTheMembersPageIsALinkToThePersonPageAndNeverPlainText() {
        val html = render(open(home(people = listOf(meSummary, beaSummary))).copy(screen = Screen.Members))
        assertTrue("""<a href="#/members/person/${bea.v}" class="person-name">Bea</a>""" in html, html)
        assertTrue("""<a href="#/members/person/${me.v}" class="person-name">Alice</a>""" in html)
        assertFalse("""<span class="person-name">""" in html, "no plain-text name")
        assertTrue(NAME_CONFIRMED in html, "the identity words stay outside the link")
    }

    @Test fun namesInSentencesAndHistoriesAreLinksToo() {
        val loans = listOf(past("1", LoanRole.OWNER, LoanStatus.RETURNED, 20_700, 20_702, ReturnOutcome.OK, returned = 20_702))
        val history = render(open(home(items = listOf(ladder), loans = loans, archived = emptyList())).copy(screen = Screen.ItemHistory, historyOf = ladder.item))
        assertTrue("""href="#/members/person/${bea.v}"""" in history)
        val borrow = render(open(home(items = listOf(drill), loans = listOf(past("2", LoanRole.BORROWER, LoanStatus.ACTIVE, 20_759, 20_760, item = drill)))).copy(screen = Screen.Borrow))
        assertTrue("""href="#/members/person/${bea.v}"""" in borrow)
        val verifyLine = identityLineHtml()
        assertTrue("""href="#/members/person/${me.v}"""" in verifyLine, "who confirmed an identity is linked")
        assertTrue("Identity confirmed by" in verifyLine)
    }

    private fun identityLineHtml() = render(open(home(people = listOf(meSummary, beaSummary))).copy(screen = Screen.Members))

    @Test fun thePersonPageShowsTheMemberTheirItemsAndSkillsAndOnlyMyHistoryWithThem() {
        val loans = listOf(
            past("1", LoanRole.BORROWER, LoanStatus.RETURNED, 20_700, 20_702, ReturnOutcome.OK, returned = 20_702, item = drill),
            past("2", LoanRole.OWNER, LoanStatus.DECLINED, 20_710, 20_711),
            past("3", LoanRole.STEWARD, LoanStatus.RETURNED, 20_720, 20_721, ReturnOutcome.OK, returned = 20_721),
        )
        val html = render(personState(bea, home(items = listOf(ladder, drill), loans = loans, people = listOf(meSummary, beaSummary), names = mapOf(me to NameSummary("Alice", true), bea to NameSummary("Bea", true))).copy(skills = listOf(beaSkill))))
        assertTrue("Member" in html && "Bea" in html && NAME_CONFIRMED in html && "Identity confirmed by" in html)
        assertTrue("Steward of the circle." in html)
        assertTrue("Items on the list" in html && "Drill" in html && ACTION_BORROW_ITEM in html && "Ask to borrow" in html, "the existing ask-to-borrow logic")
        assertFalse("Ladder" in html.substringAfter("Items on the list").substringBefore("Skill offers"), "only Bea's items; the ladder is mine")
        assertTrue("Skill offers" in html && "Bike repair" in html && "Punctures and brakes" in html)
        assertTrue("Your history with this member" in html)
        assertEquals(2, Regex("Dates: ").findAll(html).count(), "the two loans between us; the steward-view loan is not shown")
        assertTrue("You lent it." in html && "You borrowed it." in html && "Declined." in html)
        assertFalse(ld(20_720) in html, "a loan I was not part of is not shown")
        assertFalse("PRIVATE-DETAILS" in html && "Garage at the back" in html, "no private description")
        assertTrue(ACTION_CHECK_PERSON in html && "Check identity in person" in html && """$ATTR_MEMBER="${bea.v}"""" in html)
        assertFalse("Your level" in html || "level:" in html.lowercase() || "points" in html.lowercase() || "pause" in html.lowercase(), "no level, points or pauses of the member")
    }

    @Test fun myOwnPersonPageLinksToSettingsInsteadOfCheckingMe() {
        val html = render(personState(me, home(items = listOf(ladder), people = listOf(meSummary, beaSummary))))
        assertTrue("(you)" in html && """href="#/settings"""" in html && "Change your name or your devices in Settings" in html)
        assertFalse(ACTION_CHECK_PERSON in html)
        assertFalse("Your history with this member" in html)
        assertTrue("Ladder" in html, "my own items on the list")
    }

    @Test fun aMemberWhoIsGoneFallsBackToAMessageAndTheMembersLink() {
        val html = render(personState(bea, home(people = listOf(meSummary))))
        assertTrue("This member is not in the circle any more." in html && """href="#/members"""" in html)
    }

    @Test fun thePersonAddressIsParsedStrictlyAndTheStateIsForgottenOnLeavingOrLocking() {
        assertEquals(bea, PersonLink.parse("#/members/person/${bea.v}"))
        assertEquals(null, PersonLink.parse("#/members/person/BEA"))
        assertEquals(null, PersonLink.parse("#/members/person/${bea.v}x"))
        assertEquals(null, PersonLink.parse("#/members"))
        assertEquals(Screen.Person, Screen.fromFragment("#/members/person/${bea.v}"))
        val shown = reduce(open(), AppAction.ShowPerson(bea))
        assertEquals(Screen.Person, shown.screen)
        assertEquals(bea, shown.viewing)
        assertEquals(null, reduce(shown, AppAction.Navigate(Screen.Members)).viewing, "forgotten on leaving")
        assertEquals(Screen.Members, reduce(open(), AppAction.Navigate(Screen.Person)).screen, "no member chosen: back to Members")
        assertEquals(null, reduce(shown, AppAction.ModelChanged(AppModel.NoIdentity)).viewing, "forgotten on lock or sign-out")
        assertEquals(shown.viewing, reduce(shown, AppAction.ModelChanged(AppModel.Open(home()))).viewing, "kept while the circle stays open")
        assertEquals(AppState().screen, reduce(AppState(), AppAction.ShowPerson(bea)).screen, "not when signed out")
        assertTrue(Screen.Person in Screen.subPages && Screen.Person !in Screen.navigation)
    }

    // ---- person page actions and member menus (ADR 0078) ----

    private fun personActions(person: MemberSummary, canVouch: Boolean = false) =
        render(personState(bea, home(people = listOf(meSummary, person), names = mapOf(me to NameSummary("Alice", true), bea to NameSummary("Bea", person.nameConfirmed))).copy(canVouch = canVouch)))

    private fun launchButtons(html: String) = Regex("""<button[^>]*$ATTR_ACTION="$ACTION_CHECK_PERSON"[^>]*>([^<]*)<""").findAll(html).map { it.groupValues[1] }.toList()

    @Test fun theConfirmAndVouchButtonsAppearOnlyWhenUsableAndOnlyOpenTheCheckPage() {
        val usable = personActions(beaSummary.copy(isSteward = false, nameConfirmedBy = emptyList(), canConfirmName = true), canVouch = true)
        assertEquals(listOf("Check identity in person", "Confirm identity", "Vouch"), launchButtons(usable))
        assertFalse(FORM_CONFIRM_NAME_PREFIX in usable || """$ATTR_ACTION="$ACTION_VOUCH"""" in usable, "the person page confirms and vouches nothing by itself (ADR 0071)")
        assertFalse(CHECKED_IN_PERSON in usable)
        assertTrue("where you tick that you checked in person" in usable)
    }

    @Test fun anUnusableButtonIsReplacedByOnePlainSentenceWhy() {
        val confirmed = personActions(beaSummary.copy(canConfirmName = false))
        assertEquals(listOf("Check identity in person"), launchButtons(confirmed))
        assertTrue("This member&#39;s identity is already confirmed." in confirmed || "This member's identity is already confirmed." in confirmed)
        assertTrue("can vouch for another member" in confirmed)
        val noName = personActions(beaSummary.copy(displayName = null, nameConfirmedBy = emptyList(), canConfirmName = false))
        assertTrue("has not chosen a name yet, so there is nothing to confirm" in noName)
        val noRight = personActions(beaSummary.copy(nameConfirmedBy = emptyList(), canConfirmName = false))
        assertTrue("can confirm a member" in noRight && "Confirm identity" !in launchButtons(noRight))
        val vouched = personActions(beaSummary.copy(vouchedByMeUntil = 20_800), canVouch = true)
        assertTrue("You vouch for this member until" in vouched)
        assertFalse("Vouch" in launchButtons(vouched))
    }

    @Test fun myOwnPageHasNoConfirmOrVouchButtons() {
        val html = render(personState(me, home(people = listOf(meSummary, beaSummary)).copy(canVouch = true)))
        assertFalse(ACTION_CHECK_PERSON in html)
    }

    @Test fun aMenuOfMembersHasALinkBesideItForTheChosenMemberOnly() {
        val html = render(open(home().copy(canProposePause = true, pauseCandidates = listOf(bea, MemberId("c".repeat(32))))).copy(screen = Screen.Pauses))
        assertTrue("""$ATTR_MEMBER_MENU="true"""" in html && """id="$FIELD_PAUSE_SUBJECT"""" in html)
        assertTrue("""<option value="${bea.v}">Bea</option>""" in html, "the option is plain text; a menu cannot hold a link")
        val views = Regex("""<span[^>]*$ATTR_VIEW_OF="$FIELD_PAUSE_SUBJECT"[^>]*>[\s\S]*?</span>""").findAll(html).map { it.value }.toList()
        assertEquals(2, views.size)
        assertTrue("hidden" !in views[0].substringBefore(">") && """$ATTR_VIEW_MEMBER="${bea.v}"""" in views[0], "the first (chosen) member's link shows")
        assertTrue("hidden" in views[1].substringBefore(">"), "the others are hidden until chosen")
        assertTrue("""View <a href="#/members/person/${bea.v}" class="person-name">Bea</a>""" in views[0])
        val note = render(open(home().copy(standing = StandingSummary(circles.policy.Tier.TRUSTED, "3.0", emptyList(), emptyList()), contributions = circles.app.ContributionsSummary(candidates = listOf(bea), notesLeft = 3))).copy(screen = Screen.Standing))
        assertTrue("""id="$FIELD_NOTE_WITH"""" in note && """$ATTR_VIEW_OF="$FIELD_NOTE_WITH"""" in note)
    }

    // ---- stewards need a confirmed identity (ADR 0079) ----

    @Test fun onlyMembersWithAConfirmedIdentityAreOfferedAsStewardsAndOneSentenceSaysWhy() {
        val carol = MemberId("c".repeat(32))
        val confirmed = MemberSummary(bea, isMe = false, isSteward = false, devices = 1, fingerprint = "a", displayName = "Bea", nameConfirmedBy = listOf(me))
        val unconfirmed = MemberSummary(carol, isMe = false, isSteward = false, devices = 1, fingerprint = "b", displayName = "Carol")
        val html = render(open(home(people = listOf(meSummary, confirmed, unconfirmed)).copy(stewardList = listOf(circles.app.StewardInfo(me, isMe = true, sinceDay = null, votesFromDay = null)))).copy(screen = Screen.Stewards))
        assertEquals(1, Regex("""$ATTR_ACTION="$ACTION_STEWARD_ADD"""").findAll(html).count(), "only the confirmed member")
        assertTrue("""$ATTR_SUBJECT="${bea.v}"""" in html && """$ATTR_SUBJECT="${carol.v}"""" !in html)
        assertTrue("Only members whose identity has been confirmed can become stewards" in html)
        val none = render(open(home(people = listOf(meSummary, unconfirmed)).copy(stewardList = listOf(circles.app.StewardInfo(me, isMe = true, sinceDay = null, votesFromDay = null)))).copy(screen = Screen.Stewards))
        assertFalse(ACTION_STEWARD_ADD in none)
        assertTrue("No member can be proposed yet." in none)
        val all = render(open(home(people = listOf(meSummary, confirmed)).copy(stewardList = listOf(circles.app.StewardInfo(me, isMe = true, sinceDay = null, votesFromDay = null)))).copy(screen = Screen.Stewards))
        assertFalse("Only members whose identity has been confirmed" in all, "no sentence when everyone is listed")
    }

    @Test fun theirPersonPageSaysAnUnconfirmedMemberCannotBecomeASteward() {
        val unconfirmed = MemberSummary(bea, isMe = false, isSteward = false, devices = 1, fingerprint = "a", displayName = "Bea")
        val asSteward = render(personState(bea, home(people = listOf(meSummary, unconfirmed))))
        assertTrue("cannot become a steward until someone has confirmed their identity" in asSteward)
        val confirmed = unconfirmed.copy(nameConfirmedBy = listOf(me))
        assertFalse("cannot become a steward" in render(personState(bea, home(people = listOf(meSummary, confirmed)))))
        assertFalse("cannot become a steward" in render(personState(bea, home(steward = false, people = listOf(meSummary, unconfirmed)))), "only a steward needs the hint")
    }

    // ---- date format (ADR 0080) ----

    private fun loansPage(format: circles.app.DateFormat) = render(
        open(home(items = listOf(ladder), loans = listOf(past("1", LoanRole.OWNER, LoanStatus.RETURNED, 20_700, 20_702, ReturnOutcome.OK, returned = 20_704))))
            .copy(screen = Screen.ItemHistory, historyOf = ladder.item, appearance = Appearance(dateFormat = format)),
    )

    @Test fun everyDateOnAPageFollowsTheChosenFormat() {
        val from = circles.app.CalendarDay.format(20_700)
        assertEquals("2026-09-04", from)
        assertTrue("Dates: 4 Sep 2026 to 6 Sep 2026" in loansPage(circles.app.DateFormat.LONG) && "Reported back: 8 Sep 2026" in loansPage(circles.app.DateFormat.LONG))
        assertTrue("Dates: 04/09/2026 to 06/09/2026" in loansPage(circles.app.DateFormat.DAY_FIRST))
        assertTrue("Dates: 09/04/2026 to 09/06/2026" in loansPage(circles.app.DateFormat.MONTH_FIRST))
        assertTrue("Dates: 2026-09-04 to 2026-09-06" in loansPage(circles.app.DateFormat.ISO) && "Reported back: 2026-09-08" in loansPage(circles.app.DateFormat.ISO))
    }

    @Test fun theSearchTextAndDueLinesUseTheChosenFormatToo() {
        render(open().copy(appearance = Appearance(dateFormat = circles.app.DateFormat.DAY_FIRST)))
        assertEquals("Due back today (04/11/2026).", dueLine(20_761, 20_761))
        val html = loansPage(circles.app.DateFormat.MONTH_FIRST)
        assertTrue("09/04/2026" in html.substringAfter(ATTR_HISTORY_TEXT).substringBefore(">"), "a search by date finds the date as it is shown")
        render(open())
    }

    @Test fun settingsHasADateFormatChoiceWithALiveExampleAndSaysWhatTheDateBoxesDo() {
        val html = render(open(home()).copy(screen = Screen.Settings, appearance = Appearance(dateFormat = circles.app.DateFormat.MONTH_FIRST)))
        assertTrue("Date format: " in html && """id="$FIELD_DATE_FORMAT"""" in html)
        for (choice in circles.app.DateFormat.entries) assertTrue("""value="${choice.key}"""" in html && choice.label in html, choice.key)
        assertTrue(Regex("""<option[^>]*value="month-first"[^>]*selected""").containsMatchIn(html))
        assertTrue("""id="$ID_DATE_FORMAT_EXAMPLE"""" in html && "Example: 11/02/2026" in html)
        assertTrue("a page cannot change that" in html && "written out in words under the box" in html)
        val standard = render(open(home()).copy(screen = Screen.Settings))
        assertTrue("Example: 2 Nov 2026" in standard, "the default is the long format")
    }

    @Test fun dateBoxesCarryATextUnderThemInTheChosenFormatAndKeepIsoValues() {
        val request = LoanSummary(LoanId("1".repeat(32)), ladder.item, "Ladder", LoanRole.OWNER, bea, 20_763, 20_765, LoanStatus.REQUESTED, false, false, null, null)
        val change = render(open(home(items = listOf(ladder), loans = listOf(request)).copy(today = 20_759)).copy(screen = Screen.Borrow, appearance = Appearance(dateFormat = circles.app.DateFormat.DAY_FIRST)))
        assertTrue("""value="2026-11-06"""" in change, "a date box always holds YYYY-MM-DD")
        assertTrue(Regex("""$ATTR_DATE_ECHO="from-${request.loan.v}"[^>]*>06/11/2026<""").containsMatchIn(change), "the same day written out under the box")
        val ask = render(open(home(items = listOf(drill))).copy(screen = Screen.RequestLoan, borrowing = drill.item))
        assertTrue("""$ATTR_DATE_ECHO="$FIELD_FROM"""" in ask && """$ATTR_DATE_ECHO="$FIELD_TO"""" in ask, "empty until a date is chosen")
    }

    @Test fun theChoiceIsKeptInTheAppearanceAndAnUnknownKeyGivesTheDefault() {
        assertEquals(circles.app.DateFormat.ISO, Appearance.fromKeys(null, null, "iso").dateFormat)
        assertEquals(circles.app.DateFormat.DEFAULT, Appearance.fromKeys("large", "dark", "nonsense").dateFormat)
        assertEquals(circles.app.DateFormat.LONG, Appearance().dateFormat)
        assertEquals(TextSize.LARGE, Appearance.fromKeys("large", null, "iso").textSize)
    }

    // ---- account menu (ADR 0083) ----

    private fun headerOf(html: String) = html.substringAfter("<header").substringBefore("</header>")
    private fun navOf(html: String) = html.substringAfter("<nav").substringBefore("</nav>")

    @Test fun theNavigationBarKeepsSixPagesAndMyStandingAndSettingsMoveToTheAccountMenu() {
        val html = render(open(home()).copy(screen = Screen.Home))
        val nav = navOf(html)
        for (title in listOf("Circle home", "Items", "Skills", "Borrow", "Members", "Stewards")) assertTrue(">$title<" in nav, title)
        assertEquals(6, Regex("<li>").findAll(nav).count())
        assertFalse("My level" in nav || "Settings" in nav)
        assertEquals(listOf(Screen.Standing, Screen.Settings), Screen.accountMenu)
        assertTrue(Screen.Standing !in Screen.navigation && Screen.Settings !in Screen.navigation)
        assertEquals("standing", Screen.Standing.path, "routes are unchanged")
        assertEquals("settings", Screen.Settings.path)
    }

    @Test fun theAccountButtonShowsTheNameAndClosedMenuLinksAreHidden() {
        val header = headerOf(render(open(home(myName = "Alice Smith")).copy(screen = Screen.Home)))
        assertTrue(Regex("""<button[^>]*id="$ID_ACCOUNT_BUTTON"[^>]*aria-expanded="false"[^>]*aria-controls="$ID_ACCOUNT_MENU"[^>]*>Alice Smith</button>""").containsMatchIn(header) ||
            Regex("""<button[^>]*aria-expanded="false"[^>]*>Alice Smith</button>""").containsMatchIn(header), header)
        assertTrue("aria-controls=\"$ID_ACCOUNT_MENU\"" in header)
        val menu = header.substringAfter("""id="$ID_ACCOUNT_MENU"""").substringBefore("</ul>")
        assertTrue(Regex("""^[^>]*hidden""").containsMatchIn(menu), "a closed menu is hidden, so its links are not in the tab order")
        assertEquals(2, Regex("<a ").findAll(menu).count(), "exactly two links")
        assertTrue("""href="#/standing"""" in menu && ">My level<" in menu && """href="#/settings"""" in menu && ">Settings<" in menu)
        assertFalse(Regex("""role="menu"""").containsMatchIn(header), "a plain disclosure, not an ARIA menu")
        assertTrue(ACTION_SYNC in header && ACTION_LOCK in header, "Sync now and Lock now stay in the header")
    }

    @Test fun anOpenMenuSaysSoAndShowsItsLinks() {
        val header = headerOf(render(open(home()).copy(screen = Screen.Home, accountMenuOpen = true)))
        assertTrue("""aria-expanded="true"""" in header)
        val menu = header.substringAfter("""id="$ID_ACCOUNT_MENU"""").substringBefore("</ul>")
        assertFalse(Regex("""^[^>]*hidden""").containsMatchIn(menu))
    }

    @Test fun theAccountButtonAndItsLinkAreMarkedCurrentOnMyStandingAndSettings() {
        for ((screen, title) in listOf(Screen.Standing to "My level", Screen.Settings to "Settings")) {
            val header = headerOf(render(open(home()).copy(screen = screen)))
            assertTrue(Regex("""<button[^>]*aria-current="page"""").containsMatchIn(header), "the button is current on $title")
            assertTrue(Regex("""<a[^>]*aria-current="page"[^>]*>$title</a>""").containsMatchIn(header), title)
            assertFalse("aria-current" in navOf(render(open(home()).copy(screen = screen))), "nothing in the nav bar is current")
        }
        assertFalse("aria-current" in headerOf(render(open(home()).copy(screen = Screen.Items))))
    }

    @Test fun withoutADisplayNameTheButtonSaysYourAccountAndTheFirstNamePageHasNoMenu() {
        assertEquals("Your account", ACCOUNT_FALLBACK)
        val noName = render(open(home(myName = null)).copy(screen = Screen.Home))
        assertTrue(">Your account</button>" in headerOf(noName) || ">Your account<" in headerOf(noName))
        val yourName = render(open(home(myName = null)).copy(screen = Screen.YourName))
        assertFalse(ID_ACCOUNT_BUTTON in yourName, "the name page has no navigation and no menu to wander off with")
        assertTrue("""aria-label="Alice Smith, $ACCOUNT_MENU_LABEL"""" in headerOf(render(open(home()))), "the name says what the button is")
        assertFalse("<nav" in yourName)
        assertFalse(ID_ACCOUNT_BUTTON in render(AppState(screen = Screen.Welcome, model = AppModel.NoIdentity)), "no menu before sign-in")
        assertFalse("aria-label=\"Your account" in headerOf(noName), "the fallback already says what it is")
    }

    @Test fun theMenuOpensAndClosesAndClosesOnNavigatingOrWhenTheAppLocks() {
        val opened = reduce(open(), AppAction.ToggleAccountMenu)
        assertTrue(opened.accountMenuOpen)
        assertFalse(reduce(opened, AppAction.ToggleAccountMenu).accountMenuOpen)
        assertFalse(reduce(opened, AppAction.CloseAccountMenu).accountMenuOpen)
        assertFalse(reduce(opened, AppAction.Navigate(Screen.Standing)).accountMenuOpen, "choosing a page closes it")
        assertFalse(reduce(opened, AppAction.ModelChanged(AppModel.NoIdentity)).accountMenuOpen, "locking closes it")
        assertEquals(AppState().accountMenuOpen, reduce(AppState(), AppAction.ToggleAccountMenu).accountMenuOpen, "not before sign-in")
        assertTrue(reduce(opened, AppAction.ModelChanged(AppModel.Open(home()))).accountMenuOpen, "a sync redraw keeps it open")
    }
}
