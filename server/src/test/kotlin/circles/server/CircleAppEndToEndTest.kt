package circles.server

import circles.app.AppModel
import circles.app.CircleApp
import circles.app.HomeData
import circles.app.InviteLink
import circles.app.ItemResult
import circles.app.LoanResult
import circles.app.AddDeviceResult
import circles.app.DeviceLink
import circles.app.DeviceRemoval
import circles.app.Fingerprint
import circles.app.LeaveResult
import circles.app.RemoveResult
import circles.app.StewardResult
import circles.event.DeviceIdentity
import circles.keys.SeedWrapping
import circles.keys.UnwrapResult
import circles.sync.fold
import circles.app.VouchResult
import circles.event.StewardChange
import circles.policy.MemberId
import circles.app.DisputeStatus
import circles.event.DisputeClaim
import circles.event.DisputeOutcome
import circles.policy.Tier
import circles.event.CircleFold
import circles.app.LoanRole
import circles.event.LoanStatus
import circles.event.MAX_LOAN_DAYS
import circles.event.ReturnOutcome
import circles.app.MAX_ITEM_DESCRIPTION_CHARS
import circles.app.StartResult
import circles.crypto.JvmCrypto
import circles.keys.Argon2Params
import circles.keys.AutoLock
import circles.keys.InMemoryKeyStore
import circles.keys.JvmPasswordHasher
import circles.keys.KeyStore
import circles.keys.KeyVault
import circles.keys.UnlockResult
import circles.server.support.TODAY
import circles.server.support.apiTest
import circles.server.support.testCipher
import circles.sync.HttpReply
import circles.sync.HttpSyncApi
import circles.sync.HttpTransport
import circles.sync.InMemoryReplica
import circles.sync.SyncState
import circles.policy.ItemClass
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PASSPHRASE = "correct horse battery staple"
private val FAST = Argon2Params(1_024, 1, 1)

/**
 * Two browsers' worth of `CircleApp` against the real server rules: start a circle, make an invite, join with the link,
 * be admitted by the steward, lock and unlock, and watch the home data follow. The scheduler is not run (the scope is
 * dead); the tests drive sync with `syncOnce()` so the steps are deterministic.
 */
class CircleAppEndToEndTest {
    private val deadScope = CoroutineScope(Job().also { it.cancel() })

    private class Browser(val app: CircleApp, val store: KeyStore, val replica: InMemoryReplica)

    private fun browser(transport: HttpTransport, crypto: JvmCrypto = JvmCrypto(), store: KeyStore = InMemoryKeyStore(), replica: InMemoryReplica? = null, today: () -> Int = { TODAY }): Browser {
        val hasher = JvmPasswordHasher()
        val vault = KeyVault(deadScope, crypto, testCipher, hasher, store, AutoLock.UntilTabCloses, FAST)
        val local = replica ?: InMemoryReplica { it.eventId(crypto) }
        val app = CircleApp(deadScope, crypto, testCipher, vault, store, local, { identity -> HttpSyncApi(transport, crypto, identity) }, today)
        return Browser(app, store, local)
    }

    private fun home(app: CircleApp): HomeData = assertIs<AppModel.Open>(app.model.value).home

    @Test
    fun aCircleIsStartedJoinedAdmittedAndLocked() = apiTest {
        val transport = HttpTransport { method, path, body, token ->
            api.raw(method, path, body, token).let { HttpReply(it.status, it.body, it.retryAfter) }
        }
        val crypto = JvmCrypto()

        // 1. A fresh browser has nothing; a short passphrase is refused and creates nothing.
        val alice = browser(transport, crypto)
        alice.app.open()
        assertEquals(AppModel.NoIdentity, alice.app.model.value)
        assertEquals(StartResult.PassphraseTooShort(12), alice.app.startCircle("short"))
        assertNull(alice.store.load())

        // 2. Alice starts a circle; her first sync sends circle.create and the first key.
        val started = assertIs<StartResult.Started>(alice.app.startCircle(PASSPHRASE))
        assertEquals(2, home(alice.app).changesWaiting)
        val first = assertNotNull(alice.app.syncOnce())
        assertEquals(SyncState.UpToDate, first.state)
        val aliceHome = home(alice.app)
        assertTrue(aliceHome.isSteward)
        assertTrue(aliceHome.canRead)
        assertEquals(1, aliceHome.members)
        assertEquals(0, aliceHome.changesWaiting)
        assertEquals(started.circle, aliceHome.circle)

        // 3. Alice makes an invite and syncs; the link round-trips as text.
        val link = assertNotNull(alice.app.makeInvite())
        alice.app.syncOnce()
        val text = link.format("https://circles.example")
        val parsed = assertNotNull(InviteLink.parse(text))

        // 4. Bea joins from the link in another browser; she is in, but cannot read yet.
        val bea = browser(transport, crypto)
        bea.app.open()
        assertIs<StartResult.Started>(bea.app.join(parsed, PASSPHRASE))
        bea.app.syncOnce()
        val beaHome = home(bea.app)
        assertFalse(beaHome.isSteward)
        assertFalse(beaHome.canRead)
        assertEquals(2, beaHome.members)

        // 5. Alice sees Bea waiting and admits her; Bea then reads.
        alice.app.syncOnce()
        val waiting = home(alice.app).waiting.single()
        assertEquals(beaHome.me, waiting.member)
        assertTrue(alice.app.admit(waiting.device))
        alice.app.syncOnce()
        assertEquals(emptyList(), home(alice.app).waiting)
        bea.app.syncOnce()
        assertTrue(home(bea.app).canRead)

        // Both see the same two people; the steward is marked; each person's fingerprint is the same on both screens.
        val seenByAlice = home(alice.app).people
        val seenByBea = home(bea.app).people
        assertEquals(2, seenByAlice.size)
        assertEquals(listOf(true, false), seenByAlice.map { it.isMe })
        assertEquals(listOf(false, true), seenByBea.map { it.isMe })
        assertEquals(listOf(true, false), seenByAlice.map { it.isSteward })
        assertEquals(seenByAlice.map { it.fingerprint }, seenByBea.map { it.fingerprint })
        assertTrue(seenByAlice.all { Regex("[0-9a-f]{4}( [0-9a-f]{4}){4}").matches(it.fingerprint) })
        assertEquals(2, seenByAlice.map { it.fingerprint }.toSet().size)

        // 6. An invite link works once: a second person using the same link is refused by the server and shows as a refused change.
        val carol = browser(transport, crypto)
        carol.app.open()
        carol.app.join(parsed, PASSPHRASE)
        carol.app.syncOnce()
        assertEquals(1, home(carol.app).refusedChanges)

        // 7. Locking drops the session; a wrong passphrase stays locked; the right one brings the same home back.
        bea.app.lock()
        // The watcher runs in the dead scope, so lock the model by reopening from the stored identity as a new session would.
        val reopened = browser(transport, crypto, bea.store, bea.replica)
        reopened.app.open()
        assertIs<AppModel.Locked>(reopened.app.model.value)
        assertEquals(UnlockResult.WrongPassphrase, reopened.app.unlock("not the passphrase"))
        assertIs<AppModel.Locked>(reopened.app.model.value)
        assertEquals(UnlockResult.Unlocked, reopened.app.unlock(PASSPHRASE))
        val back = home(reopened.app)
        assertEquals(beaHome.me, back.me)
        assertTrue(back.canRead)

        // 8. Erasing leaves nothing behind.
        reopened.app.erase()
        assertEquals(AppModel.NoIdentity, reopened.app.model.value)
        assertNull(reopened.store.load())
    }

