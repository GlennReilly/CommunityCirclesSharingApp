package circles.event

import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.encoding.ByteString
import circles.encoding.CborMap
import circles.encoding.CborText
import circles.encoding.Parsed
import circles.policy.ItemClass
import circles.policy.ItemId
import circles.policy.MemberId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val DAY = 20_000
private const val FROM = DAY + 1
private const val TO = DAY + 5
private const val ADDRESS = "12 Example St, Elermore Vale"

/** Items, loans and detail keys (ADR 0025), folded the way every client does, on every Crypto/Cipher pair. */
abstract class MarketContract {
    protected abstract suspend fun newCrypto(): Crypto
    protected abstract suspend fun newCipher(): Cipher

    private class World(val crypto: Crypto, val cipher: Cipher) {
        val circle = newCircleId(crypto)
        val circleKey = KeyRing.newCircleKey(crypto)
        val founder = person().also { it.keyRing = KeyRing.EMPTY.with(FIRST_EPOCH, circleKey) }
        val bea = person().also { it.keyRing = KeyRing.EMPTY.with(FIRST_EPOCH, circleKey) }
        val carol = person().also { it.keyRing = KeyRing.EMPTY.with(FIRST_EPOCH, circleKey) }
        val log = mutableListOf<Envelope>()
        val item = newItemId(crypto)
        val keyId = newDetailKeyId(crypto)
        val itemKey = ItemKeys.newKey(crypto)

        fun person() = EventAuthor(crypto, DeviceIdentity.generate(crypto, cipher), cipher)

        /** A second device for an existing member. */
        fun deviceOf(member: MemberId) = EventAuthor(crypto, DeviceIdentity.generate(crypto, cipher, member), cipher)

        fun foldAs(who: EventAuthor?, events: List<Envelope> = log): CircleView =
            CircleFold(crypto, cipher, who?.identity).fold(circle, events)

        fun reasons(extra: List<Envelope>, who: EventAuthor = founder): List<String> = foldAs(who, log + extra).rejected.map { it.reason }

        /** Founder creates the circle; bea and carol join; everyone is wrapped the circle key. */
        fun start(settings: StewardSettings? = null) {
            log += founder.circleCreate(circle, DAY, settings)
            log += founder.keyWrap(circle, founder.identity.registration, FIRST_EPOCH, circleKey, DAY)
            listOf(bea, carol).forEach { joiner ->
                val invite = InviteKey(crypto.generateSigningKeyPair())
                val event = founder.invite(circle, invite, DAY + 30, 1, DAY)
                log += event
                log += joiner.join(circle, event.eventId(crypto), invite, DAY)
                log += founder.keyWrap(circle, joiner.identity.registration, FIRST_EPOCH, circleKey, DAY)
            }
        }

        fun shareItem() {
            log += founder.itemUpsert(circle, DAY, item, "Extension ladder", ItemClass.ESSENTIAL, keyId, itemKey, listOf(founder.identity.registration))
            log += founder.itemDetail(circle, DAY, item, keyId, itemKey, CborMap.of("address" to CborText(ADDRESS)))
        }

        fun request(loan: LoanId = newLoanId(crypto), by: EventAuthor = bea) = loan to by.loanRequest(circle, DAY, loan, item, FROM, TO, "for the weekend")

        fun approve(loan: LoanId, devices: List<DeviceRegistration> = listOf(bea.identity.registration)) =
            founder.loanApprove(circle, DAY, loan, item, FROM, TO, itemKey, devices)

        fun addressOf(view: CircleView): String? =
            ((view.market.details[item] as? Parsed.Ok<CborMap>)?.value?.get("address") as? CborText)?.value
    }

    private suspend fun world() = World(newCrypto(), newCipher()).also { it.start() }

    /** Shares a new item of [itemClass] owned by [owner]; returns its id and key. */
    private fun World.share(owner: EventAuthor, itemClass: ItemClass): Pair<ItemId, ByteString> {
        val id = newItemId(crypto)
        val key = ItemKeys.newKey(crypto)
        log += owner.itemUpsert(circle, DAY, id, "Thing", itemClass, newDetailKeyId(crypto), key, listOf(owner.identity.registration))
        return id to key
    }

    private fun World.requestOf(by: EventAuthor, item: ItemId, day: Int = DAY) = by.loanRequest(circle, day, newLoanId(crypto), item, day + 1, day + 3)

    /** A whole good loan from [owner] to [borrower], returned OK on [returnDay]. */
    private fun World.goodLoan(owner: EventAuthor, borrower: EventAuthor, item: ItemId, key: ByteString, day: Int, returnDay: Int) {
        val loan = newLoanId(crypto)
        log += borrower.loanRequest(circle, day, loan, item, day, day + 2)
        log += owner.loanApprove(circle, day, loan, item, day, day + 2, key, listOf(borrower.identity.registration))
        log += owner.loanHandover(circle, day, loan)
        log += borrower.loanHandover(circle, day, loan)
        log += borrower.loanReturn(circle, returnDay, loan, ReturnOutcome.OK)
    }

    @Test
    fun aLoanGoesFromRequestToReturnAndOnlyTheApprovedBorrowerReadsTheDetail() = runTest {
        val w = world()
        w.shareItem()
        assertEquals(ADDRESS, w.addressOf(w.foldAs(w.founder)), "the owner reads their own detail")
        assertIs<Parsed.Invalid>(w.foldAs(w.bea).market.details.getValue(w.item), "no key before approval")

        val (loan, request) = w.request()
        w.log += request
        assertEquals(LoanStatus.REQUESTED, w.foldAs(w.founder).market.loans.getValue(loan).status)

        w.log += w.approve(loan)
        val approved = w.foldAs(w.bea)
        assertEquals(LoanStatus.APPROVED, approved.market.loans.getValue(loan).status)
        assertEquals(ADDRESS, w.addressOf(approved), "the approved borrower reads the detail")
        assertIs<Parsed.Invalid>(w.foldAs(w.carol).market.details.getValue(w.item), "other members cannot")

        w.log += w.founder.loanHandover(w.circle, DAY, loan)
        assertEquals(LoanStatus.APPROVED, w.foldAs(w.founder).market.loans.getValue(loan).status, "one side is not enough")
        w.log += w.bea.loanHandover(w.circle, DAY, loan)
        assertEquals(LoanStatus.ACTIVE, w.foldAs(w.founder).market.loans.getValue(loan).status)

        w.log += w.bea.loanReturn(w.circle, DAY + 5, loan, ReturnOutcome.OK)
        val done = w.foldAs(w.founder).market.loans.getValue(loan)
        assertEquals(LoanStatus.RETURNED, done.status)
        assertEquals(ReturnOutcome.OK, done.outcome)
        assertEquals(emptyList(), w.foldAs(w.founder).rejected)
    }

    @Test
    fun marketEventsKeepTheirContentOutOfThePublicPartAndTheServersReach() = runTest {
        val w = world()
        w.shareItem()
        val (_, request) = w.request()
        w.log += request
        val market = w.log.filter { isMarketType(it.type) }
        assertEquals(3, market.size)
        market.forEach {
            assertTrue(it.public.entries.isEmpty(), "${it.type} has no public part")
            assertFalse(it.encrypted.toHex().contains(ByteString.of("ladder".encodeToByteArray()).toHex()))
            assertFalse(it.encrypted.toHex().contains(ByteString.of("Elermore".encodeToByteArray()).toHex()))
            assertEquals(null, EnvelopeChecks.check(it))
        }
    }

    @Test
    fun aDeviceWithoutTheCircleKeySeesNothingAndRejectsNothing() = runTest {
        val w = world()
        w.shareItem()
        val outsider = w.person()
        val view = w.foldAs(outsider)
        assertTrue(view.market.items.isEmpty())
        assertEquals(emptyList(), view.rejected)
    }

    @Test
    fun archivingThenRestoringPutsTheItemBackAsItWasWithItsKeyDetailPhotosAndLoans() = runTest {
        val w = world()
        val owner = w.founder.identity.registration
        w.log += w.founder.itemUpsert(w.circle, DAY, w.item, "Extension ladder", ItemClass.ESSENTIAL, w.keyId, w.itemKey, listOf(owner), null, listOf(photo(1), photo(2)))
        w.log += w.founder.itemDetail(w.circle, DAY, w.item, w.keyId, w.itemKey, CborMap.of("address" to CborText(ADDRESS)))
        val (loan, request) = w.request()
        w.log += listOf(request, w.approve(loan), w.founder.loanHandover(w.circle, DAY, loan), w.bea.loanHandover(w.circle, DAY, loan), w.bea.loanReturn(w.circle, DAY, loan, ReturnOutcome.OK))
        val before = w.foldAs(w.founder).market.items.getValue(w.item)
        w.log += w.founder.itemRetire(w.circle, DAY + 1, w.item)
        assertTrue(w.foldAs(w.founder).market.items.getValue(w.item).retired)
        w.log += w.founder.itemRestore(w.circle, DAY + 2, w.item)
        val view = w.foldAs(w.founder)
        assertEquals(emptyList(), view.rejected)
        val after = view.market.items.getValue(w.item)
        assertEquals(false, after.retired)
        assertEquals(before, after, "same id, name, class, key id, key wraps, sealed details and photos")
        assertEquals(ADDRESS, w.addressOf(view), "the owner still reads the details, with the same key")
        assertEquals(LoanStatus.RETURNED, view.market.loans.getValue(loan).status, "old loans stay")
    }

    @Test
    fun aRestoredItemTakesRequestsAgainAndChangesAgain() = runTest {
        val w = world()
        w.shareItem()
        w.log += w.founder.itemRetire(w.circle, DAY, w.item)
        val (_, whileArchived) = w.request()
        assertEquals(listOf("The item has been archived, so it cannot be changed or asked for."), w.reasons(listOf(whileArchived)))
        w.log += w.founder.itemRestore(w.circle, DAY + 1, w.item)
        val (loan, again) = w.request(by = w.carol)
        w.log += again
        w.log += w.founder.itemUpsert(w.circle, DAY + 1, w.item, "Longer ladder", ItemClass.ESSENTIAL, w.keyId, w.itemKey, listOf(w.founder.identity.registration))
        val view = w.foldAs(w.founder)
        assertEquals(emptyList(), view.rejected)
        assertEquals(LoanStatus.REQUESTED, view.market.loans.getValue(loan).status)
        assertEquals("Longer ladder", view.market.items.getValue(w.item).name)
    }

    @Test
    fun onlyTheOwnerMayRestoreAndOnlyAnArchivedItemAndAnUnknownItemIsRefused() = runTest {
        val w = world()
        w.shareItem()
        assertEquals(listOf("The item is not archived, so there is nothing to restore."), w.reasons(listOf(w.founder.itemRestore(w.circle, DAY, w.item))))
        w.log += w.founder.itemRetire(w.circle, DAY, w.item)
        assertEquals(listOf("Only the owner of an item can restore it."), w.reasons(listOf(w.bea.itemRestore(w.circle, DAY, w.item))))
        assertEquals(listOf("That item is not in the circle."), w.reasons(listOf(w.founder.itemRestore(w.circle, DAY, newItemId(w.crypto)))))
        assertTrue(w.foldAs(w.founder, w.log + w.bea.itemRestore(w.circle, DAY, w.item)).market.items.getValue(w.item).retired, "a refused restore changes nothing")
        w.log += w.founder.itemRestore(w.circle, DAY + 1, w.item)
        assertEquals(listOf("The item is not archived, so there is nothing to restore."), w.reasons(listOf(w.founder.itemRestore(w.circle, DAY + 2, w.item))), "twice is refused")
    }

