package circles.app

import circles.event.DisputeClaim
import circles.event.LoanId
import circles.event.LoanStatus
import circles.event.ReturnOutcome
import circles.policy.ItemClass
import circles.policy.ItemId
import circles.policy.MemberId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LoanHistoryTest {
    private val ladder = ItemId("1".repeat(32))
    private val drill = ItemId("2".repeat(32))
    private val me = MemberId("a".repeat(32))
    private val bea = MemberId("b".repeat(32))
    private val cat = MemberId("c".repeat(32))

    private var counter = 0
    private fun loan(
        item: ItemId, role: LoanRole, status: LoanStatus, from: Int, to: Int, other: MemberId = bea,
        outcome: ReturnOutcome? = null, returnedDay: Int? = null, dispute: DisputeSummary? = null,
    ): LoanSummary {
        counter += 1
        return LoanSummary(
            loan = LoanId(counter.toString().padStart(32, '0')), item = item, itemName = if (item == ladder) "Ladder" else "Drill",
            role = role, other = other, fromDay = from, toDay = to, status = status, iConfirmedHandover = true,
            otherConfirmedHandover = true, outcome = outcome, description = "PRIVATE", dispute = dispute, returnedDay = returnedDay,
        )
    }

    private fun item(id: ItemId, owner: MemberId = bea, access: AccessSummary? = AccessSummary(true, emptyList(), emptyList())) =
        ItemSummary(id, if (id == ladder) "Ladder" else "Drill", ItemClass.EVERYDAY, owner, owner == me, null, access = access)

    @Test fun ownerHistoryListsEveryLoanOfThatItemNewestFirstAndNothingElse() {
        val old = loan(ladder, LoanRole.OWNER, LoanStatus.RETURNED, 100, 102, outcome = ReturnOutcome.OK, returnedDay = 102)
        val newer = loan(ladder, LoanRole.OWNER, LoanStatus.DECLINED, 200, 202, other = cat)
        val otherItem = loan(drill, LoanRole.OWNER, LoanStatus.RETURNED, 300, 301)
        val asBorrower = loan(ladder, LoanRole.BORROWER, LoanStatus.RETURNED, 400, 401)
        val asSteward = loan(ladder, LoanRole.STEWARD, LoanStatus.RETURNED, 500, 501)
        val entries = LoanHistory.forOwnedItem(listOf(old, otherItem, asBorrower, asSteward, newer), ladder)
        assertEquals(listOf(newer, old), entries.map { it.loan })
        assertEquals(listOf(HistoryOutcome.DECLINED, HistoryOutcome.RETURNED_ON_TIME), entries.map { it.outcome })
    }

    @Test fun anItemWithNoLoansHasAnEmptyHistory() {
        assertTrue(LoanHistory.forOwnedItem(emptyList(), ladder).isEmpty())
    }

    @Test fun outcomesAreNamedFromTheStatusTheReportAndTheReturnDay() {
        fun outcome(status: LoanStatus, reported: ReturnOutcome? = null, returned: Int? = null) =
            LoanHistory.outcomeOf(loan(ladder, LoanRole.OWNER, status, 10, 12, outcome = reported, returnedDay = returned))
        assertEquals(HistoryOutcome.ASKED, outcome(LoanStatus.REQUESTED))
        assertEquals(HistoryOutcome.APPROVED, outcome(LoanStatus.APPROVED))
        assertEquals(HistoryOutcome.OUT, outcome(LoanStatus.ACTIVE))
        assertEquals(HistoryOutcome.CANCELLED, outcome(LoanStatus.CANCELLED))
        assertEquals(HistoryOutcome.RETURNED_ON_TIME, outcome(LoanStatus.RETURNED, ReturnOutcome.OK, 12))
        assertEquals(HistoryOutcome.RETURNED_ON_TIME, outcome(LoanStatus.RETURNED, ReturnOutcome.OK, 11))
        assertEquals(HistoryOutcome.RETURNED_LATE, outcome(LoanStatus.RETURNED, ReturnOutcome.OK, 13))
        assertEquals(HistoryOutcome.RETURNED_LATE, outcome(LoanStatus.RETURNED, ReturnOutcome.LATE, 12))
        assertEquals(HistoryOutcome.DAMAGE_REPORTED, outcome(LoanStatus.RETURNED, ReturnOutcome.DAMAGED_REPORTED, 13))
        assertEquals(HistoryOutcome.LOSS_REPORTED, outcome(LoanStatus.RETURNED, ReturnOutcome.LOST_REPORTED, 13))
    }

    @Test fun aDisputeAndHowItEndedTravelWithTheEntry() {
        for ((status, expected) in listOf(
            DisputeStatus.PENDING to HistoryDispute.PENDING, DisputeStatus.UPHELD to HistoryDispute.UPHELD, DisputeStatus.DISMISSED to HistoryDispute.DISMISSED,
        )) {
            val entry = LoanHistory.entryOf(loan(ladder, LoanRole.OWNER, LoanStatus.RETURNED, 1, 2, outcome = ReturnOutcome.OK, returnedDay = 2, dispute = DisputeSummary(DisputeClaim.DAMAGE, null, 3, status)))
            assertEquals(expected, entry.dispute)
            assertEquals(DisputeClaim.DAMAGE, entry.claim)
        }
        assertEquals(null, LoanHistory.entryOf(loan(ladder, LoanRole.OWNER, LoanStatus.RETURNED, 1, 2)).dispute)
    }

    @Test fun borrowerHistoryGroupsEndedLoansByItemMostRecentFirst() {
        val a = loan(ladder, LoanRole.BORROWER, LoanStatus.RETURNED, 100, 102, outcome = ReturnOutcome.OK, returnedDay = 102)
        val b = loan(drill, LoanRole.BORROWER, LoanStatus.RETURNED, 300, 302, other = cat, outcome = ReturnOutcome.OK, returnedDay = 302)
        val c = loan(ladder, LoanRole.BORROWER, LoanStatus.CANCELLED, 200, 202)
        val stillOut = loan(drill, LoanRole.BORROWER, LoanStatus.ACTIVE, 400, 402)
        val asOwner = loan(drill, LoanRole.OWNER, LoanStatus.RETURNED, 500, 502)
        val history = LoanHistory.borrowedBefore(listOf(a, b, c, stillOut, asOwner), listOf(item(ladder), item(drill, cat)))
        assertEquals(listOf(drill, ladder), history.map { it.item })
        assertEquals(listOf(c, a), history[1].entries.map { it.loan })
        assertEquals(cat, history[0].owner)
    }

    @Test fun borrowAgainIsOfferedOnlyWhenTheItemIsListedAndTheRulesAllowIt() {
        val ladderLoan = loan(ladder, LoanRole.BORROWER, LoanStatus.RETURNED, 1, 2)
        val drillLoan = loan(drill, LoanRole.BORROWER, LoanStatus.RETURNED, 3, 4)
        val gone = loan(ItemId("3".repeat(32)), LoanRole.BORROWER, LoanStatus.DECLINED, 5, 6)
        val blocked = AccessSummary(false, listOf("Not at your level yet"), listOf("Ask for a vouch"))
        val history = LoanHistory.borrowedBefore(listOf(ladderLoan, drillLoan, gone), listOf(item(ladder), item(drill, access = blocked)))
        val byItem = history.associateBy { it.item }
        assertEquals(BorrowAgain.CanAsk, byItem.getValue(ladder).again)
        assertEquals(BorrowAgain.NotYet(listOf("Not at your level yet"), listOf("Ask for a vouch")), byItem.getValue(drill).again)
        assertEquals(BorrowAgain.NotListed, byItem.getValue(gone.item).again)
    }

    @Test fun anItemWithNoAccessInformationCanBeAskedFor() {
        val history = LoanHistory.borrowedBefore(listOf(loan(ladder, LoanRole.BORROWER, LoanStatus.RETURNED, 1, 2)), listOf(item(ladder, access = null)))
        assertIs<BorrowAgain.CanAsk>(history.single().again)
    }

    @Test fun removedItemsAreThoseIOwnedThatAreNoLongerListedWithTheirLoansNewestFirst() {
        val listed = listOf(item(drill, owner = me))
        val gone1 = loan(ladder, LoanRole.OWNER, LoanStatus.RETURNED, 100, 102, outcome = ReturnOutcome.OK, returnedDay = 102)
        val gone2 = loan(ladder, LoanRole.OWNER, LoanStatus.DECLINED, 200, 202, other = cat)
        val stillListed = loan(drill, LoanRole.OWNER, LoanStatus.RETURNED, 300, 301, outcome = ReturnOutcome.OK, returnedDay = 301)
        val borrowed = loan(ladder, LoanRole.BORROWER, LoanStatus.RETURNED, 50, 51, outcome = ReturnOutcome.OK, returnedDay = 51)
        val removed = LoanHistory.removedOwnedItems(listOf(gone1, stillListed, borrowed, gone2), listed)
        assertEquals(1, removed.size)
        assertEquals(ladder, removed.single().item)
        assertEquals("Ladder", removed.single().name)
        assertEquals(listOf(gone2.loan, gone1.loan), removed.single().entries.map { it.loan.loan })
        assertEquals(removed.single(), LoanHistory.removedOwnedItem(listOf(gone1, gone2), listed, ladder))
        assertEquals(null, LoanHistory.removedOwnedItem(listOf(gone1), listed, drill))
    }

    @Test fun aRemovedItemWhoseNameThisDeviceCannotReadHasAnEmptyName() {
        val unreadable = loan(ladder, LoanRole.OWNER, LoanStatus.RETURNED, 100, 102, outcome = ReturnOutcome.OK, returnedDay = 102).copy(itemName = "")
        assertEquals("", LoanHistory.removedOwnedItems(listOf(unreadable), emptyList()).single().name)
    }

    @Test fun removedItemsAreEmptyWhenNothingWasRemovedAndNeverIncludeOthersItems() {
        assertTrue(LoanHistory.removedOwnedItems(emptyList(), emptyList()).isEmpty())
        val theirs = loan(ladder, LoanRole.STEWARD, LoanStatus.RETURNED, 1, 2)
        assertTrue(LoanHistory.removedOwnedItems(listOf(theirs), emptyList()).isEmpty())
    }
}