    @Test
    fun theDeviceCounterContinuesAfterARestart() = apiTest {
        val transport = HttpTransport { method, path, body, token ->
            api.raw(method, path, body, token).let { HttpReply(it.status, it.body, it.retryAfter) }
        }
        val crypto = JvmCrypto()
        val first = browser(transport, crypto)
        first.app.open()
        first.app.startCircle(PASSPHRASE)
        first.app.syncOnce()
        first.app.makeInvite()
        first.app.syncOnce()
        val before = first.replica.entries((home(first.app)).circle).maxOf { it.envelope.ctr }
        val second = browser(transport, crypto, first.store, first.replica)
        second.app.open()
        second.app.unlock(PASSPHRASE)
        second.app.makeInvite()
        val circle = home(second.app).circle
        val queued = second.replica.outbox(circle).single()
        assertEquals(before + 1, queued.ctr)
        val report = assertNotNull(second.app.syncOnce())
        assertEquals(SyncState.UpToDate, report.state)
        assertEquals(0, report.refused)
    }

    @Test
    fun itemsAreAddedEditedKeptPrivateAndRetired() = apiTest {
        val transport = HttpTransport { method, path, body, token ->
            api.raw(method, path, body, token).let { HttpReply(it.status, it.body, it.retryAfter) }
        }
        val crypto = JvmCrypto()
        val alice = browser(transport, crypto)
        alice.app.open(); alice.app.startCircle(PASSPHRASE); alice.app.syncOnce()
        val link = assertNotNull(alice.app.makeInvite()); alice.app.syncOnce()
        val bea = browser(transport, crypto)
        bea.app.open(); bea.app.join(link, PASSPHRASE); bea.app.syncOnce()

        // Before a steward lets her in, Bea cannot seal anything.
        assertEquals(ItemResult.NotLetInYet, bea.app.addItem("Ladder", ItemClass.EVERYDAY, "x"))

        alice.app.syncOnce()
        assertTrue(alice.app.admit(home(alice.app).waiting.single().device))
        alice.app.syncOnce(); bea.app.syncOnce()

        // Bad input is refused before anything is queued.
        assertEquals(ItemResult.BadName, alice.app.addItem("   ", ItemClass.EVERYDAY, ""))
        assertEquals(ItemResult.BadName, alice.app.addItem("x".repeat(81), ItemClass.EVERYDAY, ""))
        assertEquals(ItemResult.DescriptionTooLong(MAX_ITEM_DESCRIPTION_CHARS), alice.app.addItem("Ladder", ItemClass.EVERYDAY, "d".repeat(MAX_ITEM_DESCRIPTION_CHARS + 1)))
        assertEquals(0, home(alice.app).changesWaiting)

        // Alice adds a ladder; she sees her own description.
        val saved = assertIs<ItemResult.Saved>(alice.app.addItem("  Ladder  ", ItemClass.EVERYDAY, "Garage at the back, ring first"))
        assertEquals(2, home(alice.app).changesWaiting)
        val first = assertNotNull(alice.app.syncOnce())
        assertEquals(SyncState.UpToDate, first.state)
        assertEquals(0, first.refused)
        val mine = home(alice.app).items.single()
        assertEquals("Ladder", mine.name)
        assertEquals("Garage at the back, ring first", mine.description)
        assertTrue(mine.ownedByMe)

        // Bea sees the item but not the private description, and cannot change or retire it.
        bea.app.syncOnce()
        val theirs = home(bea.app).items.single()
        assertEquals("Ladder", theirs.name)
        assertEquals(ItemClass.EVERYDAY, theirs.itemClass)
        assertNull(theirs.description)
        assertFalse(theirs.ownedByMe)
        assertEquals(ItemResult.NotAllowed, bea.app.editItem(saved.item, "Mine now", ItemClass.EVERYDAY, ""))
        assertEquals(ItemResult.NotAllowed, bea.app.retireItem(saved.item))

        // Alice edits it; Bea sees the new name and kind, still not the description.
        assertIs<ItemResult.Saved>(alice.app.editItem(saved.item, "Long ladder", ItemClass.VALUABLE, "Side gate code is private"))
        assertEquals(0, assertNotNull(alice.app.syncOnce()).refused)
        assertEquals("Side gate code is private", home(alice.app).items.single().description)
        bea.app.syncOnce()
        assertEquals("Long ladder", home(bea.app).items.single().name)
        assertEquals(ItemClass.VALUABLE, home(bea.app).items.single().itemClass)
        assertNull(home(bea.app).items.single().description)

        // The server never saw the words: nothing readable is in the database.
        val dump = server.rows("SELECT public, encrypted FROM events").joinToString { row -> row.joinToString { (it as? ByteArray)?.decodeToString().orEmpty() } }
        assertFalse("Long ladder" in dump || "Garage" in dump || "gate code" in dump)

        // Retiring takes it off both lists.
        assertIs<ItemResult.Saved>(alice.app.retireItem(saved.item))
        alice.app.syncOnce(); bea.app.syncOnce()
        assertEquals(emptyList(), home(alice.app).items)
        assertEquals(emptyList(), home(bea.app).items)
        assertEquals(ItemResult.NotAllowed, alice.app.editItem(saved.item, "Back again", ItemClass.EVERYDAY, ""))
    }