    @Test
    fun aLoanOpenAtArchiveTimeIsUntouchedByArchivingAndRestoring() = runTest {
        val w = world()
        w.shareItem()
        val (loan, request) = w.request()
        w.log += listOf(request, w.approve(loan), w.founder.loanHandover(w.circle, DAY, loan), w.bea.loanHandover(w.circle, DAY, loan))
        w.log += w.founder.itemRetire(w.circle, DAY + 1, w.item)
        w.log += w.founder.itemRestore(w.circle, DAY + 2, w.item)
        val view = w.foldAs(w.founder)
        assertEquals(LoanStatus.ACTIVE, view.market.loans.getValue(loan).status)
        w.log += w.bea.loanReturn(w.circle, DAY + 3, loan, ReturnOutcome.OK)
        assertEquals(LoanStatus.RETURNED, w.foldAs(w.founder).market.loans.getValue(loan).status)
        assertEquals(emptyList(), w.foldAs(w.founder).rejected)
    }

    @Test
    fun onlyTheOwnerMayChangeRetireOrAttachDetailToAnItem() = runTest {
        val w = world()
        w.shareItem()
        val reasons = w.reasons(
            listOf(
                w.bea.itemUpsert(w.circle, DAY, w.item, "Mine now", ItemClass.EVERYDAY, w.keyId, w.itemKey, listOf(w.bea.identity.registration)),
                w.bea.itemRetire(w.circle, DAY, w.item),
                w.bea.itemDetail(w.circle, DAY, w.item, w.keyId, w.itemKey, CborMap.of("address" to CborText("elsewhere"))),
                w.founder.itemDetail(w.circle, DAY, w.item, newDetailKeyId(w.crypto), w.itemKey, CborMap.of("address" to CborText("stale key"))),
                w.founder.itemUpsert(w.circle, DAY, newItemId(w.crypto), "Wrapped to bea", ItemClass.EVERYDAY, w.keyId, w.itemKey, listOf(w.bea.identity.registration)),
                w.founder.itemRetire(w.circle, DAY, newItemId(w.crypto)),
            ),
        )
        assertEquals(
            listOf(
                "Only the owner of an item can change it.",
                "Only the owner of an item can archive it.",
                "Only the owner of an item can add details to it.",
                "The item's details were locked with an old key, so they cannot be added.",
                "The item's key can only go to the owner's devices or to borrowers with an open loan.",
                "That item is not in the circle.",
            ),
            reasons,
        )
        assertEquals("Extension ladder", w.foldAs(w.founder).market.items.getValue(w.item).name)
    }

    @Test
    fun loanRequestsAndResponsesFollowTheRules() = runTest {
        val w = world()
        w.shareItem()
        val (loan, request) = w.request()
        w.log += request
        val (_, own) = w.request(by = w.founder)
        val (_, unknownItem) = newLoanId(w.crypto).let { it to w.bea.loanRequest(w.circle, DAY, it, newItemId(w.crypto), FROM, TO) }
        val duplicate = w.bea.loanRequest(w.circle, DAY, loan, w.item, FROM, TO)
        val notOwner = w.carol.loanApprove(w.circle, DAY, loan, w.item, FROM, TO, w.itemKey, listOf(w.bea.identity.registration))
        val wrongDevices = w.approve(loan, listOf(w.carol.identity.registration))
        val reasons = w.reasons(listOf(own, unknownItem, duplicate, notOwner, wrongDevices))
        assertEquals(
            listOf(
                "A member cannot borrow their own item.",
                "That item is not in the circle.",
                "That loan request has already been recorded.",
                "Only the owner of the item can answer a loan request.",
                "The item's key can only be sent to the borrower's devices.",
            ),
            reasons,
        )
    }

    @Test
    fun aLoanCanBeAnsweredOnceHandedOverByThePartiesAndReturnedByTheBorrower() = runTest {
        val w = world()
        w.shareItem()
        val (loan, request) = w.request()
        w.log += request
        val early = listOf(
            w.founder.loanHandover(w.circle, DAY, loan),
            w.bea.loanReturn(w.circle, DAY, loan, ReturnOutcome.OK),
        )
        assertEquals(
            listOf("A hand-over needs an approved loan that has not been handed over yet.", "Only a loan that has been handed over can be returned."),
            w.reasons(early),
        )
        w.log += w.approve(loan)
        val later = listOf(
            w.carol.loanHandover(w.circle, DAY, loan),
            w.founder.loanDecline(w.circle, DAY, loan, FROM, TO),
            w.founder.loanApprove(w.circle, DAY, loan, w.item, FROM, TO + 1, w.itemKey, listOf(w.bea.identity.registration)),
        )
        assertEquals(
            listOf("Only the owner or the borrower can confirm a hand-over.", "The loan request has already been answered.", "The loan request has already been answered."),
            w.reasons(later),
        )
        w.log += listOf(w.founder.loanHandover(w.circle, DAY, loan), w.bea.loanHandover(w.circle, DAY, loan))
        assertEquals(
            listOf("Only the borrower can report a loan as returned.", "A hand-over needs an approved loan that has not been handed over yet."),
            w.reasons(
                listOf(
                    w.founder.loanReturn(w.circle, DAY, loan, ReturnOutcome.OK),
                    w.bea.loanHandover(w.circle, DAY, loan), // the loan is already active
                ),
            ),
        )
        w.log += w.bea.loanReturn(w.circle, DAY, loan, ReturnOutcome.LATE)
        assertEquals(listOf("Only a loan that has been handed over can be returned."), w.reasons(listOf(w.bea.loanReturn(w.circle, DAY, loan, ReturnOutcome.OK))))
        assertEquals(ReturnOutcome.LATE, w.foldAs(w.founder).market.loans.getValue(loan).outcome)
    }

    @Test
    fun aDeclinedLoanIsFinalAndGivesNoKey() = runTest {
        val w = world()
        w.shareItem()
        val (loan, request) = w.request()
        w.log += request
        w.log += w.founder.loanDecline(w.circle, DAY, loan, FROM, TO, "not this week")
        val view = w.foldAs(w.bea)
        assertEquals(LoanStatus.DECLINED, view.market.loans.getValue(loan).status)
        assertIs<Parsed.Invalid>(view.market.details.getValue(w.item))
        assertEquals(listOf("The loan request has already been answered."), w.reasons(listOf(w.approve(loan)), w.bea))
    }

    @Test
    fun aSecondApprovalGivesTheBorrowersNewerDeviceTheKey() = runTest {
        val w = world()
        w.shareItem()
        val (loan, request) = w.request()
        w.log += request
        w.log += w.approve(loan)
        val phone = w.deviceOf(w.bea.identity.member)
        w.log += w.bea.deviceAdd(w.circle, phone.identity, DAY)
        w.log += w.founder.keyWrap(w.circle, phone.identity.registration, FIRST_EPOCH, w.circleKey, DAY)
        assertIs<Parsed.Invalid>(w.foldAs(phone).market.details.getValue(w.item), "the phone was added after the approval")
        w.log += w.approve(loan, listOf(phone.identity.registration))
        val view = w.foldAs(phone)
        assertEquals(ADDRESS, w.addressOf(view))
        assertEquals(LoanStatus.APPROVED, view.market.loans.getValue(loan).status)
        assertEquals(emptyList(), view.rejected)
    }

    @Test
    fun changingTheDetailKeyCutsOffEarlierBorrowersFromNewDetailOnly() = runTest {
        val w = world()
        w.shareItem()
        val (loan, request) = w.request()
        w.log += request
        w.log += w.approve(loan)
        assertEquals(ADDRESS, w.addressOf(w.foldAs(w.bea)))

        val newKeyId = newDetailKeyId(w.crypto)
        val newKey = ItemKeys.newKey(w.crypto)
        w.log += w.founder.itemUpsert(w.circle, DAY, w.item, "Extension ladder", ItemClass.ESSENTIAL, newKeyId, newKey, listOf(w.founder.identity.registration))
        w.log += w.founder.itemDetail(w.circle, DAY, w.item, newKeyId, newKey, CborMap.of("address" to CborText("new address")))
        val founderView = w.foldAs(w.founder)
        assertEquals("new address", w.addressOf(founderView))
        assertEquals(2, founderView.market.itemKeys.keys.count { it.first == w.item }, "the owner keeps both keys")
        assertIs<Parsed.Invalid>(w.foldAs(w.bea).market.details.getValue(w.item), "bea only holds the old key")
        assertEquals(emptyList(), founderView.rejected)
    }

    @Test
    fun aRetiredItemTakesNoNewRequestsButAnActiveLoanCanStillComeBack() = runTest {
        val w = world()
        w.shareItem()
        val (loan, request) = w.request()
        w.log += listOf(request, w.approve(loan), w.founder.loanHandover(w.circle, DAY, loan), w.bea.loanHandover(w.circle, DAY, loan))
        w.log += w.founder.itemRetire(w.circle, DAY, w.item)
        val (_, late) = w.request(by = w.carol)
        assertEquals(listOf("The item has been archived, so it cannot be changed or asked for."), w.reasons(listOf(late)))
        w.log += w.bea.loanReturn(w.circle, DAY, loan, ReturnOutcome.OK)
        val view = w.foldAs(w.founder)
        assertEquals(LoanStatus.RETURNED, view.market.loans.getValue(loan).status)
        assertTrue(view.market.items.getValue(w.item).retired)
        assertEquals(emptyList(), view.rejected)
    }

    @Test
    fun theAccessHookDecidesWhoMayRequest() = runTest {
        val w = world()
        w.shareItem()
        val (_, byBea) = w.request()
        val (_, byCarol) = w.request(by = w.carol)
        val gate: (MemberId, ItemId) -> Boolean = { member, _ -> member != w.carol.identity.member }
        val view = CircleFold(w.crypto, w.cipher, w.founder.identity, mayBorrow = gate).fold(w.circle, w.log + listOf(byBea, byCarol))
        assertEquals(listOf("This member may not borrow this item yet."), view.rejected.map { it.reason })
        assertEquals(1, view.market.loans.size)
    }

    @Test
    fun malformedPayloadsAreRejectedWithTheParsersReason() = runTest {
        val w = world()
        w.shareItem()
        val tooLong = w.bea.loanRequest(w.circle, DAY, newLoanId(w.crypto), w.item, FROM, FROM + MAX_LOAN_DAYS + 1)
        val reasons = w.reasons(listOf(tooLong))
        assertEquals(1, reasons.size)
        assertTrue(reasons.single().startsWith("the window must be"), reasons.toString())
    }

