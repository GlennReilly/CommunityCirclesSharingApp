package circles.app

import circles.event.DisputeClaim
import circles.event.LoanStatus
import circles.event.ReturnOutcome
import circles.policy.ItemId
import circles.policy.MemberId

/**
 * How one loan ended, or where it stands, in the words the history pages use (ADR 0072).
 * Reporting damage or loss is a plain fact here, never a penalty (spec section 6).
 */
enum class HistoryOutcome {
    ASKED, APPROVED, OUT, RETURNED_ON_TIME, RETURNED_LATE, DAMAGE_REPORTED, LOSS_REPORTED, DECLINED, CANCELLED,
}

/** How a dispute over a return ended, if there was one; null when there was none. */
enum class HistoryDispute { PENDING, UPHELD, DISMISSED }

/** One loan in a history list: the loan plus its outcome. The private item details are never part of it. */
data class HistoryEntry(
    val loan: LoanSummary,
    val outcome: HistoryOutcome,
    val dispute: HistoryDispute?,
    /** The disputed claim (damage or loss), when [dispute] is set. */
    val claim: DisputeClaim?,
)

/** Whether a borrower may borrow an item again from the history page. */
sealed interface BorrowAgain {
    /** The request form can be opened. */
    data object CanAsk : BorrowAgain

    /** The circle's access rules say not yet; [reasons] and [howToUnlock] are for the member to read. */
    data class NotYet(val reasons: List<String>, val howToUnlock: List<String>) : BorrowAgain

    /** The item was removed, or its owner left the circle: nothing to ask for. */
    data object NotListed : BorrowAgain
}

/** An item the member has borrowed before, with their loans of it and what they may do next. */
data class BorrowedItem(
    val item: ItemId,
    val itemName: String,
    val owner: MemberId,
    /** Most recent first. */
    val entries: List<HistoryEntry>,
    val again: BorrowAgain,
)

/**
 * The loan history pages (ADR 0072), computed from what `HomeData` already holds. A member sees only loans they were
 * a party to: as the owner of the item, or as its borrower. Stewards get no extra view.
 */
object LoanHistory {
    private val ENDED = setOf(LoanStatus.RETURNED, LoanStatus.DECLINED, LoanStatus.CANCELLED)

    fun outcomeOf(loan: LoanSummary): HistoryOutcome = when (loan.status) {
        LoanStatus.REQUESTED -> HistoryOutcome.ASKED
        LoanStatus.APPROVED -> HistoryOutcome.APPROVED
        LoanStatus.ACTIVE -> HistoryOutcome.OUT
        LoanStatus.DECLINED -> HistoryOutcome.DECLINED
        LoanStatus.CANCELLED -> HistoryOutcome.CANCELLED
        LoanStatus.RETURNED -> when {
            loan.outcome == ReturnOutcome.DAMAGED_REPORTED -> HistoryOutcome.DAMAGE_REPORTED
            loan.outcome == ReturnOutcome.LOST_REPORTED -> HistoryOutcome.LOSS_REPORTED
            loan.outcome == ReturnOutcome.LATE -> HistoryOutcome.RETURNED_LATE
            loan.returnedDay != null && loan.returnedDay > loan.toDay -> HistoryOutcome.RETURNED_LATE
            else -> HistoryOutcome.RETURNED_ON_TIME
        }
    }

    fun entryOf(loan: LoanSummary): HistoryEntry = HistoryEntry(
        loan = loan,
        outcome = outcomeOf(loan),
        dispute = loan.dispute?.let {
            when (it.status) {
                DisputeStatus.PENDING -> HistoryDispute.PENDING
                DisputeStatus.UPHELD -> HistoryDispute.UPHELD
                DisputeStatus.DISMISSED -> HistoryDispute.DISMISSED
            }
        },
        claim = loan.dispute?.claim,
    )

    private fun newestFirst(loans: List<LoanSummary>): List<LoanSummary> =
        loans.sortedWith(compareByDescending<LoanSummary> { it.fromDay }.thenByDescending { it.toDay }.thenBy { it.loan.v })

    /** Every loan of [item] that the member made as its owner, most recent first. Empty for anyone else's item. */
    fun forOwnedItem(loans: List<LoanSummary>, item: ItemId): List<HistoryEntry> =
        newestFirst(loans.filter { it.item == item && it.role == LoanRole.OWNER }).map(::entryOf)

    /**
     * The items the member borrowed before (a loan that ended: returned, declined or cancelled), grouped by item, the
     * item with the most recent loan first. [items] is `HomeData.items`, which leaves out removed items and items of
     * members who left, so an item missing from it cannot be asked for.
     */
    fun borrowedBefore(loans: List<LoanSummary>, items: List<ItemSummary>): List<BorrowedItem> {
        val past = loans.filter { it.role == LoanRole.BORROWER && it.status in ENDED }
        val ordered = newestFirst(past)
        return ordered.map { it.item }.distinct().map { item ->
            val mine = ordered.filter { it.item == item }
            val listed = items.firstOrNull { it.item == item }
            BorrowedItem(
                item = item,
                itemName = listed?.name ?: mine.first().itemName,
                owner = mine.first().other,
                entries = mine.map(::entryOf),
                again = when {
                    listed == null || listed.ownedByMe -> BorrowAgain.NotListed
                    listed.access == null || listed.access.canAsk -> BorrowAgain.CanAsk
                    else -> BorrowAgain.NotYet(listed.access.reasons, listed.access.howToUnlock)
                },
            )
        }
    }
}