    @Test
    fun anItemIsBorrowedHandedOverAndReturned() = apiTest {
        val transport = HttpTransport { method, path, body, token ->
            api.raw(method, path, body, token).let { HttpReply(it.status, it.body, it.retryAfter) }
        }
        val crypto = JvmCrypto()
        val alice = browser(transport, crypto)
        alice.app.open(); alice.app.startCircle(PASSPHRASE); alice.app.syncOnce()
        val link = assertNotNull(alice.app.makeInvite()); alice.app.syncOnce()
        val bea = browser(transport, crypto)
        bea.app.open(); bea.app.join(link, PASSPHRASE); bea.app.syncOnce(); alice.app.syncOnce()
        alice.app.admit(home(alice.app).waiting.single().device); alice.app.syncOnce(); bea.app.syncOnce()
        val item = (alice.app.addItem("Ladder", ItemClass.EVERYDAY, "Garage at the back, ring first") as ItemResult.Saved).item
        alice.app.syncOnce(); bea.app.syncOnce()
        assertNull(home(bea.app).items.single().description)

        // Bad requests are refused before anything is queued.
        assertEquals(LoanResult.BadDates(MAX_LOAN_DAYS), bea.app.requestLoan(item, TODAY - 1, TODAY + 3, ""))
        assertEquals(LoanResult.BadDates(MAX_LOAN_DAYS), bea.app.requestLoan(item, TODAY + 5, TODAY + 3, ""))
        assertEquals(LoanResult.BadDates(MAX_LOAN_DAYS), bea.app.requestLoan(item, TODAY, TODAY + MAX_LOAN_DAYS + 1, ""))
        assertEquals(LoanResult.NoteTooLong(256), bea.app.requestLoan(item, TODAY, TODAY + 1, "n".repeat(257)))
        assertEquals(LoanResult.NotAllowed, alice.app.requestLoan(item, TODAY, TODAY + 1, ""), "not your own item")
        assertEquals(0, home(bea.app).changesWaiting)

        // Bea asks; Alice sees the request with the note, declines the first, and Bea asks again.
        val declined = (bea.app.requestLoan(item, TODAY + 1, TODAY + 3, "for the fence") as LoanResult.Done).loan
        assertEquals(0, assertNotNull(bea.app.syncOnce()).refused)
        alice.app.syncOnce()
        val request = home(alice.app).loans.single()
        assertEquals(LoanRole.OWNER, request.role)
        assertEquals(LoanStatus.REQUESTED, request.status)
        assertEquals("for the fence", request.note)
        assertEquals(LoanResult.NotAllowed, bea.app.respondToLoan(declined, approve = true, note = ""), "only the owner answers")
        assertIs<LoanResult.Done>(alice.app.respondToLoan(declined, approve = false, note = "away that week"))
        alice.app.syncOnce(); bea.app.syncOnce()
        assertEquals(LoanStatus.DECLINED, home(bea.app).loans.single().status)
        assertEquals(LoanResult.NotAllowed, alice.app.respondToLoan(declined, approve = true, note = ""), "already answered")
        assertNull(home(bea.app).items.single().description)

        val loan = (bea.app.requestLoan(item, TODAY + 1, TODAY + 3, "") as LoanResult.Done).loan
        bea.app.syncOnce(); alice.app.syncOnce()
        assertIs<LoanResult.Done>(alice.app.respondToLoan(loan, approve = true, note = ""))
        alice.app.syncOnce(); bea.app.syncOnce()

        // Approval is what gives Bea the private details.
        val approved = home(bea.app).loans.single { it.loan == loan }
        assertEquals(LoanStatus.APPROVED, approved.status)
        assertEquals("Garage at the back, ring first", approved.description)
        assertEquals("Garage at the back, ring first", home(bea.app).items.single().description)

        // Hand-over needs both people; one confirmation is not enough.
        assertEquals(LoanResult.NotAllowed, bea.app.returnLoan(loan, ReturnOutcome.OK), "not active yet")
        assertIs<LoanResult.Done>(bea.app.confirmHandover(loan))
        assertEquals(LoanResult.NotAllowed, bea.app.confirmHandover(loan), "only once")
        bea.app.syncOnce(); alice.app.syncOnce()
        assertEquals(LoanStatus.APPROVED, home(alice.app).loans.single { it.loan == loan }.status)
        assertTrue(home(alice.app).loans.single { it.loan == loan }.otherConfirmedHandover)
        assertIs<LoanResult.Done>(alice.app.confirmHandover(loan))
        alice.app.syncOnce(); bea.app.syncOnce()
        assertEquals(LoanStatus.ACTIVE, home(bea.app).loans.single { it.loan == loan }.status)

        // Only the borrower reports the return, and reporting damage is accepted as it is.
        assertEquals(LoanResult.NotAllowed, alice.app.returnLoan(loan, ReturnOutcome.OK))
        assertIs<LoanResult.Done>(bea.app.returnLoan(loan, ReturnOutcome.DAMAGED_REPORTED))
        assertEquals(0, assertNotNull(bea.app.syncOnce()).refused)
        alice.app.syncOnce()
        val returned = home(alice.app).loans.single { it.loan == loan }
        assertEquals(LoanStatus.RETURNED, returned.status)
        assertEquals(ReturnOutcome.DAMAGED_REPORTED, returned.outcome)
        assertEquals(0, home(alice.app).refusedChanges)
        assertEquals(0, home(bea.app).refusedChanges)

        // The server never saw the dates, notes or outcomes in the clear.
        val dump = server.rows("SELECT public, encrypted FROM events").joinToString { row -> row.joinToString { (it as? ByteArray)?.decodeToString().orEmpty() } }
        assertFalse("fence" in dump || "away that week" in dump || "DAMAGED" in dump)
    }

