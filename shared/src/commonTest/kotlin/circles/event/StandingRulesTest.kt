package circles.event

import circles.policy.CircleId
import circles.policy.EventKind
import circles.policy.ItemId
import circles.policy.MemberId
import circles.policy.Vouch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val circle = CircleId("c".repeat(32))
private val owner = MemberId("a".repeat(32))
private val borrower = MemberId("b".repeat(32))
private val sponsor = MemberId("d".repeat(32))
private const val RETURNED = 20_100

/** What returned, confirmed and disputed loans earn, as plain functions of the loan states (ADR 0042, 0043). */
class StandingRulesTest {
    private fun loan(
        outcome: ReturnOutcome = ReturnOutcome.OK,
        returned: Int = RETURNED,
        to: Int = RETURNED,
        confirmed: Int? = null,
        dispute: DisputeState? = null,
    ) = LoanState(
        LoanId("1".repeat(32)), ItemId("2".repeat(32)), borrower, owner, to - 2, to, LoanStatus.RETURNED, null, emptyMap(), true, true,
        outcome, returned, null, confirmed, dispute,
    )

    private fun kinds(loan: LoanState, day: Int, vouches: List<Vouch> = emptyList()) =
        StandingRules.eventsFrom(listOf(loan), circle, day, vouches).map { it.member to it.kind }.toSet()

    private val good = setOf(owner to EventKind.LENT_ITEM_RETURNED_OK, borrower to EventKind.BORROWED_AND_RETURNED_OK)

    @Test fun anUndisputedReturnCountsOnlyAfterTheGracePeriod() {
        assertEquals(emptySet(), kinds(loan(), RETURNED + RETURN_GRACE_DAYS - 1))
        assertEquals(good, kinds(loan(), RETURNED + RETURN_GRACE_DAYS))
    }

    @Test fun anOwnerConfirmationCountsItAtOnce() {
        assertEquals(good, kinds(loan(confirmed = RETURNED + 5), RETURNED + 5) - setOf(owner to EventKind.CONFIRMED_RETURN_PROMPTLY))
        assertEquals(emptySet(), kinds(loan(confirmed = RETURNED + 5), RETURNED + 4))
    }

    @Test fun confirmingWithinThreeDaysEarnsTheSmallBonusAndLaterDoesNot() {
        val bonus = owner to EventKind.CONFIRMED_RETURN_PROMPTLY
        assertTrue(bonus in kinds(loan(confirmed = RETURNED + PROMPT_CONFIRM_DAYS), RETURNED + 10))
        assertTrue(bonus !in kinds(loan(confirmed = RETURNED + PROMPT_CONFIRM_DAYS + 1), RETURNED + 10))
        assertTrue(bonus !in kinds(loan(), RETURNED + 10), "not confirming costs nothing and earns nothing")
    }

    @Test fun reportedDamageOrLossEarnsNothingEvenWhenConfirmedOrWaitedOut() {
        for (outcome in listOf(ReturnOutcome.DAMAGED_REPORTED, ReturnOutcome.LOST_REPORTED)) {
            assertEquals(emptySet(), kinds(loan(outcome, confirmed = RETURNED + 1), RETURNED + 100), outcome.name)
        }
    }

    @Test fun lateIsJudgedAgainstTheAgreedWindowOrReportedAsLate() {
        val late = setOf(owner to EventKind.LENT_ITEM_RETURNED_OK, borrower to EventKind.RETURNED_LATE)
        assertEquals(late, kinds(loan(ReturnOutcome.OK, returned = RETURNED, to = RETURNED - 1), RETURNED + 20))
        assertEquals(late, kinds(loan(ReturnOutcome.LATE), RETURNED + 20))
    }

    @Test fun aPendingDisputeWithholdsCreditFromItsOwnDayEvenAfterTheReturnCounted() {
        val disputed = loan(dispute = DisputeState(DisputeClaim.DAMAGE, "cracked", day = RETURNED + 12))
        assertEquals(good, kinds(disputed, RETURNED + 11), "before the dispute existed the credit counted")
        assertEquals(emptySet(), kinds(disputed, RETURNED + 12))
        assertEquals(emptySet(), kinds(disputed, RETURNED + 12 + DAMAGE_CLAIM_DAYS - 1))
    }

    @Test fun anUnresolvedDisputeIsDismissedAfterTheClaimWindow() {
        val disputed = loan(dispute = DisputeState(DisputeClaim.LOSS, null, day = RETURNED + 2))
        assertEquals(good, kinds(disputed, RETURNED + 2 + DAMAGE_CLAIM_DAYS))
    }

    @Test fun aDismissedDisputeCountsTheReturnAsReportedFromTheResolutionDay() {
        val resolved = loan(dispute = DisputeState(DisputeClaim.DAMAGE, null, RETURNED + 1, DisputeOutcome.DISMISSED, resolvedDay = RETURNED + 9))
        assertEquals(emptySet(), kinds(resolved, RETURNED + 8))
        assertEquals(good, kinds(resolved, RETURNED + 9))
    }

    @Test fun anUpheldDisputeGivesTheBorrowerTheIncidentAndTheOwnerNothing() {
        val upheld = loan(dispute = DisputeState(DisputeClaim.DAMAGE, null, RETURNED + 1, DisputeOutcome.UPHELD, resolvedDay = RETURNED + 5))
        assertEquals(emptySet(), kinds(upheld, RETURNED + 4), "still pending")
        assertEquals(setOf(borrower to EventKind.DAMAGED_OR_LOST_UNREPORTED), kinds(upheld, RETURNED + 5))
        assertEquals(setOf(borrower to EventKind.DAMAGED_OR_LOST_UNREPORTED), kinds(upheld, RETURNED + 500), "no lending credit for the owner")
    }

    @Test fun anUpheldDisputeAlsoNudgesLiveVouchersMildly() {
        val upheld = loan(dispute = DisputeState(DisputeClaim.LOSS, null, RETURNED + 1, DisputeOutcome.UPHELD, resolvedDay = RETURNED + 5))
        val live = Vouch(sponsor, borrower, circle, day = RETURNED - 10, expiresDay = RETURNED + 100)
        val lapsed = Vouch(MemberId("e".repeat(32)), borrower, circle, day = RETURNED - 200, expiresDay = RETURNED - 100)
        val got = kinds(upheld, RETURNED + 6, listOf(live, lapsed))
        assertTrue(sponsor to EventKind.RETURNED_LATE in got)
        assertEquals(2, got.size, "only the live vouch is nudged")
    }

    @Test fun aLoanThatWasNotReturnedEarnsNothing() {
        assertEquals(emptySet(), kinds(loan().copy(status = LoanStatus.ACTIVE), RETURNED + 100))
    }
}