    @Test
    fun aNewMemberMayAskForEssentialsAndEverydayItemsButNotValuableOnes() = runTest {
        val w = world()
        val (essential, _) = w.share(w.founder, ItemClass.ESSENTIAL)
        val (everyday, _) = w.share(w.founder, ItemClass.EVERYDAY)
        val (valuable, _) = w.share(w.founder, ItemClass.VALUABLE)
        val (highValue, _) = w.share(w.founder, ItemClass.HIGH_VALUE)
        assertEquals(emptyList(), w.reasons(listOf(w.requestOf(w.bea, essential), w.requestOf(w.bea, everyday))))
        val refused = w.reasons(listOf(w.requestOf(w.bea, valuable), w.requestOf(w.bea, highValue)))
        assertEquals(2, refused.size)
        assertTrue(refused.all { it.startsWith("This request does not meet the circle's access rules. ") }, refused.toString())
        assertTrue(refused[0].contains("needs Level 2") && refused[1].contains("needs Level 3"), refused.toString())
        assertTrue(refused[0].contains("vouch"), "a new member is told the vouch route")
    }

    @Test
    fun aStewardCountsAsTrustedForTheirOwnRequestsButNotTheirOwnItems() = runTest {
        val w = world()
        val (heavy, _) = w.share(w.bea, ItemClass.HIGH_VALUE)
        assertEquals(emptyList(), w.reasons(listOf(w.requestOf(w.founder, heavy))), "the steward floor opens high-value items")
        assertEquals(1, w.reasons(listOf(w.requestOf(w.carol, heavy))).size, "an ordinary member is still held back")
        val (own, _) = w.share(w.founder, ItemClass.ESSENTIAL)
        assertTrue(w.reasons(listOf(w.requestOf(w.founder, own))).single().contains("own item"))
    }

    @Test
    fun theStewardFloorUsesTheStewardsOfThatMomentNotOfToday() = runTest {
        val w = World(newCrypto(), newCipher())
        w.start(StewardSettings(quorum = 1))
        val (heavy, _) = w.share(w.carol, ItemClass.HIGH_VALUE)
        val before = w.requestOf(w.bea, heavy, DAY)
        w.log += before
        w.log += w.founder.stewardAdd(w.circle, w.bea.identity.member, DAY + 1)
        val after = w.requestOf(w.bea, heavy, DAY + 2)
        w.log += after
        val view = w.foldAs(w.founder)
        val rejected = view.rejected.map { it.id }
        assertTrue(before.eventId(w.crypto) in rejected, "before bea was a steward the request fails")
        assertFalse(after.eventId(w.crypto) in rejected, "after, the floor applies")
    }

    @Test
    fun vouchesFollowTheRulesOfAdr0042() = runTest {
        val w = World(newCrypto(), newCipher())
        w.start()
        val founderVouch = w.founder.vouch(w.circle, DAY, w.carol.identity.member, DAY + 100)
        val byNewMember = w.bea.vouch(w.circle, DAY, w.carol.identity.member, DAY + 100)
        val ofSelf = w.founder.vouch(w.circle, DAY, w.founder.identity.member, DAY + 100)
        val ofStranger = w.founder.vouch(w.circle, DAY, newMemberId(w.crypto), DAY + 100)
        w.log += listOf(founderVouch, byNewMember, ofSelf, ofStranger)
        val view = w.foldAs(w.founder)
        assertEquals(listOf(w.founder.identity.member to w.carol.identity.member), view.market.vouches.map { it.voucher to it.vouchee })
        val reasons = view.rejected.associate { it.id to it.reason }
        assertTrue(reasons.getValue(byNewMember.eventId(w.crypto)).contains("Only a member at Level 2 or above can vouch"))
        assertTrue(reasons.getValue(ofSelf.eventId(w.crypto)).contains("themselves"))
        assertTrue(reasons.getValue(ofStranger.eventId(w.crypto)).contains("not in the circle"))
        assertFalse(founderVouch.eventId(w.crypto) in reasons)
    }

    @Test
    fun twoVouchesFromQualifiedMembersOpenRegularAccessUntilTheyExpire() = runTest {
        val w = World(newCrypto(), newCipher())
        w.start(StewardSettings(quorum = 1))
        w.log += w.founder.stewardAdd(w.circle, w.bea.identity.member, DAY)
        val (valuable, _) = w.share(w.founder, ItemClass.VALUABLE)
        w.log += w.founder.vouch(w.circle, DAY + 1, w.carol.identity.member, DAY + 400)
        val oneVouch = w.requestOf(w.carol, valuable, DAY + 2)
        w.log += oneVouch
        w.log += w.bea.vouch(w.circle, DAY + 3, w.carol.identity.member, DAY + 400)
        val twoVouches = w.requestOf(w.carol, valuable, DAY + 4)
        w.log += twoVouches
        // A vouch lives at most 180 days whatever the author wrote: by day + 190 both have lapsed.
        val lapsed = w.requestOf(w.carol, valuable, DAY + 190)
        w.log += lapsed
        val rejected = w.foldAs(w.founder).rejected.map { it.id }
        assertTrue(oneVouch.eventId(w.crypto) in rejected, "one vouch is not enough")
        assertFalse(twoVouches.eventId(w.crypto) in rejected, "two stewards' vouches open Regular access")
        assertTrue(lapsed.eventId(w.crypto) in rejected, "expired vouches no longer count")
    }

    @Test
    fun lendingAndGettingItBackEarnsAccessOnlyAfterTheGracePeriod() = runTest {
        val w = World(newCrypto(), newCipher())
        w.start()
        val (lent, lentKey) = w.share(w.bea, ItemClass.EVERYDAY)
        val (valuable, _) = w.share(w.founder, ItemClass.VALUABLE)
        // Bea lends the item four times, twice to carol and twice to the founder, all returned on the same day; each good
        // return earns 1.0 (up to three per counterpart), so she has 4.0 points once the grace period is over.
        listOf(w.carol, w.carol, w.founder, w.founder).forEach { w.goodLoan(w.bea, it, lent, lentKey, DAY, DAY + 2) }
        val lastReturn = DAY + 2
        val tooSoon = w.requestOf(w.bea, valuable, lastReturn + RETURN_GRACE_DAYS - 1)
        val afterGrace = w.requestOf(w.bea, valuable, lastReturn + RETURN_GRACE_DAYS)
        w.log += listOf(tooSoon, afterGrace)
        val view = w.foldAs(w.founder)
        val rejected = view.rejected.map { it.id }
        assertTrue(tooSoon.eventId(w.crypto) in rejected, "before the grace period the returns do not count")
        assertFalse(afterGrace.eventId(w.crypto) in rejected, "four good loans reach Regular")
        assertEquals(4, view.market.loans.values.count { it.status == LoanStatus.RETURNED && it.returnedDay == DAY + 2 })
    }

    @Test
    fun reportedDamageEarnsNothingAndAnLateOkCountsAgainstTheBorrower() = runTest {
        val w = World(newCrypto(), newCipher())
        w.start()
        val (item, key) = w.share(w.bea, ItemClass.EVERYDAY)
        val damaged = newLoanId(w.crypto)
        w.log += w.carol.loanRequest(w.circle, DAY, damaged, item, DAY, DAY + 2)
        w.log += w.bea.loanApprove(w.circle, DAY, damaged, item, DAY, DAY + 2, key, listOf(w.carol.identity.registration))
        w.log += w.bea.loanHandover(w.circle, DAY, damaged)
        w.log += w.carol.loanHandover(w.circle, DAY, damaged)
        w.log += w.carol.loanReturn(w.circle, DAY + 2, damaged, ReturnOutcome.DAMAGED_REPORTED)
        val loans = w.foldAs(w.founder).market.loans.values
        assertEquals(emptyList(), StandingRules.eventsFrom(loans, w.circle, DAY + 100))
        val late = loans.first().copy(outcome = ReturnOutcome.OK, returnedDay = DAY + 5)
        val kinds = StandingRules.eventsFrom(listOf(late), w.circle, DAY + 100).associate { it.member to it.kind }
        assertEquals(circles.policy.EventKind.LENT_ITEM_RETURNED_OK, kinds[late.owner])
        assertEquals(circles.policy.EventKind.RETURNED_LATE, kinds[late.borrower])
    }

    /** A finished loan from bea (owner) to carol (borrower) with [outcome] on [returnDay]; returns the loan id. */
    private fun World.loanBetweenBeaAndCarol(outcome: ReturnOutcome, returnDay: Int): LoanId {
        val (item, key) = share(bea, ItemClass.EVERYDAY)
        val loan = newLoanId(crypto)
        log += carol.loanRequest(circle, DAY, loan, item, DAY, DAY + 2)
        log += bea.loanApprove(circle, DAY, loan, item, DAY, DAY + 2, key, listOf(carol.identity.registration))
        log += bea.loanHandover(circle, DAY, loan)
        log += carol.loanHandover(circle, DAY, loan)
        log += carol.loanReturn(circle, returnDay, loan, outcome)
        return loan
    }

    private fun World.rejectedWith(vararg events: Envelope): List<String> = reasons(events.toList())

    @Test
    fun onlyTheOwnerMayConfirmAReturnAndOnlyOnceAndNotAfterADispute() = runTest {
        val w = world()
        val loan = w.loanBetweenBeaAndCarol(ReturnOutcome.OK, DAY + 2)
        val byBorrower = w.carol.confirmReturn(w.circle, DAY + 3, loan)
        val first = w.bea.confirmReturn(w.circle, DAY + 3, loan)
        val again = w.bea.confirmReturn(w.circle, DAY + 4, loan)
        val reasons = w.rejectedWith(byBorrower, first, again)
        assertEquals(2, reasons.size)
        assertTrue(reasons[0].contains("Only the owner of the item"))
        assertTrue(reasons[1].contains("This return has already been confirmed."))
        val damaged = w.loanBetweenBeaAndCarol(ReturnOutcome.DAMAGED_REPORTED, DAY + 2)
        assertTrue(w.rejectedWith(w.bea.confirmReturn(w.circle, DAY + 3, damaged)).single().contains("needs no confirmation"))
        val disputed = w.loanBetweenBeaAndCarol(ReturnOutcome.OK, DAY + 2)
        w.log += w.bea.loanDispute(w.circle, DAY + 3, disputed, DisputeClaim.DAMAGE)
        assertTrue(w.rejectedWith(w.bea.confirmReturn(w.circle, DAY + 4, disputed)).single().contains("in dispute"))
    }

    @Test
    fun disputesFollowTheRulesOfAdr0043() = runTest {
        val w = world()
        val loan = w.loanBetweenBeaAndCarol(ReturnOutcome.OK, DAY + 2)
        val notOwner = w.carol.loanDispute(w.circle, DAY + 3, loan, DisputeClaim.DAMAGE)
        val tooLate = w.bea.loanDispute(w.circle, DAY + 2 + DAMAGE_CLAIM_DAYS + 1, loan, DisputeClaim.DAMAGE)
        val good = w.bea.loanDispute(w.circle, DAY + 2 + DAMAGE_CLAIM_DAYS, loan, DisputeClaim.LOSS, "never came back")
        val twice = w.bea.loanDispute(w.circle, DAY + 3, loan, DisputeClaim.DAMAGE)
        val reasons = w.foldAs(w.founder, w.log + listOf(notOwner, tooLate, good, twice)).rejected.associate { it.id to it.reason }
        assertTrue(reasons.getValue(notOwner.eventId(w.crypto)).contains("Only the owner of the item"))
        assertTrue(reasons.getValue(tooLate.eventId(w.crypto)).contains("within $DAMAGE_CLAIM_DAYS days"))
        assertTrue(good.eventId(w.crypto) !in reasons, "the last allowed day is accepted")
        assertTrue(reasons.getValue(twice.eventId(w.crypto)).contains("already disputed"))
        val reported = w.loanBetweenBeaAndCarol(ReturnOutcome.LOST_REPORTED, DAY + 2)
        assertTrue(w.rejectedWith(w.bea.loanDispute(w.circle, DAY + 3, reported, DisputeClaim.LOSS)).single().contains("already reported a problem"))
        val notReturned = newLoanId(w.crypto)
        assertTrue(w.rejectedWith(w.bea.loanDispute(w.circle, DAY + 3, notReturned, DisputeClaim.LOSS)).single().contains("That loan is not in the circle."))
    }