    @Test
    fun lendingEarnsAccessAndVouchingFollowsTheRules() = apiTest {
        val transport = HttpTransport { method, path, body, token ->
            api.raw(method, path, body, token).let { HttpReply(it.status, it.body, it.retryAfter) }
        }
        val crypto = JvmCrypto()
        var day = TODAY
        val alice = browser(transport, crypto, today = { day })
        alice.app.open(); alice.app.startCircle(PASSPHRASE); alice.app.syncOnce()
        val link = assertNotNull(alice.app.makeInvite()); alice.app.syncOnce()
        val bea = browser(transport, crypto, today = { day })
        bea.app.open(); bea.app.join(link, PASSPHRASE); bea.app.syncOnce(); alice.app.syncOnce()
        alice.app.admit(home(alice.app).waiting.single().device); alice.app.syncOnce(); bea.app.syncOnce()

        val drill = (alice.app.addItem("Drill", ItemClass.VALUABLE, "") as ItemResult.Saved).item
        val ladder = (bea.app.addItem("Ladder", ItemClass.EVERYDAY, "") as ItemResult.Saved).item
        alice.app.syncOnce(); bea.app.syncOnce(); alice.app.syncOnce()

        // The founder is a steward, so counts as trusted; Bea starts as a newcomer and is told why and how to change that.
        assertEquals(Tier.TRUSTED, home(alice.app).standing!!.tier)
        assertTrue(home(alice.app).canVouch)
        val before = home(bea.app)
        assertEquals(Tier.NEWCOMER, before.standing!!.tier)
        assertFalse(before.canVouch)
        val drillAccess = before.items.single { it.item == drill }.access!!
        assertFalse(drillAccess.canAsk)
        assertTrue(drillAccess.reasons.any { it.contains("needs NEIGHBOUR") })
        assertTrue(drillAccess.howToUnlock.any { it.contains("vouch") })
        assertNull(before.items.single { it.item == ladder }.access, "no access line on your own item")
        assertTrue(before.standing!!.howToUnlock.isNotEmpty())
        val refused = assertIs<LoanResult.NotYet>(bea.app.requestLoan(drill, day + 1, day + 2, ""))
        assertTrue(refused.reasons.any { it.contains("needs NEIGHBOUR") })
        assertEquals(0, home(bea.app).changesWaiting, "a request that would be ignored is not even queued")
        assertEquals(VouchResult.NotAllowed, bea.app.vouchFor(home(bea.app).people.single { !it.isMe }.member), "a newcomer cannot vouch")
        assertEquals(VouchResult.NotAllowed, alice.app.vouchFor(home(alice.app).me), "nobody vouches for themselves")

        // Alice borrows Bea's ladder three times and returns it each time; Bea, as the lender, earns 1.0 per good return.
        repeat(3) {
            val loan = (alice.app.requestLoan(ladder, day, day + 2, "") as LoanResult.Done).loan
            alice.app.syncOnce(); bea.app.syncOnce()
            assertIs<LoanResult.Done>(bea.app.respondToLoan(loan, approve = true, note = ""))
            bea.app.syncOnce(); alice.app.syncOnce()
            assertIs<LoanResult.Done>(bea.app.confirmHandover(loan)); assertIs<LoanResult.Done>(alice.app.confirmHandover(loan))
            bea.app.syncOnce(); alice.app.syncOnce()
            assertIs<LoanResult.Done>(alice.app.returnLoan(loan, ReturnOutcome.OK))
            alice.app.syncOnce(); bea.app.syncOnce()
        }
        assertEquals(0, home(alice.app).refusedChanges)
        assertEquals(Tier.NEWCOMER, home(bea.app).standing!!.tier, "returns count only after the grace period")

        day += 7
        server.clock.advanceSeconds(7L * 24 * 60 * 60) // the server refuses events dated in its future
        bea.app.syncOnce(); alice.app.syncOnce()
        val after = home(bea.app)
        assertEquals(Tier.NEIGHBOUR, after.standing!!.tier)
        assertEquals("3.0", after.standing!!.points)
        assertTrue(after.canVouch)
        assertTrue(after.items.single { it.item == drill }.access!!.canAsk)
        assertIs<LoanResult.Done>(bea.app.requestLoan(drill, day + 1, day + 2, "at last"))
        assertEquals(0, assertNotNull(bea.app.syncOnce()).refused)

        // Vouching: Alice (steward) vouches for Bea; the Members page shows it until the expiry; Bea (now a neighbour) may too.
        val vouched = assertIs<VouchResult.Done>(alice.app.vouchFor(home(bea.app).me))
        assertEquals(day + 180, vouched.expiresDay)
        assertEquals(day + 180, home(alice.app).people.single { !it.isMe }.vouchedByMeUntil)
        assertNull(home(alice.app).people.single { it.isMe }.vouchedByMeUntil)
        assertIs<VouchResult.Done>(bea.app.vouchFor(home(alice.app).me))
        assertEquals(0, assertNotNull(alice.app.syncOnce()).refused)
        assertEquals(0, assertNotNull(bea.app.syncOnce()).refused)
    }

