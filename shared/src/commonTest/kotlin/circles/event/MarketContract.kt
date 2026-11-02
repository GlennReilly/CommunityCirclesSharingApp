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
        fun start() {
            log += founder.circleCreate(circle, DAY)
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
                "only the owner may change an item",
                "only the owner may retire an item",
                "only the owner may attach detail",
                "detail is sealed under a key that is not the item's current one",
                "keyWraps must go to the owner's own devices",
                "unknown item",
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
                "a member cannot borrow their own item",
                "unknown item",
                "loan id already used",
                "only the item's owner may respond",
                "keyWraps must go to the borrower's devices",
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
            listOf("hand-over needs an approved loan that is not yet active", "only an active loan can be returned"),
            w.reasons(early),
        )
        w.log += w.approve(loan)
        val later = listOf(
            w.carol.loanHandover(w.circle, DAY, loan),
            w.founder.loanDecline(w.circle, DAY, loan, FROM, TO),
            w.founder.loanApprove(w.circle, DAY, loan, w.item, FROM, TO + 1, w.itemKey, listOf(w.bea.identity.registration)),
        )
        assertEquals(
            listOf("only the owner or the borrower may confirm a hand-over", "the loan is already answered", "the loan is already answered"),
            w.reasons(later),
        )
        w.log += listOf(w.founder.loanHandover(w.circle, DAY, loan), w.bea.loanHandover(w.circle, DAY, loan))
        assertEquals(
            listOf("only the borrower reports a return", "hand-over needs an approved loan that is not yet active"),
            w.reasons(
                listOf(
                    w.founder.loanReturn(w.circle, DAY, loan, ReturnOutcome.OK),
                    w.bea.loanHandover(w.circle, DAY, loan), // the loan is already active
                ),
            ),
        )
        w.log += w.bea.loanReturn(w.circle, DAY, loan, ReturnOutcome.LATE)
        assertEquals(listOf("only an active loan can be returned"), w.reasons(listOf(w.bea.loanReturn(w.circle, DAY, loan, ReturnOutcome.OK))))
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
        assertEquals(listOf("the loan is already answered"), w.reasons(listOf(w.approve(loan)), w.bea))
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
        assertEquals(listOf("item is retired"), w.reasons(listOf(late)))
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
        assertEquals(listOf("member may not borrow this item yet"), view.rejected.map { it.reason })
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
}