    @Test
    fun onlyAStewardWhoIsNotAPartyMayResolveAndOnlyOnce() = runTest {
        val w = World(newCrypto(), newCipher())
        w.start()
        val loan = w.loanBetweenBeaAndCarol(ReturnOutcome.OK, DAY + 2)
        w.log += w.bea.loanDispute(w.circle, DAY + 3, loan, DisputeClaim.DAMAGE, "cracked handle")
        val byMember = w.carol.loanResolve(w.circle, DAY + 4, loan, DisputeOutcome.DISMISSED)
        val byOwner = w.bea.loanResolve(w.circle, DAY + 4, loan, DisputeOutcome.UPHELD)
        val byStewardOk = w.founder.loanResolve(w.circle, DAY + 5, loan, DisputeOutcome.UPHELD)
        val again = w.founder.loanResolve(w.circle, DAY + 6, loan, DisputeOutcome.DISMISSED)
        val reasons = w.foldAs(w.founder, w.log + listOf(byMember, byOwner, byStewardOk, again)).rejected.associate { it.id to it.reason }
        assertTrue(reasons.getValue(byMember.eventId(w.crypto)).contains("Only a steward"))
        assertTrue(reasons.getValue(byOwner.eventId(w.crypto)).contains("Only a steward"), "the owner is no steward here")
        assertTrue(byStewardOk.eventId(w.crypto) !in reasons)
        assertTrue(reasons.getValue(again.eventId(w.crypto)).contains("already been resolved"))
        val undisputed = w.loanBetweenBeaAndCarol(ReturnOutcome.OK, DAY + 2)
        assertTrue(w.rejectedWith(w.founder.loanResolve(w.circle, DAY + 4, undisputed, DisputeOutcome.UPHELD)).single().contains("not in dispute"))
    }

    @Test
    fun aStewardWhoIsAPartyCannotResolveTheirOwnDispute() = runTest {
        val w = World(newCrypto(), newCipher())
        w.start()
        val (item, key) = w.share(w.founder, ItemClass.EVERYDAY)
        val loan = newLoanId(w.crypto)
        w.log += w.bea.loanRequest(w.circle, DAY, loan, item, DAY, DAY + 2)
        w.log += w.founder.loanApprove(w.circle, DAY, loan, item, DAY, DAY + 2, key, listOf(w.bea.identity.registration))
        w.log += w.founder.loanHandover(w.circle, DAY, loan)
        w.log += w.bea.loanHandover(w.circle, DAY, loan)
        w.log += w.bea.loanReturn(w.circle, DAY + 2, loan, ReturnOutcome.OK)
        w.log += w.founder.loanDispute(w.circle, DAY + 3, loan, DisputeClaim.DAMAGE)
        val own = w.founder.loanResolve(w.circle, DAY + 4, loan, DisputeOutcome.UPHELD)
        val reason = w.foldAs(w.founder, w.log + own).rejected.single { it.id == own.eventId(w.crypto) }.reason
        assertTrue(reason.contains("part of the loan"), reason)
    }

    @Test
    fun anUpheldDisputeHoldsValuableItemsButNotEverydayOnes() = runTest {
        val w = World(newCrypto(), newCipher())
        w.start(StewardSettings(quorum = 1))
        w.log += w.founder.stewardAdd(w.circle, w.bea.identity.member, DAY)
        w.log += w.founder.vouch(w.circle, DAY, w.carol.identity.member, DAY + 400)
        w.log += w.bea.vouch(w.circle, DAY, w.carol.identity.member, DAY + 400)
        val (valuable, _) = w.share(w.founder, ItemClass.VALUABLE)
        val (everyday, _) = w.share(w.founder, ItemClass.EVERYDAY)
        // Carol, made Regular by two vouches, borrows a founder item and brings it back; the founder disputes it and
        // bea, a steward who is not a party, upholds the claim.
        val (lent, lentKey) = w.share(w.founder, ItemClass.EVERYDAY)
        val loan = newLoanId(w.crypto)
        w.log += w.carol.loanRequest(w.circle, DAY + 1, loan, lent, DAY + 1, DAY + 3)
        w.log += w.founder.loanApprove(w.circle, DAY + 1, loan, lent, DAY + 1, DAY + 3, lentKey, listOf(w.carol.identity.registration))
        w.log += w.founder.loanHandover(w.circle, DAY + 1, loan)
        w.log += w.carol.loanHandover(w.circle, DAY + 1, loan)
        w.log += w.carol.loanReturn(w.circle, DAY + 3, loan, ReturnOutcome.OK)
        w.log += w.founder.loanDispute(w.circle, DAY + 4, loan, DisputeClaim.DAMAGE, "the blade is bent")
        w.log += w.bea.loanResolve(w.circle, DAY + 6, loan, DisputeOutcome.UPHELD)
        val heldValuable = w.requestOf(w.carol, valuable, DAY + 10)
        val openEveryday = w.requestOf(w.carol, everyday, DAY + 10)
        val afterCooldown = w.requestOf(w.carol, valuable, DAY + 6 + 91)
        w.log += listOf(heldValuable, openEveryday)
        // The vouches expire after 180 days from DAY, so judge the post-cooldown request while they still live.
        w.log += afterCooldown
        val view = w.foldAs(w.founder)
        val reasons = view.rejected.associate { it.id to it.reason }
        assertTrue(heldValuable.eventId(w.crypto) in reasons, "the hold applies to valuable items")
        assertTrue(reasons.getValue(heldValuable.eventId(w.crypto)).contains("on hold"))
        assertTrue(openEveryday.eventId(w.crypto) !in reasons, "everyday items stay open")
        assertTrue(afterCooldown.eventId(w.crypto) !in reasons, "the hold is a pause that ends")
        assertEquals(DisputeOutcome.UPHELD, view.market.loans.getValue(loan).dispute?.outcome)
    }

    @Test
    fun aStewardsDevicesCanOnlyBeRevokedByThatStewardAndOrdinaryOnesByAnyStewardOrTheOwner() = runTest {
        val w = World(newCrypto(), newCipher())
        w.start(StewardSettings(quorum = 1))
        w.log += w.founder.stewardAdd(w.circle, w.bea.identity.member, DAY)
        val onSteward = w.founder.deviceRevoke(w.circle, w.bea.identity.device, DAY + 1)
        val onMember = w.founder.deviceRevoke(w.circle, w.carol.identity.device, DAY + 1)
        val ownDevice = w.bea.deviceRevoke(w.circle, w.bea.identity.device, DAY + 1)
        val view = w.foldAs(w.founder, w.log + listOf(onSteward, onMember, ownDevice))
        val reasons = view.rejected.associate { it.id to it.reason }
        assertTrue(reasons.getValue(onSteward.eventId(w.crypto)).contains("only be revoked by that steward"))
        assertFalse(onMember.eventId(w.crypto) in reasons, "a steward may remove an ordinary member")
        assertFalse(ownDevice.eventId(w.crypto) in reasons, "a steward may revoke their own device")
        assertTrue(view.devices.getValue(w.carol.identity.device).revoked)
        assertEquals(DAY + 1, view.devices.getValue(w.carol.identity.device).revokedDay)
    }

    @Test
    fun aDepartureNeedsARotationAndTheRotationClearsIt() = runTest {
        val w = World(newCrypto(), newCipher())
        w.start()
        assertEquals(emptySet(), w.foldAs(w.founder).unrotatedRemovals)
        w.log += w.founder.deviceRevoke(w.circle, w.carol.identity.device, DAY + 1)
        assertEquals(setOf(w.carol.identity.member), w.foldAs(w.founder).unrotatedRemovals)
        // The member leaving on their own counts too.
        w.log += w.bea.deviceRevoke(w.circle, w.bea.identity.device, DAY + 2)
        assertEquals(setOf(w.carol.identity.member, w.bea.identity.member), w.foldAs(w.founder).unrotatedRemovals)
        val remaining = listOf(w.founder.identity.registration)
        w.log += w.founder.rotateKeys(w.circle, 2, remaining, DAY + 3)
        // A device revoked before a rotation that left it out is no longer a danger, and neither is one that never had the key.
        assertEquals(emptySet(), w.foldAs(w.founder).unrotatedRemovals)
        val after = w.foldAs(w.founder)
        assertEquals(emptySet(), after.unrotatedRemovals)
        assertEquals(2, after.currentEpoch)
        assertEquals(emptyList(), after.rejected)
        // A later departure needs its own rotation.
        val dee = w.person()
        val invite = InviteKey(w.crypto.generateSigningKeyPair())
        val inviteEvent = w.founder.invite(w.circle, invite, DAY + 40, 1, DAY + 4)
        w.log += inviteEvent
        w.log += dee.join(w.circle, inviteEvent.eventId(w.crypto), invite, DAY + 4)
        w.log += w.founder.keyWrap(w.circle, dee.identity.registration, 2, w.founder.keyRing[2]!!, DAY + 4)
        w.log += w.founder.deviceRevoke(w.circle, dee.identity.device, DAY + 5)
        assertEquals(setOf(dee.identity.member), w.foldAs(w.founder).unrotatedRemovals, "dee held the current key")
    }

    @Test
    fun vouchesByAMemberWhoHasLeftStopCountingFromTheDayTheyLeft() = runTest {
        val w = World(newCrypto(), newCipher())
        w.start()
        // Bea earns Regular by lending four times (good returns on DAY + 2 count from DAY + 2 + the grace period).
        val (lent, lentKey) = w.share(w.bea, ItemClass.EVERYDAY)
        listOf(w.carol, w.carol, w.founder, w.founder).forEach { w.goodLoan(w.bea, it, lent, lentKey, DAY, DAY + 2) }
        val day = DAY + 2 + RETURN_GRACE_DAYS
        val (valuable, _) = w.share(w.founder, ItemClass.VALUABLE)
        // Two vouches for a new member (the steward's and the Regular member's) open Regular access.
        val dee = w.person().also { it.keyRing = KeyRing.EMPTY.with(FIRST_EPOCH, w.circleKey) }
        val invite = InviteKey(w.crypto.generateSigningKeyPair())
        val inviteEvent = w.founder.invite(w.circle, invite, day + 60, 1, day)
        w.log += inviteEvent
        w.log += dee.join(w.circle, inviteEvent.eventId(w.crypto), invite, day)
        w.log += w.founder.keyWrap(w.circle, dee.identity.registration, FIRST_EPOCH, w.circleKey, day)
        w.log += w.founder.vouch(w.circle, day, dee.identity.member, day + 100)
        w.log += w.bea.vouch(w.circle, day, dee.identity.member, day + 100)
        val before = w.requestOf(dee, valuable, day + 1)
        w.log += before
        // The founder removes bea on day + 3. A request dated day + 2 (judged after that, in log order) still has her vouch;
        // one dated day + 3 does not.
        w.log += w.founder.deviceRevoke(w.circle, w.bea.identity.device, day + 3)
        val lastDayCounted = w.requestOf(dee, valuable, day + 2)
        val dayGone = w.requestOf(dee, valuable, day + 3)
        w.log += listOf(lastDayCounted, dayGone)
        val view = w.foldAs(w.founder)
        val rejected = view.rejected.map { it.id }
        assertFalse(before.eventId(w.crypto) in rejected, "two live vouches")
        assertFalse(lastDayCounted.eventId(w.crypto) in rejected, "her vouch still counted before she left")
        assertTrue(dayGone.eventId(w.crypto) in rejected, "from the day she left only the steward's vouch remains")
        assertEquals(mapOf(w.bea.identity.member to day + 3), view.market.departed)
    }