    @Test
    fun aReturnIsConfirmedAndAnotherDisputedAndTheStewardWhoIsNotAPartyDecides() = apiTest {
        val transport = HttpTransport { method, path, body, token ->
            api.raw(method, path, body, token).let { HttpReply(it.status, it.body, it.retryAfter) }
        }
        val crypto = JvmCrypto()
        val alice = browser(transport, crypto)
        alice.app.open(); alice.app.startCircle(PASSPHRASE); alice.app.syncOnce()
        suspend fun join(): Browser {
            val link = assertNotNull(alice.app.makeInvite()); alice.app.syncOnce()
            val member = browser(transport, crypto)
            member.app.open(); member.app.join(link, PASSPHRASE); member.app.syncOnce(); alice.app.syncOnce()
            alice.app.admit(home(alice.app).waiting.single().device); alice.app.syncOnce(); member.app.syncOnce()
            return member
        }
        val bea = join()
        val carol = join()
        val item = (bea.app.addItem("Wheelbarrow", ItemClass.EVERYDAY, "") as ItemResult.Saved).item
        listOf(bea, carol, alice).forEach { it.app.syncOnce() }

        // Two returns of the same item: the owner confirms the first and disputes the second.
        suspend fun lendAndReturn(): circles.event.LoanId {
            val loan = (carol.app.requestLoan(item, TODAY, TODAY + 2, "") as LoanResult.Done).loan
            carol.app.syncOnce(); bea.app.syncOnce()
            assertIs<LoanResult.Done>(bea.app.respondToLoan(loan, approve = true, note = "")); bea.app.syncOnce(); carol.app.syncOnce()
            assertIs<LoanResult.Done>(bea.app.confirmHandover(loan)); assertIs<LoanResult.Done>(carol.app.confirmHandover(loan))
            bea.app.syncOnce(); carol.app.syncOnce()
            assertIs<LoanResult.Done>(carol.app.returnLoan(loan, ReturnOutcome.OK))
            carol.app.syncOnce(); bea.app.syncOnce(); alice.app.syncOnce()
            return loan
        }
        val first = lendAndReturn()
        assertEquals(LoanResult.NotAllowed, carol.app.confirmReturn(first), "the borrower cannot confirm")
        assertTrue(home(bea.app).loans.single { it.loan == first }.canConfirm)
        assertIs<LoanResult.Done>(bea.app.confirmReturn(first))
        assertEquals(LoanResult.NotAllowed, bea.app.confirmReturn(first), "only once")
        bea.app.syncOnce(); carol.app.syncOnce(); alice.app.syncOnce()
        assertEquals("1.3", home(bea.app).standing!!.points, "1.0 for lending plus 0.25 for confirming promptly, counted at once")
        assertEquals("0.5", home(carol.app).standing!!.points)

        val second = lendAndReturn()
        val seenByBea = home(bea.app).loans.single { it.loan == second }
        assertTrue(seenByBea.canDispute)
        assertEquals(LoanResult.NoteTooLong(256), bea.app.disputeReturn(second, DisputeClaim.DAMAGE, "n".repeat(257)))
        assertEquals(LoanResult.NotAllowed, carol.app.disputeReturn(second, DisputeClaim.DAMAGE, ""), "only the owner disputes")
        assertIs<LoanResult.Done>(bea.app.disputeReturn(second, DisputeClaim.DAMAGE, "the handle is cracked"))
        assertEquals(DisputeStatus.PENDING, home(bea.app).loans.single { it.loan == second }.dispute!!.status)
        bea.app.syncOnce(); carol.app.syncOnce(); alice.app.syncOnce()

        // Alice (steward, not a party) can resolve; the parties cannot. Credit for the second loan is withheld meanwhile.
        val forAlice = home(alice.app).loans.single { it.loan == second }
        assertEquals(LoanRole.STEWARD, forAlice.role)
        assertTrue(forAlice.canResolve)
        assertEquals("the handle is cracked", forAlice.dispute!!.note)
        assertEquals(LoanResult.NotAllowed, bea.app.resolveDispute(second, DisputeOutcome.UPHELD))
        assertEquals(LoanResult.NotAllowed, carol.app.resolveDispute(second, DisputeOutcome.DISMISSED))
        assertEquals("1.3", home(bea.app).standing!!.points, "nothing more while pending")
        assertEquals(1, home(alice.app).stewardCount)

        assertIs<LoanResult.Done>(alice.app.resolveDispute(second, DisputeOutcome.UPHELD))
        assertEquals(LoanResult.NotAllowed, alice.app.resolveDispute(second, DisputeOutcome.DISMISSED), "only once")
        listOf(alice, bea, carol).forEach { assertEquals(0, assertNotNull(it.app.syncOnce()).refused) }
        assertEquals(DisputeStatus.UPHELD, home(carol.app).loans.single { it.loan == second }.dispute!!.status)
        val carolNow = home(carol.app).standing!!
        assertEquals("0.0", carolNow.points, "the incident outweighs the earlier good loan, and points never go below zero")
        assertTrue(carolNow.reasons.any { it.contains("unreported incident") && it.contains("on hold") })
        assertEquals("1.3", home(bea.app).standing!!.points, "an upheld dispute earns the owner nothing for that loan")
        assertEquals(0, home(alice.app).loans.count { it.canResolve })
    }

    @Test
    fun stewardsAreAddedByQuorumAfterTheCoolingOffAndTheRulesCanBeChanged() = apiTest {
        val transport = HttpTransport { method, path, body, token ->
            api.raw(method, path, body, token).let { HttpReply(it.status, it.body, it.retryAfter) }
        }
        val crypto = JvmCrypto()
        var day = TODAY
        val alice = browser(transport, crypto, today = { day })
        alice.app.open(); alice.app.startCircle(PASSPHRASE); alice.app.syncOnce()
        suspend fun join(): Browser {
            val link = assertNotNull(alice.app.makeInvite()); alice.app.syncOnce()
            val member = browser(transport, crypto, today = { day })
            member.app.open(); member.app.join(link, PASSPHRASE); member.app.syncOnce(); alice.app.syncOnce()
            alice.app.admit(home(alice.app).waiting.single().device); alice.app.syncOnce(); member.app.syncOnce()
            return member
        }
        val bea = join()
        val carol = join()
        fun id(b: Browser) = home(b.app).me
        // Everyone pushes, then everyone pulls, so a change made by one is seen by all.
        fun sync() = runBlocking { repeat(2) { listOf(alice, bea, carol).forEach { it.app.syncOnce() } } }

        // One steward and the default quorum of two: the only steward can add another at once.
        assertEquals(1, home(alice.app).stewardList.size)
        assertEquals(2, home(alice.app).stewardSettings.quorum)
        assertEquals(StewardResult.Sent(takesEffect = true), alice.app.proposeSteward(id(bea)))
        sync()
        val beaView = home(bea.app)
        assertTrue(beaView.isSteward)
        val beaInfo = beaView.stewardList.single { it.isMe }
        assertEquals(day + 7, beaInfo.votesFromDay, "a new steward cools off for 7 days")

        // A second addition now needs two stewards; Bea cannot vote yet, and says why when she tries.
        assertEquals(StewardResult.Sent(takesEffect = false), alice.app.proposeSteward(id(carol)))
        sync()
        val waiting = home(bea.app).proposals.single()
        assertEquals(StewardChange.ADD, waiting.change)
        assertEquals(id(carol), waiting.member)
        assertEquals(1, waiting.agreeing); assertEquals(2, waiting.needed)
        assertFalse(waiting.canAgree)
        assertTrue(assertIs<StewardResult.Refused>(bea.app.proposeSteward(id(carol))).reason.contains("cannot vote until day"))
        assertFalse(home(alice.app).proposals.single().canAgree, "Alice already agrees")

        day += 7; server.clock.advanceSeconds(7L * 24 * 60 * 60)
        sync()
        assertTrue(home(bea.app).proposals.single().canAgree)
        assertEquals(StewardResult.Sent(takesEffect = true), bea.app.proposeSteward(id(carol)))
        sync()
        assertEquals(3, home(alice.app).stewardList.size)
        assertEquals(emptyList(), home(alice.app).proposals)
        assertTrue(home(carol.app).isSteward)

        // The rules themselves are a proposal too: quorum 1 needs two of the three to agree.
        assertEquals(StewardResult.Sent(takesEffect = false), alice.app.proposeStewardSettings(1, 60, 14))
        sync()
        val rules = home(bea.app).proposals.single()
        assertEquals(StewardChange.POLICY, rules.change)
        assertEquals(1, rules.settings!!.quorum)
        assertTrue(assertIs<StewardResult.Refused>(alice.app.proposeStewardSettings(2, 60, 14)).reason.contains("already the circle's settings"))
        assertTrue(assertIs<StewardResult.Refused>(alice.app.proposeStewardSettings(0, 60, 14)).reason.contains("quorum"))
        assertEquals(StewardResult.Sent(takesEffect = true), bea.app.proposeStewardSettings(1, 60, 14))
        sync()
        assertEquals(1, home(carol.app).stewardSettings.quorum)

        // With quorum one a single steward can remove another; anyone may step down while another remains; the last stays.
        assertEquals(StewardResult.Sent(takesEffect = true), alice.app.removeSteward(id(carol)))
        sync()
        assertEquals(2, home(alice.app).stewardList.size)
        assertFalse(home(carol.app).isSteward)
        assertEquals(StewardResult.Sent(takesEffect = true), bea.app.removeSteward(id(bea)))
        sync()
        assertEquals(1, home(alice.app).stewardList.size)
        assertEquals("a circle keeps at least one steward", assertIs<StewardResult.Refused>(alice.app.removeSteward(id(alice))).reason)
        assertEquals("member is not a steward", assertIs<StewardResult.Refused>(alice.app.removeSteward(id(carol))).reason)
        assertTrue(assertIs<StewardResult.Refused>(alice.app.proposeSteward(MemberId("9".repeat(32)))).reason.contains("not in the circle"))
        listOf(alice, bea, carol).forEach { assertEquals(0, assertNotNull(it.app.syncOnce()).refused) }
    }