    @Test
    fun aNewItemKeyMayGoToBorrowersWithAnOpenLoanButNotToOthers() = runTest {
        val w = world()
        w.shareItem()
        val (loan, request) = w.request()
        w.log += request
        w.log += w.approve(loan)
        val newKey = ItemKeys.newKey(w.crypto)
        val newId = newDetailKeyId(w.crypto)
        val toBorrower = w.founder.itemUpsert(w.circle, DAY, w.item, "Extension ladder", ItemClass.ESSENTIAL, newId, newKey, listOf(w.founder.identity.registration, w.bea.identity.registration))
        val toStranger = w.founder.itemUpsert(w.circle, DAY, w.item, "Extension ladder", ItemClass.ESSENTIAL, newId, newKey, listOf(w.founder.identity.registration, w.carol.identity.registration))
        assertEquals(listOf("The item's key can only go to the owner's devices or to borrowers with an open loan."), w.reasons(listOf(toStranger)))
        val detail = w.founder.itemDetail(w.circle, DAY, w.item, newId, newKey, CborMap.of("address" to CborText(ADDRESS)))
        val view = w.foldAs(w.bea, w.log + listOf(toBorrower, detail))
        assertEquals(emptyList(), view.rejected)
        assertEquals(ADDRESS, w.addressOf(view), "the borrower reads the new details with the new key")
        assertEquals(null, w.addressOf(w.foldAs(w.carol, w.log + listOf(toBorrower, detail))), "someone who was never sent it cannot")
        // Once the loan is returned the borrower is no longer one of the people who may be sent a key.
        val lender = w.foldAs(w.founder, w.log + listOf(w.founder.loanHandover(w.circle, DAY, loan), w.bea.loanHandover(w.circle, DAY, loan), w.bea.loanReturn(w.circle, DAY, loan, ReturnOutcome.OK), toBorrower))
        assertEquals(listOf("The item's key can only go to the owner's devices or to borrowers with an open loan."), lender.rejected.map { it.reason })
    }

    // ---- pauses (ADR 0050) ----

    /** Three stewards (so three trusted reviewers) and an ordinary member, dave, who is the usual subject. */
    private fun World.pauseCircle(): EventAuthor {
        start(StewardSettings(quorum = 1))
        log += founder.stewardAdd(circle, bea.identity.member, DAY)
        log += founder.stewardAdd(circle, carol.identity.member, DAY)
        val dave = person().also { it.keyRing = KeyRing.EMPTY.with(FIRST_EPOCH, circleKey) }
        val invite = InviteKey(crypto.generateSigningKeyPair())
        val event = founder.invite(circle, invite, DAY + 30, 1, DAY)
        log += event
        log += dave.join(circle, event.eventId(crypto), invite, DAY)
        log += founder.keyWrap(circle, dave.identity.registration, FIRST_EPOCH, circleKey, DAY)
        return dave
    }

    private fun CircleView.pause(id: PauseId): PauseState = market.pauses.single { it.pause == id }

    @Test
    fun twoReviewersPlaceAPauseThatEndsOnItsOwnAndEssentialsStayOpen() = runTest {
        val w = World(newCrypto(), newCipher())
        val dave = w.pauseCircle()
        val id = newPauseId(w.crypto)
        w.log += w.founder.pausePropose(w.circle, DAY, id, dave.identity.member, "keeps keeping things", 30)
        var view = w.foldAs(dave)
        assertEquals(emptyList(), view.rejected)
        assertEquals(PauseStatus.PROPOSED, view.pause(id).statusOn(DAY))
        assertEquals(2, view.pause(id).needed, "three reviewers can be asked, two are needed")
        assertEquals(emptyList(), view.market.livePausesOn(w.circle, DAY))
        w.log += w.bea.pauseReview(w.circle, DAY + 1, id, true)
        view = w.foldAs(dave)
        assertEquals(PauseStatus.LIVE, view.pause(id).statusOn(DAY + 1))
        assertEquals(DAY + 31, view.pause(id).untilDay)
        assertEquals(1, view.market.livePausesOn(w.circle, DAY + 5).size)
        assertEquals(PauseStatus.ENDED, view.pause(id).statusOn(DAY + 31))
        assertEquals(emptyList(), view.market.livePausesOn(w.circle, DAY + 31))
        // The access rule reads it: an everyday item is paused, an essential one is not.
        val everyday = w.share(w.founder, ItemClass.EVERYDAY)
        val essential = w.share(w.founder, ItemClass.ESSENTIAL)
        val request = w.requestOf(dave, essential.first, DAY + 2)
        val view2 = w.foldAs(dave, w.log + request)
        assertEquals(emptyList(), view2.rejected, "essentials stay open during a pause")
        assertTrue(everyday.first != essential.first)
    }

    @Test
    fun theRulesForProposingReviewingAndLifting() = runTest {
        val w = World(newCrypto(), newCipher())
        val dave = w.pauseCircle()
        val id = newPauseId(w.crypto)
        val proposal = w.founder.pausePropose(w.circle, DAY, id, dave.identity.member, "a reason", 30)
        w.log += proposal
        fun rejected(vararg extra: Envelope) = w.foldAs(w.founder, w.log + extra.toList()).rejected.map { it.reason }
        assertEquals(listOf("A member cannot propose pausing themselves."), rejected(w.bea.pausePropose(w.circle, DAY, newPauseId(w.crypto), w.bea.identity.member, "x", 5)))
        assertEquals(listOf("A steward cannot be paused; the rules for stewards apply instead."), rejected(w.bea.pausePropose(w.circle, DAY, newPauseId(w.crypto), w.carol.identity.member, "x", 5)))
        assertEquals(listOf("Only a member at Level 3 or above can propose a pause."), rejected(dave.pausePropose(w.circle, DAY, newPauseId(w.crypto), w.bea.identity.member, "x", 5)).let { it.take(1) })
        assertEquals(listOf("That member already has a pause or a pause proposal."), rejected(w.carol.pausePropose(w.circle, DAY, newPauseId(w.crypto), dave.identity.member, "again", 5)))
        assertEquals(listOf("A member cannot review a pause about themselves."), rejected(dave.pauseReview(w.circle, DAY, id, true)))
        assertEquals(listOf("That pause is not in the circle."), rejected(w.bea.pauseReview(w.circle, DAY, newPauseId(w.crypto), true)))
        assertEquals(listOf("The pause is over."), rejected(w.bea.pauseReview(w.circle, DAY + PAUSE_PROPOSAL_WINDOW_DAYS + 1, id, true)), "a proposal lapses")
        // Lifting: not by a bystander, not by a reviewer before any appeal; the proposer may cancel before it takes effect.
        val notAllowed = "A pause can only be lifted by a reviewer after an appeal, by another steward, or by the proposer before it takes effect."
        assertEquals(listOf(notAllowed), rejected(dave.pauseLift(w.circle, DAY, id)))
        assertEquals(emptyList(), rejected(w.founder.pauseLift(w.circle, DAY, id)))
        // Reviews take effect, then a reviewer withdraws below the number needed and it ends.
        w.log += w.bea.pauseReview(w.circle, DAY, id, true)
        assertEquals(PauseStatus.LIVE, w.foldAs(dave).pause(id).statusOn(DAY))
        assertEquals(listOf("The pause is already in force."), rejected(w.carol.pauseReview(w.circle, DAY, id, true)))
        assertEquals(listOf("The pause is already in force."), rejected(w.carol.pauseReview(w.circle, DAY, id, false)))
        val withdrawn = w.foldAs(dave, w.log + w.bea.pauseReview(w.circle, DAY + 2, id, false))
        assertEquals(PauseStatus.LIFTED, withdrawn.pause(id).statusOn(DAY + 3))
        assertEquals(emptyList(), withdrawn.market.livePausesOn(w.circle, DAY + 3))
        // The subject appeals once; afterwards a reviewer may lift; a steward who is not the proposer always may.
        assertEquals(listOf("Only the member the pause is about can appeal it."), rejected(w.bea.pauseAppeal(w.circle, DAY, id, "no")))
        w.log += dave.pauseAppeal(w.circle, DAY + 1, id, "it was the weather")
        assertEquals(listOf("The member the pause is about has already appealed."), rejected(dave.pauseAppeal(w.circle, DAY + 1, id, "again")))
        assertEquals("it was the weather", w.foldAs(dave).pause(id).appealNote)
        assertEquals(false, w.foldAs(dave).market.livePausesOn(w.circle, DAY + 1).single().appealOpen)
        assertEquals(PauseStatus.LIFTED, w.foldAs(dave, w.log + w.bea.pauseLift(w.circle, DAY + 2, id)).pause(id).statusOn(DAY + 2))
        assertEquals(PauseStatus.LIFTED, w.foldAs(dave, w.log + w.carol.pauseLift(w.circle, DAY + 2, id)).pause(id).statusOn(DAY + 2))
    }

    @Test
    fun aCircleWithTooFewTrustedMembersCannotPauseAnyone() = runTest {
        val w = World(newCrypto(), newCipher())
        w.start(StewardSettings(quorum = 1)) // only the founder is a steward, bea and carol are new members
        val id = newPauseId(w.crypto)
        assertEquals(
            listOf("The circle has too few members at Level 3 or above to review a pause."),
            w.reasons(listOf(w.founder.pausePropose(w.circle, DAY, id, w.bea.identity.member, "x", 5))),
        )
    }

    // ---- circle rules (ADR 0051) ----

    private fun World.defaults() = ConfigSettings.of(StandingRules.defaultConfig(circle))

    @Test
    fun aConfigTakesEffectFromTheDayItWasAgreedAndEarlierDaysKeepTheOldRules() = runTest {
        val w = World(newCrypto(), newCipher())
        w.start(StewardSettings(quorum = 1))
        val changed = w.defaults().copy(halfLifeDays = 90, vouchesForRegular = 3)
        w.log += w.founder.configUpdate(w.circle, DAY + 10, changed)
        val view = w.foldAs(w.founder)
        assertEquals(emptyList(), view.rejected)
        assertEquals(180, view.market.configOn(w.circle, DAY + 9).halfLifeDays, "before the day it took effect")
        assertEquals(90, view.market.configOn(w.circle, DAY + 10).halfLifeDays)
        assertEquals(3, view.market.configOn(w.circle, DAY + 400).vouchesForRegular)
        assertEquals(listOf(ConfigVersion(DAY + 10, changed)), view.market.configs)
        // A later change replaces it from its own day on.
        val later = changed.copy(halfLifeDays = 60)
        w.log += w.founder.configUpdate(w.circle, DAY + 20, later)
        val view2 = w.foldAs(w.founder)
        assertEquals(90, view2.market.configOn(w.circle, DAY + 19).halfLifeDays)
        assertEquals(60, view2.market.configOn(w.circle, DAY + 20).halfLifeDays)
    }

    @Test
    fun configRulesRefuseNonStewardsBadValuesAndNoChange() = runTest {
        val w = World(newCrypto(), newCipher())
        w.start(StewardSettings(quorum = 1))
        val d = w.defaults()
        fun rejected(by: EventAuthor, settings: ConfigSettings) = w.reasons(listOf(by.configUpdate(w.circle, DAY, settings)))
        assertEquals(listOf("Only a steward can change the circle's rules."), rejected(w.bea, d.copy(halfLifeDays = 90)))
        assertEquals(listOf("Those are already the circle's rules."), rejected(w.founder, d))
        assertEquals(listOf("Points can fade by half over 30 to 730 days."), rejected(w.founder, d.copy(halfLifeDays = 10)))
        assertEquals(listOf("The reviewers needed for a pause must be from 2 to 9."), rejected(w.founder, d.copy(reviewersForPause = 1)))
        assertEquals(listOf("The points must rise from each level to the next."), rejected(w.founder, d.copy(regularTenths = d.trustedTenths)))
        assertEquals(listOf("Each level's points can be from 0.1 to 10000."), rejected(w.founder, d.copy(stewardTenths = 0)))
        assertEquals(listOf("Higher-value items can be on hold for 0 to 365 days after an unreported incident."), rejected(w.founder, d.copy(incidentCooldownDays = 366)))
    }

    @Test
    fun withAQuorumOfTwoTheSameSettingsMustBeSentByTwoStewardsAfterTheCoolingOffAndInTime() = runTest {
        val w = World(newCrypto(), newCipher())
        w.start(StewardSettings(quorum = 2))
        w.log += w.founder.stewardAdd(w.circle, w.bea.identity.member, DAY)
        val wanted = w.defaults().copy(defaultPauseDays = 20)
        val other = w.defaults().copy(defaultPauseDays = 21)
        w.log += w.founder.configUpdate(w.circle, DAY, wanted)
        var view = w.foldAs(w.founder)
        assertEquals(emptyList(), view.market.configs)
        assertEquals(setOf(w.founder.identity.member), view.market.pendingConfigs.single().voters)
        // Bea is a new steward: she may not vote for 7 days; a vote for different settings does not count towards these.
        assertEquals(listOf("A new steward cannot vote until ${circles.app.ReasonDates.token(DAY + 7)}."), w.reasons(listOf(w.bea.configUpdate(w.circle, DAY + 1, wanted))))
        view = w.foldAs(w.founder, w.log + w.bea.configUpdate(w.circle, DAY + 7, other))
        assertEquals(emptyList(), view.market.configs)
        assertEquals(2, view.market.pendingConfigs.size)
        view = w.foldAs(w.founder, w.log + w.bea.configUpdate(w.circle, DAY + 7, wanted))
        assertEquals(listOf(ConfigVersion(DAY + 7, wanted)), view.market.configs)
        assertEquals(20, view.market.configOn(w.circle, DAY + 7).defaultPauseDays)
        assertEquals(30, view.market.configOn(w.circle, DAY + 6).defaultPauseDays)
        // A first vote that is more than 14 days old no longer counts.
        val stale = w.foldAs(w.founder, w.log + w.bea.configUpdate(w.circle, DAY + 15, wanted))
        assertEquals(emptyList(), stale.market.configs)
        assertEquals(DAY + 15, stale.market.pendingConfigs.single { it.settings == wanted }.firstDay)
    }

    // ---- loan windows (ADR 0052) ----

    @Test
    fun anItemCannotBePromisedTwiceForTheSameDaysButMayBeAskedForTwice() = runTest {
        val w = world()
        w.shareItem()
        val promised = "The item is already promised to someone for some of those days."
        val (first, firstRequest) = w.request(by = w.bea)
        w.log += firstRequest
        val (second, secondRequest) = w.request(by = w.carol)
        w.log += secondRequest
        assertEquals(emptyList(), w.foldAs(w.founder).rejected, "two people may ask for the same days")
        w.log += w.approve(first)
        // The second request can no longer be approved, and a new overlapping one is refused; the next days are free.
        assertEquals(listOf(promised), w.reasons(listOf(w.founder.loanApprove(w.circle, DAY, second, w.item, FROM, TO, w.itemKey, listOf(w.carol.identity.registration)))))
        assertEquals(listOf(promised), w.reasons(listOf(w.carol.loanRequest(w.circle, DAY, newLoanId(w.crypto), w.item, TO, TO + 2))), "the last day counts")
        assertEquals(emptyList(), w.reasons(listOf(w.carol.loanRequest(w.circle, DAY, newLoanId(w.crypto), w.item, TO + 1, TO + 2))))
        assertEquals(emptyList(), w.reasons(listOf(w.carol.loanRequest(w.circle, DAY, newLoanId(w.crypto), w.item, FROM - 3, FROM - 1))))
        // Declining is always possible; cancelling the first one frees the days.
        assertEquals(emptyList(), w.reasons(listOf(w.founder.loanDecline(w.circle, DAY, second, FROM, TO))))
        val cancelled = w.foldAs(w.founder, w.log + w.bea.loanCancel(w.circle, DAY, first))
        assertEquals(emptyList(), cancelled.rejected)
        assertEquals(LoanStatus.CANCELLED, cancelled.market.loans.getValue(first).status)
        val freed = w.founder.loanApprove(w.circle, DAY, second, w.item, FROM, TO, w.itemKey, listOf(w.carol.identity.registration))
        assertEquals(LoanStatus.APPROVED, w.foldAs(w.carol, w.log + w.bea.loanCancel(w.circle, DAY, first) + freed).market.loans.getValue(second).status)
    }

    @Test
    fun onlyTheBorrowerMayCancelAndNotOnceTheItemHasChangedHands() = runTest {
        val w = world()
        w.shareItem()
        val (loan, request) = w.request(by = w.bea)
        w.log += request
        val onlyBorrower = "Only the borrower can cancel a loan."
        assertEquals(listOf(onlyBorrower), w.reasons(listOf(w.founder.loanCancel(w.circle, DAY, loan))))
        assertEquals(listOf(onlyBorrower), w.reasons(listOf(w.carol.loanCancel(w.circle, DAY, loan))))
        assertEquals(listOf("That loan is not in the circle."), w.reasons(listOf(w.bea.loanCancel(w.circle, DAY, newLoanId(w.crypto)))))
        w.log += w.approve(loan)
        assertEquals(emptyList(), w.reasons(listOf(w.bea.loanCancel(w.circle, DAY, loan))), "an approval can be withdrawn before the hand-over")
        w.log += w.founder.loanHandover(w.circle, DAY, loan)
        w.log += w.bea.loanHandover(w.circle, DAY, loan)
        assertEquals(listOf("A loan can only be cancelled while it is a request or approved and not yet handed over."), w.reasons(listOf(w.bea.loanCancel(w.circle, DAY, loan))))
    }

    // ---- contribution notes (ADR 0053) ----

    private fun World.pointsOf(view: CircleView, member: MemberId, day: Int): Double =
        StandingRules.standingOf(member, StandingRules.defaultConfig(circle), view.market.loans.values, emptyList(), view.stewards, day, view.market.contributions).points

    @Test
    fun aContributionCountsFromTheDayTheNamedPersonConfirmsAndNotBefore() = runTest {
        val w = world()
        val note = newNoteId(w.crypto)
        w.log += w.bea.standingNote(w.circle, DAY, note, ContributionKind.SKILL_SHARE_HELPED, w.carol.identity.member, "fixed the bike")
        assertEquals(emptyList(), w.foldAs(w.bea).rejected)
        assertEquals(0.0, w.pointsOf(w.foldAs(w.bea), w.bea.identity.member, DAY + 6), "an unconfirmed note counts for nothing")
        w.log += w.carol.confirmNote(w.circle, DAY + 5, note)
        val view = w.foldAs(w.bea)
        assertEquals(emptyList(), view.rejected)
        assertEquals(0.0, w.pointsOf(view, w.bea.identity.member, DAY + 4))
        assertEquals(1.5, w.pointsOf(view, w.bea.identity.member, DAY + 5), "the points of SKILL_SHARE_HELPED")
        assertEquals(0.0, w.pointsOf(view, w.carol.identity.member, DAY + 5), "the person who confirms earns nothing")
        assertEquals(DAY + 5, view.market.contributions.single().confirmedDay)
    }

    @Test
    fun theRulesForNotesAndTheirConfirmation() = runTest {
        val w = world()
        fun note(by: EventAuthor, with: EventAuthor, day: Int = DAY, kind: ContributionKind = ContributionKind.HOSTED_OR_ORGANISED) =
            by.standingNote(w.circle, day, newNoteId(w.crypto), kind, with.identity.member, "")
        assertEquals(listOf("A contribution note must name someone other than its author."), w.reasons(listOf(note(w.bea, w.bea))))
        assertEquals(listOf("Both people in a contribution note must be members of the circle."), w.reasons(listOf(w.bea.standingNote(w.circle, DAY, newNoteId(w.crypto), ContributionKind.HOSTED_OR_ORGANISED, MemberId("9".repeat(32)), ""))))
        // The founder is a steward, so counts as trusted: only a new member can be mentored.
        assertEquals(listOf("Only a new member can be named as mentored."), w.reasons(listOf(note(w.bea, w.founder, kind = ContributionKind.MENTORED_NEW_MEMBER))))
        assertEquals(emptyList(), w.reasons(listOf(note(w.founder, w.carol, kind = ContributionKind.MENTORED_NEW_MEMBER))))
        // Three notes in 30 days; the fourth waits until the first is 30 days old.
        val first = note(w.bea, w.carol, DAY)
        w.log += first; w.log += note(w.bea, w.carol, DAY + 1); w.log += note(w.bea, w.founder, DAY + 2)
        assertEquals(listOf("A member can create at most 3 contribution notes in 30 days."), w.reasons(listOf(note(w.bea, w.carol, DAY + 29))))
        assertEquals(emptyList(), w.reasons(listOf(note(w.bea, w.carol, DAY + 30))))
        // Only the named person confirms, once, and in time.
        val id = w.foldAs(w.bea).market.contributions.first().note
        assertEquals(listOf("Only the person named in a contribution note can confirm it."), w.reasons(listOf(w.founder.confirmNote(w.circle, DAY, id))))
        assertEquals(listOf("Only the person named in a contribution note can confirm it."), w.reasons(listOf(w.bea.confirmNote(w.circle, DAY, id))))
        assertEquals(listOf("That contribution note is not in the circle."), w.reasons(listOf(w.carol.confirmNote(w.circle, DAY, newNoteId(w.crypto)))))
        assertEquals(listOf("This contribution note has lapsed, so it can no longer be confirmed."), w.reasons(listOf(w.carol.confirmNote(w.circle, DAY + 31, id))))
        w.log += w.carol.confirmNote(w.circle, DAY + 3, id)
        assertEquals(listOf("This contribution note has already been confirmed."), w.reasons(listOf(w.carol.confirmNote(w.circle, DAY + 4, id))))
    }