    @Test
    fun aRemovedMemberIsCutOffAndCannotReadAnythingAfterTheKeyRotation() = apiTest {
        val transport = HttpTransport { method, path, body, token ->
            api.raw(method, path, body, token).let { HttpReply(it.status, it.body, it.retryAfter) }
        }
        val crypto = JvmCrypto()
        val alice = browser(transport, crypto)
        alice.app.open(); alice.app.startCircle(PASSPHRASE); alice.app.syncOnce()
        suspend fun join(): Browser {
            val link = assertNotNull(alice.app.makeInvite()); alice.app.syncOnce()
            val member = browser(transport, crypto)
            member.app.open(); member.app.join(link, PASSPHRASE); member.app.syncOnce(); alice.app.syncOnce()
            alice.app.admit(home(alice.app).waiting.single().device); alice.app.syncOnce(); member.app.syncOnce()
            return member
        }
        val bea = join()
        val carol = join()
        fun sync() = runBlocking { repeat(2) { listOf(alice, bea, carol).filter { it.app.model.value is AppModel.Open }.forEach { it.app.syncOnce() } } }
        val carolsItem = (carol.app.addItem("Ladder", ItemClass.EVERYDAY, "") as ItemResult.Saved).item
        val beforeRemoval = (alice.app.addItem("Planter", ItemClass.EVERYDAY, "") as ItemResult.Saved).item
        sync()
        assertEquals(2, home(alice.app).items.size)
        val aliceId = home(alice.app).me; val beaId = home(bea.app).me; val carolId = home(carol.app).me

        // Who may remove whom.
        assertEquals(RemoveResult.NotAllowed, bea.app.removeMember(carolId), "only a steward")
        assertEquals(RemoveResult.NotAllowed, alice.app.removeMember(aliceId), "not yourself")
        assertEquals(RemoveResult.NotInCircle, alice.app.removeMember(MemberId("9".repeat(32))))
        assertTrue(home(alice.app).people.single { it.member == carolId }.canRemove)
        assertFalse(home(alice.app).people.single { it.isMe }.canRemove)
        assertFalse(home(bea.app).people.any { it.canRemove })

        // Carol's device, as the operator or a thief would hold it: the identity and the log she could read.
        val stored = assertNotNull(carol.store.load())
        val carolSeed = (SeedWrapping.unwrapWithPassphrase(testCipher, JvmPasswordHasher(), stored.wrapped, stored.deviceId, PASSPHRASE) as UnwrapResult.Opened).seed
        val carolIdentity = DeviceIdentity.fromSeed(crypto, testCipher, carolSeed, stored.memberId, stored.deviceId)

        assertEquals(RemoveResult.Removed, alice.app.removeMember(carolId))
        sync()
        assertEquals(setOf(aliceId, beaId), home(alice.app).people.map { it.member }.toSet())
        assertEquals(setOf(aliceId, beaId), home(bea.app).people.map { it.member }.toSet())
        assertFalse(home(alice.app).needsRotation, "the removal already rotated the key")
        assertEquals(emptyList(), home(alice.app).items.filter { it.item == carolsItem }, "a removed member's items are not listed")
        assertEquals(LoanResult.NotAllowed, bea.app.requestLoan(carolsItem, TODAY, TODAY + 1, ""), "and cannot be requested")

        // Carol's next sync is refused: she is signed out.
        assertEquals(SyncState.SignedOut, assertNotNull(carol.app.syncOnce()).state)

        // Something added after the rotation reaches Bea but is unreadable with Carol's keys; earlier things stay readable to her.
        val afterRemoval = (alice.app.addItem("Secret shed code", ItemClass.EVERYDAY, "behind the red door") as ItemResult.Saved).item
        sync()
        assertEquals("behind the red door", home(alice.app).items.single { it.item == afterRemoval }.description)
        assertTrue(home(bea.app).items.any { it.item == afterRemoval })
        val leakedLog = (alice.replica.entries(home(alice.app).circle))
        val asCarol = CircleFold(crypto, testCipher, carolIdentity).fold(home(alice.app).circle, leakedLog.map { it.envelope })
        assertTrue(beforeRemoval in asCarol.market.items.keys, "she keeps what she could already read")
        assertFalse(afterRemoval in asCarol.market.items.keys, "but nothing sealed after the rotation")
        assertEquals(2, asCarol.currentEpoch)
        assertEquals(null, asCarol.keys[2], "no key for the new epoch was ever wrapped to her device")

        // Leaving on one's own: Bea revokes her devices, forgets everything, and a steward is prompted to rotate.
        assertEquals(LeaveResult.StewardMustStepDown, alice.app.leaveCircle())
        assertEquals(LeaveResult.Left, bea.app.leaveCircle())
        assertEquals(AppModel.NoIdentity, bea.app.model.value)
        assertNull(bea.store.load())
        assertEquals(emptyList(), bea.replica.entries(home(alice.app).circle), "the local copy of the circle is gone")
        alice.app.syncOnce()
        assertTrue(home(alice.app).needsRotation)
        assertEquals(setOf(aliceId), home(alice.app).people.map { it.member }.toSet())
        assertEquals(RemoveResult.Removed, alice.app.rotateKeyNow())
        assertFalse(home(alice.app).needsRotation)
        assertEquals(RemoveResult.NotAllowed, alice.app.rotateKeyNow(), "nothing left to rotate for")
        assertEquals(0, assertNotNull(alice.app.syncOnce()).refused)
    }