    // ---- skill offers (ADR 0055) ----

    @Test
    fun aMemberOffersChangesAndRetiresASkillAndOthersCannot() = runTest {
        val w = world()
        val id = newSkillId(w.crypto)
        w.log += w.bea.skillUpsert(w.circle, DAY, id, "Bike repair", "Punctures and brakes", setOf(OpenTo.GIFT, OpenTo.THANKS))
        var view = w.foldAs(w.carol)
        assertEquals(emptyList(), view.rejected)
        assertEquals(SkillState(id, w.bea.identity.member, "Bike repair", "Punctures and brakes", setOf(OpenTo.GIFT, OpenTo.THANKS)), view.market.skills.single())
        // The author may change it whole; nobody else may change or retire it.
        w.log += w.bea.skillUpsert(w.circle, DAY + 1, id, "Bike and wheelchair repair", "", setOf(OpenTo.SWAP))
        assertEquals("Bike and wheelchair repair", w.foldAs(w.carol).market.skills.single().name)
        assertEquals(listOf("Only the member who made a skill offer can change it."), w.reasons(listOf(w.carol.skillUpsert(w.circle, DAY, id, "Mine now", "", emptySet()))))
        assertEquals(listOf("Only the member who made a skill offer can remove it."), w.reasons(listOf(w.carol.skillRetire(w.circle, DAY, id))))
        assertEquals(listOf("That skill offer is not in the circle."), w.reasons(listOf(w.bea.skillRetire(w.circle, DAY, newSkillId(w.crypto)))))
        w.log += w.bea.skillRetire(w.circle, DAY + 2, id)
        view = w.foldAs(w.carol)
        assertEquals(true, view.market.skills.single().retired)
        assertEquals(listOf("The skill offer has been removed, so it cannot be changed."), w.reasons(listOf(w.bea.skillUpsert(w.circle, DAY + 3, id, "Again", "", emptySet()))))
        assertEquals(listOf("The skill offer is already removed."), w.reasons(listOf(w.bea.skillRetire(w.circle, DAY + 3, id))))
    }

    @Test
    fun skillTextIsLimitedAndAMemberHasAtMostTenLiveOffers() = runTest {
        val w = world()
        assertEquals(listOf("name must be 1 to 60 characters"), w.reasons(listOf(w.bea.skillUpsert(w.circle, DAY, newSkillId(w.crypto), "   ", "", emptySet()))))
        assertEquals(listOf("name must be 1 to 60 characters"), w.reasons(listOf(w.bea.skillUpsert(w.circle, DAY, newSkillId(w.crypto), "n".repeat(61), "", emptySet()))))
        assertEquals(listOf("details must be at most 500 characters"), w.reasons(listOf(w.bea.skillUpsert(w.circle, DAY, newSkillId(w.crypto), "ok", "d".repeat(501), emptySet()))))
        val ids = List(10) { newSkillId(w.crypto) }
        ids.forEach { w.log += w.bea.skillUpsert(w.circle, DAY, it, "skill", "", emptySet()) }
        assertEquals(emptyList(), w.foldAs(w.bea).rejected)
        assertEquals(listOf("A member can have at most 10 skill offers at a time."), w.reasons(listOf(w.bea.skillUpsert(w.circle, DAY, newSkillId(w.crypto), "one too many", "", emptySet()))))
        assertEquals(emptyList(), w.reasons(listOf(w.carol.skillUpsert(w.circle, DAY, newSkillId(w.crypto), "someone else", "", emptySet()))), "the limit is per member")
        // Retiring one makes room.
        w.log += w.bea.skillRetire(w.circle, DAY + 1, ids.first())
        assertEquals(emptyList(), w.reasons(listOf(w.bea.skillUpsert(w.circle, DAY + 1, newSkillId(w.crypto), "now fits", "", setOf(OpenTo.GIFT, OpenTo.SWAP, OpenTo.THANKS)))))
    }

    // ---- display names (ADR 0068) ----

    @Test
    fun aMemberSetsAndChangesTheirNameAndEveryChangeClearsItsConfirmations() = runTest {
        val w = world()
        val carol = w.carol.identity.member
        val first = w.carol.memberProfile(w.circle, DAY, "Carol Ng")
        w.log += first
        assertFalse(first.encrypted.toHex().contains(ByteString.of("Carol".encodeToByteArray()).toHex()), "the name is sealed")
        assertTrue(first.public.entries.isEmpty())
        var view = w.foldAs(w.bea)
        assertEquals("Carol Ng", view.market.names.getValue(carol).name)
        assertFalse(view.market.nameConfirmed(carol), "not confirmed until someone does")
        w.log += w.founder.confirmName(w.circle, DAY, carol, first.eventId(w.crypto), "Carol Ng")
        view = w.foldAs(w.bea)
        assertEquals(emptyList(), view.rejected)
        assertEquals(setOf(w.founder.identity.member), view.market.nameConfirmers(carol))
        assertTrue(view.market.nameConfirmed(carol))
        assertEquals(listOf("You have already confirmed this member's identity."), w.reasons(listOf(w.founder.confirmName(w.circle, DAY, carol, first.eventId(w.crypto), "Carol Ng"))))
        assertEquals(listOf("That is already the member's name."), w.reasons(listOf(w.carol.memberProfile(w.circle, DAY, "Carol Ng"))))

        w.log += w.carol.memberProfile(w.circle, DAY + 1, "Carol N.")
        view = w.foldAs(w.bea)
        assertEquals("Carol N.", view.market.names.getValue(carol).name)
        assertEquals(emptySet(), view.market.nameConfirmers(carol), "a new name starts unconfirmed")
        // Going back to the first name is a change too: a confirmation of the first one is stale, and so is one with the wrong text.
        val again = w.carol.memberProfile(w.circle, DAY + 2, "Carol Ng")
        w.log += again
        assertEquals(
            listOf("The member's name has changed since, so this confirmation no longer applies.", "The member's name has changed since, so this confirmation no longer applies."),
            w.reasons(
                listOf(
                    w.founder.confirmName(w.circle, DAY + 2, carol, first.eventId(w.crypto), "Carol Ng"),
                    w.founder.confirmName(w.circle, DAY + 2, carol, again.eventId(w.crypto), "Carol N."),
                ),
            ),
        )
        assertEquals(emptyList(), w.reasons(listOf(w.founder.confirmName(w.circle, DAY + 2, carol, again.eventId(w.crypto), "Carol Ng"))))
    }

    @Test
    fun onlyAnotherMemberAtRegularOrAboveMayConfirmAndBadNamesAreRefused() = runTest {
        val w = world()
        val bea = w.bea.identity.member
        val named = w.bea.memberProfile(w.circle, DAY, "Bea")
        w.log += named
        val id = named.eventId(w.crypto)
        assertEquals(
            listOf(
                "A member cannot confirm their own identity.",
                "Only a member at Level 2 or above, or a steward, can confirm someone's identity.",
                "That member has not chosen a name yet, so there is no identity to confirm.",
            ),
            w.reasons(
                listOf(
                    w.bea.confirmName(w.circle, DAY, bea, id, "Bea"),
                    w.carol.confirmName(w.circle, DAY, bea, id, "Bea"), // carol is a new member
                    w.founder.confirmName(w.circle, DAY, w.carol.identity.member, id, "Bea"),
                ),
            ),
        )
        val bad = listOf("x".repeat(MAX_DISPLAY_NAME_CODE_POINTS + 1), "Bea\u0007", " Bea", "Bea  Lee", "2b69a182", "12345", "Be\u200Ba", "")
        for (name in bad) {
            assertEquals(1, w.reasons(listOf(w.carol.memberProfile(w.circle, DAY, name))).size, "refused: '$name'")
        }
        assertEquals("Bea", w.foldAs(w.founder).market.names.getValue(bea).name)
    }

    @Test
    fun aConfirmationStopsCountingWhenTheConfirmerLeaves() = runTest {
        val w = World(newCrypto(), newCipher())
        w.start(StewardSettings(quorum = 1))
        w.log += w.founder.stewardAdd(w.circle, w.bea.identity.member, DAY)
        val carol = w.carol.identity.member
        val named = w.carol.memberProfile(w.circle, DAY, "Carol")
        w.log += named
        w.log += w.bea.confirmName(w.circle, DAY, carol, named.eventId(w.crypto), "Carol")
        assertTrue(w.foldAs(w.founder).market.nameConfirmed(carol), "a steward may confirm")
        w.log += w.bea.deviceRevoke(w.circle, w.bea.identity.device, DAY + 1) // bea leaves the circle
        val after = w.foldAs(w.founder)
        assertEquals(emptyList(), after.rejected)
        assertEquals(emptySet(), after.market.nameConfirmers(carol))
        assertFalse(after.market.nameConfirmed(carol))
        w.log += w.founder.confirmName(w.circle, DAY + 1, carol, named.eventId(w.crypto), "Carol")
        assertEquals(setOf(w.founder.identity.member), w.foldAs(w.founder).market.nameConfirmers(carol))
    }

    // ---- unique display names (ADR 0069) ----

    @Test
    fun aNameAnotherCurrentMemberHoldsIsTakenInAnyCaseAndTheFirstClaimInTheLogWins() = runTest {
        val w = World(newCrypto(), newCipher())
        w.start(StewardSettings(quorum = 1))
        val bea = w.bea.identity.member
        val carol = w.carol.identity.member
        w.log += w.bea.memberProfile(w.circle, DAY, "Bea")
        // Same name in another case, with accents folded as written, and the Turkish letters kept apart.
        assertEquals(listOf(NAME_TAKEN_REASON), w.reasons(listOf(w.carol.memberProfile(w.circle, DAY, "BEA"))))
        assertEquals(emptyList(), w.reasons(listOf(w.carol.memberProfile(w.circle, DAY, "B\u00E9a"))), "an accent makes a different name")
        val view = w.foldAs(w.founder)
        assertEquals("Bea", view.market.names.getValue(bea).name)
        assertEquals("Bea", w.foldAs(w.founder).market.names.getValue(bea).name)
        assertEquals(null, w.foldAs(w.founder).market.names[carol], "a refused first claim leaves the member unnamed")
        // Two claims for a free name: the earlier one in the log wins, the later one is refused with a reason.
        val first = w.carol.memberProfile(w.circle, DAY + 1, "Sam")
        val second = w.founder.memberProfile(w.circle, DAY + 1, "sam")
        w.log += first
        assertEquals(listOf(NAME_TAKEN_REASON), w.reasons(listOf(second)))
        w.log += second
        val folded = w.foldAs(w.founder)
        assertEquals("Sam", folded.market.names.getValue(carol).name)
        assertEquals(null, folded.market.names[w.founder.identity.member])
        assertEquals(mapOf(w.founder.identity.member to "sam"), folded.nameRefusals)
    }

    @Test
    fun aMemberMayChangeTheirOwnNameToOneOnlyTheyHoldAndTheOldNameIsFreed() = runTest {
        val w = World(newCrypto(), newCipher())
        w.start(StewardSettings(quorum = 1))
        w.log += w.bea.memberProfile(w.circle, DAY, "bea")
        val recased = w.bea.memberProfile(w.circle, DAY + 1, "Bea")
        assertEquals(emptyList(), w.reasons(listOf(recased)), "a case change of their own name is allowed")
        w.log += recased
        assertEquals("Bea", w.foldAs(w.bea).market.names.getValue(w.bea.identity.member).name)
        w.log += w.bea.memberProfile(w.circle, DAY + 2, "Beatrice")
        assertEquals(emptyList(), w.reasons(listOf(w.carol.memberProfile(w.circle, DAY + 3, "BEA"))), "freed by the rename")
        // A refused change keeps the old name and is reported to its author until a later claim works.
        val refused = w.carol.memberProfile(w.circle, DAY + 3, "beatrice")
        w.log += w.carol.memberProfile(w.circle, DAY + 3, "Carol")
        assertEquals(listOf(NAME_TAKEN_REASON), w.reasons(listOf(refused)))
        w.log += refused
        val folded = w.foldAs(w.founder)
        assertEquals("Carol", folded.market.names.getValue(w.carol.identity.member).name)
        assertEquals(mapOf(w.carol.identity.member to "beatrice"), folded.nameRefusals)
        w.log += w.carol.memberProfile(w.circle, DAY + 4, "Carol Ng")
        assertEquals(emptyMap(), w.foldAs(w.founder).nameRefusals, "a later accepted claim ends the message")
    }

    @Test
    fun aNameIsFreedWhenItsHolderLeaves() = runTest {
        val w = World(newCrypto(), newCipher())
        w.start(StewardSettings(quorum = 1))
        w.log += w.bea.memberProfile(w.circle, DAY, "Bea")
        assertEquals(listOf(NAME_TAKEN_REASON), w.reasons(listOf(w.carol.memberProfile(w.circle, DAY, "bea"))))
        w.log += w.bea.deviceRevoke(w.circle, w.bea.identity.device, DAY + 1)
        assertEquals(emptyList(), w.reasons(listOf(w.carol.memberProfile(w.circle, DAY + 1, "bea"))))
        w.log += w.carol.memberProfile(w.circle, DAY + 1, "bea")
        assertEquals("Bea", w.foldAs(w.founder).market.names.getValue(w.bea.identity.member).name, "the leaver keeps the name on old records")
    }

    // ---- item photos (ADR 0060) ----

    private fun photo(n: Int, alt: String = "Front view") = ItemPhoto(n.toString(16).padStart(64, 'a'), n.toString(16).padStart(64, 'b'), FIRST_EPOCH, alt)

    @Test
    fun anItemCarriesUpToThreePhotosAndALaterUpsertReplacesThem() = runTest {
        val w = world()
        val owner = w.founder.identity.registration
        fun upsert(photos: List<ItemPhoto>, day: Int = DAY) = w.founder.itemUpsert(w.circle, day, w.item, "Extension ladder", ItemClass.ESSENTIAL, w.keyId, w.itemKey, listOf(owner), null, photos)
        w.log += upsert(listOf(photo(1), photo(2)))
        var view = w.foldAs(w.bea)
        assertEquals(emptyList(), view.rejected)
        assertEquals(listOf(photo(1), photo(2)), view.market.items.getValue(w.item).photos)
        w.log += upsert(listOf(photo(3)), DAY + 1)
        assertEquals(listOf(photo(3)), w.foldAs(w.bea).market.items.getValue(w.item).photos, "the newest list replaces the old one")
        w.log += upsert(emptyList(), DAY + 2)
        assertEquals(emptyList(), w.foldAs(w.bea).market.items.getValue(w.item).photos)
        val tooMany = upsert(listOf(photo(1), photo(2), photo(3), photo(4)), DAY + 3)
        assertTrue(w.reasons(listOf(tooMany)).single().startsWith("photos must list at most 3"))
        for (bad in listOf(photo(1, alt = ""), photo(1, alt = "x".repeat(141)), photo(1).copy(photo = "XYZ"), photo(1).copy(thumb = "a".repeat(63)), photo(1).copy(epoch = 0))) {
            assertTrue(w.reasons(listOf(upsert(listOf(bad), DAY + 3))).single().startsWith("photos must list"), "$bad")
        }
    }

    // ---- approving other dates (ADR 0061) ----

    @Test
    fun anOwnerMayApproveOtherDatesAndTheBookingUsesTheNewOnes() = runTest {
        val w = world()
        w.shareItem()
        val (first, firstRequest) = w.request(by = w.bea)
        val (second, secondRequest) = w.request(by = w.carol)
        w.log += firstRequest; w.log += secondRequest
        // Bea asked for FROM..TO; the owner approves two days later.
        w.log += w.founder.loanApprove(w.circle, DAY, first, w.item, TO + 1, TO + 3, w.itemKey, listOf(w.bea.identity.registration))
        val loan = w.foldAs(w.bea).market.loans.getValue(first)
        assertEquals(LoanStatus.APPROVED, loan.status)
        assertEquals(TO + 1 to TO + 3, loan.fromDay to loan.toDay)
        assertEquals(FROM to TO, loan.requestedFromDay to loan.requestedToDay, "what was asked for is kept")
        // Carol's request for FROM..TO is free now (Bea's booking moved), and one for the new days is not.
        assertEquals(emptyList(), w.reasons(listOf(w.founder.loanApprove(w.circle, DAY, second, w.item, FROM, TO, w.itemKey, listOf(w.carol.identity.registration)))))
        assertEquals(
            listOf("The item is already promised to someone for some of those days."),
            w.reasons(listOf(w.founder.loanApprove(w.circle, DAY, second, w.item, TO + 2, TO + 4, w.itemKey, listOf(w.carol.identity.registration)))),
        )
        // An approval with the same dates is an unchanged loan.
        w.log += w.founder.loanApprove(w.circle, DAY, second, w.item, FROM, TO, w.itemKey, listOf(w.carol.identity.registration))
        val same = w.foldAs(w.carol).market.loans.getValue(second)
        assertEquals(FROM to TO, same.requestedFromDay to same.requestedToDay)
        assertEquals(same.fromDay, same.requestedFromDay)
    }

    // ---- a steward needs a confirmed identity (ADR 0079) ----

    private fun World.strict(who: EventAuthor, extra: List<Envelope> = emptyList()): CircleView =
        CircleFold(crypto, cipher, who.identity, requireConfirmedStewards = true).fold(circle, log + extra)

    /** Bea sets a name and the founder confirms it, so Bea's identity counts as confirmed. */
    private fun World.confirmBea() {
        val profile = bea.memberProfile(circle, DAY, "Bea Jones")
        log += profile
        log += founder.confirmName(circle, DAY, bea.identity.member, profile.eventId(crypto), "Bea Jones")
    }

    @Test
    fun aMemberWhoseIdentityIsNotConfirmedCannotBecomeASteward() = runTest {
        val w = world()
        w.log += w.bea.memberProfile(w.circle, DAY, "Bea Jones")
        val view = w.strict(w.founder, listOf(w.founder.stewardAdd(w.circle, w.bea.identity.member, DAY + 1)))
        assertEquals(setOf(w.founder.identity.member), view.stewards)
        assertEquals(listOf(IDENTITY_NOT_CONFIRMED_REASON), view.rejected.map { it.reason })
        // the same log folded without the rule (a plain governance fold) lets it through: the rule is the app's, not the wire's
        assertEquals(setOf(w.founder.identity.member, w.bea.identity.member), w.foldAs(w.founder, w.log + w.founder.stewardAdd(w.circle, w.bea.identity.member, DAY + 1)).stewards)
    }

    @Test
    fun aMemberWithAConfirmedIdentityCanBecomeASteward() = runTest {
        val w = world()
        w.confirmBea()
        val view = w.strict(w.founder, listOf(w.founder.stewardAdd(w.circle, w.bea.identity.member, DAY + 1)))
        assertEquals(setOf(w.founder.identity.member, w.bea.identity.member), view.stewards)
        assertEquals(emptyList(), view.rejected)
    }

    @Test
    fun aConfirmationThatComesAfterTheProposalDoesNotMakeAnEarlierProposalValid() = runTest {
        val w = world()
        val profile = w.bea.memberProfile(w.circle, DAY, "Bea Jones")
        w.log += profile
        w.log += w.founder.stewardAdd(w.circle, w.bea.identity.member, DAY + 1)
        w.log += w.founder.confirmName(w.circle, DAY + 2, w.bea.identity.member, profile.eventId(w.crypto), "Bea Jones")
        val view = w.strict(w.founder)
        assertEquals(setOf(w.founder.identity.member), view.stewards, "the early proposal stays refused")
        assertEquals(listOf(IDENTITY_NOT_CONFIRMED_REASON), view.rejected.map { it.reason })
        assertEquals(setOf(w.founder.identity.member, w.bea.identity.member), w.strict(w.founder, listOf(w.founder.stewardAdd(w.circle, w.bea.identity.member, DAY + 3))).stewards.also { }, "asking again after the confirmation works")
    }

    @Test
    fun theFounderIsExemptAndASittingStewardKeepsTheRoleWhenTheConfirmerLeaves() = runTest {
        val w = world()
        w.confirmBea()
        w.log += w.founder.stewardAdd(w.circle, w.bea.identity.member, DAY + 1)
        // the founder steps down and leaves: Bea, confirmed by the founder, stays a steward
        w.log += w.founder.stewardRemove(w.circle, w.founder.identity.member, DAY + 20)
        val view = w.strict(w.bea)
        assertEquals(setOf(w.bea.identity.member), view.stewards)
        // later Bea brings the founder back, who has no name and no confirmation, and it works: the founder is exempt
        val back = w.bea.stewardAdd(w.circle, w.founder.identity.member, DAY + 30)
        assertEquals(setOf(w.bea.identity.member, w.founder.identity.member), w.strict(w.bea, listOf(back)).stewards)
        assertEquals(emptyList(), w.strict(w.bea, listOf(back)).rejected)
    }

    @Test
    fun theInactivityFallbackDoesNotLetAnUnconfirmedMemberIn() = runTest {
        val w = world()
        w.confirmBea()
        w.log += w.founder.stewardAdd(w.circle, w.bea.identity.member, DAY + 1)
        // carol has no confirmed identity; bea proposes her, the founder stays silent, and bea repeats the proposal long after
        val first = w.bea.stewardAdd(w.circle, w.carol.identity.member, DAY + 10)
        val repeat = w.bea.stewardAdd(w.circle, w.carol.identity.member, DAY + 100)
        val view = w.strict(w.bea, listOf(first, repeat))
        assertEquals(setOf(w.founder.identity.member, w.bea.identity.member), view.stewards)
        assertEquals(listOf(IDENTITY_NOT_CONFIRMED_REASON, IDENTITY_NOT_CONFIRMED_REASON), view.rejected.map { it.reason })
    }
}