    @Test
    fun aSecondDeviceIsPairedReadsEverythingAndIsRemovedWithTheKeyChanged() = apiTest {
        val transport = HttpTransport { method, path, body, token ->
            api.raw(method, path, body, token).let { HttpReply(it.status, it.body, it.retryAfter) }
        }
        val crypto = JvmCrypto()
        val alice = browser(transport, crypto)
        alice.app.open(); alice.app.startCircle(PASSPHRASE); alice.app.syncOnce()
        suspend fun join(): Browser {
            val link = assertNotNull(alice.app.makeInvite()); alice.app.syncOnce()
            val member = browser(transport, crypto)
            member.app.open(); member.app.join(link, PASSPHRASE); member.app.syncOnce(); alice.app.syncOnce()
            alice.app.admit(home(alice.app).waiting.single().device); alice.app.syncOnce(); member.app.syncOnce()
            return member
        }
        val bea = join()
        val ladder = (alice.app.addItem("Ladder", ItemClass.EVERYDAY, "Garage at the back") as ItemResult.Saved).item
        alice.app.syncOnce(); bea.app.syncOnce(); alice.app.syncOnce()
        val aliceId = home(alice.app).me

        // 1. The existing device shows a link of public ids; the new browser opens it and shows its code and fingerprint.
        val link = assertNotNull(DeviceLink.parse(assertNotNull(alice.app.deviceLink("https://circles.example"))))
        assertEquals(aliceId, link.member)
        val phone = browser(transport, crypto)
        phone.app.open()
        assertIs<StartResult.Started>(phone.app.startOnNewDevice(link, PASSPHRASE))
        val waiting = assertIs<AppModel.AwaitingApproval>(phone.app.model.value)
        assertEquals(link.circle, waiting.circle)
        assertEquals(SyncState.SignedOut, assertNotNull(phone.app.syncOnce()).state, "not registered yet, so the server refuses the login")
        assertIs<AppModel.AwaitingApproval>(phone.app.model.value)

        // 2. The member checks the fingerprint, then adds it with a name. Bad input is refused first.
        val preview = assertNotNull(alice.app.previewDeviceCode(waiting.code))
        assertEquals(waiting.fingerprint, preview.fingerprint, "the two screens show the same fingerprint")
        assertNull(alice.app.previewDeviceCode("not a code"))
        assertEquals(AddDeviceResult.BadCode, alice.app.addDevice("nonsense", "Phone"))
        assertEquals(AddDeviceResult.NameTooLong, alice.app.addDevice(waiting.code, "n".repeat(41)))
        val added = assertIs<AddDeviceResult.Added>(alice.app.addDevice(waiting.code, "Phone"))
        assertEquals(1, added.keysWrapped); assertEquals(1, added.itemsShared)
        assertEquals(AddDeviceResult.AlreadyKnown, alice.app.addDevice(waiting.code, "Phone again"))
        listOf(alice, phone, bea).forEach { assertEquals(0, assertNotNull(it.app.syncOnce()).refused) }
        phone.app.syncOnce()

        // 3. The phone is in: same member, reads the circle and the member's own private item details.
        val phoneHome = home(phone.app)
        assertEquals(aliceId, phoneHome.me)
        assertTrue(phoneHome.isSteward && phoneHome.canRead)
        assertEquals("Garage at the back", phoneHome.items.single { it.item == ladder }.description)
        assertEquals(2, home(alice.app).myDevices.size)
        assertEquals(setOf(null, "Phone"), home(alice.app).myDevices.map { it.name }.toSet())
        assertEquals("Phone", home(phone.app).myDevices.single { it.isThisOne }.name)
        assertEquals(2, home(bea.app).people.single { it.member == aliceId }.devices)

        // 4. Removing a device: only your own other devices; a steward changes the key in the same batch.
        val phoneDevice = home(alice.app).myDevices.single { it.name == "Phone" }.device
        assertEquals(DeviceRemoval.NotAllowed, alice.app.removeDevice(home(alice.app).myDevices.single { it.isThisOne }.device), "not this device")
        assertEquals(DeviceRemoval.NotAllowed, alice.app.removeDevice(home(bea.app).myDevices.single().device), "not someone else's")
        assertEquals(DeviceRemoval.Removed, alice.app.removeDevice(phoneDevice))
        assertFalse(home(alice.app).needsRotation, "the steward's removal rotated the key at once")
        alice.app.syncOnce(); bea.app.syncOnce()
        assertEquals(SyncState.SignedOut, assertNotNull(phone.app.syncOnce()).state, "the removed device is signed out")
        assertEquals(1, home(alice.app).myDevices.size)

        // 5. A member who is not a steward removes a device: no rotation of her own, a steward is prompted.
        val beaLink = assertNotNull(DeviceLink.parse(assertNotNull(bea.app.deviceLink("https://circles.example"))))
        val beaTablet = browser(transport, crypto)
        beaTablet.app.open(); beaTablet.app.startOnNewDevice(beaLink, PASSPHRASE)
        val tabletCode = assertIs<AppModel.AwaitingApproval>(beaTablet.app.model.value).code
        assertIs<AddDeviceResult.Added>(bea.app.addDevice(tabletCode, ""))
        bea.app.syncOnce(); beaTablet.app.syncOnce(); beaTablet.app.syncOnce(); alice.app.syncOnce()
        assertEquals(null, home(bea.app).myDevices.single { !it.isThisOne }.name, "no name typed")
        assertEquals(DeviceRemoval.Removed, bea.app.removeDevice(home(bea.app).myDevices.single { !it.isThisOne }.device))
        bea.app.syncOnce(); alice.app.syncOnce()
        assertTrue(home(alice.app).needsRotation, "a steward is asked to change the key after Bea removed a device")
        assertEquals(RemoveResult.Removed, alice.app.rotateKeyNow())
        assertFalse(home(alice.app).needsRotation)
        listOf(alice, bea).forEach { assertEquals(0, assertNotNull(it.app.syncOnce()).refused) }
        assertEquals(Fingerprint.of(crypto, crypto.generateSigningKeyPair().publicKey).length, 24, "five groups of four and four spaces")
    }
}
