package circles.event

import circles.policy.AccessDecision
import circles.policy.AccessPolicy
import circles.policy.CircleConfig
import circles.policy.CircleId
import circles.policy.EventKind
import circles.policy.Item
import circles.policy.MemberId
import circles.policy.Standing
import circles.policy.StandingCalculator
import circles.policy.StandingEvent
import circles.policy.Tier
import circles.policy.Vouch

/** Days after a return before it counts, when nobody disputed it (spec 3.3, ADR 0042). */
const val RETURN_GRACE_DAYS = 7

/** An owner who confirms a return within this many days earns the prompt-confirmation credit (spec 3.3). */
const val PROMPT_CONFIRM_DAYS = 3

/** How long after a return an owner may dispute it, and how long a dispute waits for a steward (ADR 0043). */
const val DAMAGE_CLAIM_DAYS = 30

/** Stewards count as at least this tier for every decision (ADR 0042, A1). */
val STEWARD_TIER_FLOOR: Tier = Tier.TRUSTED

/**
 * How the policy library is fed from the log (ADR 0042). Every function takes the state it should judge from and the day
 * to judge on, so two clients with the same log give the same answer whatever day they run on. Pure; no I/O.
 */
object StandingRules {
    /**
     * The standing events that [loans] have earned by [day] (ADR 0042, 0043). A returned loan counts from the owner's
     * confirmation, or once [RETURN_GRACE_DAYS] have passed since the return with no dispute. Reported damage or loss
     * earns nothing. An "OK" report after the agreed end day counts as late. A dispute withholds both parties' credit
     * from its own day; if a non-party steward upholds it the borrower gets the unreported-incident entry (and their
     * vouchers a mild nudge), if it is dismissed, or nobody resolves it within [DAMAGE_CLAIM_DAYS], the return counts as
     * the borrower reported it.
     */
    fun eventsFrom(loans: Collection<LoanState>, circle: CircleId, day: Int, vouches: List<Vouch> = emptyList(), cfg: CircleConfig = defaultConfig(circle)): List<StandingEvent> = buildList {
        for (loan in loans) {
            val returned = loan.returnedDay ?: continue
            val outcome = loan.outcome ?: continue
            if (loan.status != LoanStatus.RETURNED) continue
            if (outcome != ReturnOutcome.OK && outcome != ReturnOutcome.LATE) continue // reported damage or loss earns nothing
            val dispute = loan.dispute?.takeIf { it.day <= day }
            // When, if ever, the return starts to count as the borrower reported it.
            val countsFrom: Int? = when {
                dispute == null -> loan.confirmedDay ?: (returned + RETURN_GRACE_DAYS)
                dispute.outcome == DisputeOutcome.DISMISSED -> maxOf(loan.confirmedDay ?: (returned + RETURN_GRACE_DAYS), dispute.resolvedDay ?: dispute.day)
                dispute.outcome == DisputeOutcome.UPHELD -> null
                else -> dispute.day + DAMAGE_CLAIM_DAYS // unresolved: dismissed by default after the claim window
            }
            if (dispute != null && dispute.outcome == DisputeOutcome.UPHELD) {
                val resolved = dispute.resolvedDay ?: continue
                if (resolved <= day) {
                    val incident = StandingEvent(loan.borrower, circle, EventKind.DAMAGED_OR_LOST_UNREPORTED, resolved, loan.owner, loan.loan.v)
                    add(incident)
                    addAll(StandingCalculator.sponsorNudges(incident, vouches, cfg))
                }
                continue
            }
            val from = countsFrom ?: continue
            if (from > day) continue
            val late = outcome == ReturnOutcome.LATE || (outcome == ReturnOutcome.OK && returned > loan.toDay)
            add(StandingEvent(loan.owner, circle, EventKind.LENT_ITEM_RETURNED_OK, from, loan.borrower, loan.loan.v))
            add(StandingEvent(loan.borrower, circle, if (late) EventKind.RETURNED_LATE else EventKind.BORROWED_AND_RETURNED_OK, from, loan.owner, loan.loan.v))
            val confirmed = loan.confirmedDay
            if (confirmed != null && confirmed <= day && confirmed - returned <= PROMPT_CONFIRM_DAYS && (dispute == null || dispute.outcome == DisputeOutcome.DISMISSED)) {
                add(StandingEvent(loan.owner, circle, EventKind.CONFIRMED_RETURN_PROMPTLY, confirmed, loan.borrower, "${loan.loan.v}:confirm"))
            }
        }
    }

    private fun floored(member: MemberId, tier: Tier, stewards: Set<MemberId>): Tier =
        if (member in stewards && tier.rank < STEWARD_TIER_FLOOR.rank) STEWARD_TIER_FLOOR else tier

    /** [member]'s standing on [day], from the loans and vouches known so far, with the steward floor applied. */
    fun standingOf(
        member: MemberId,
        cfg: CircleConfig,
        loans: Collection<LoanState>,
        vouches: List<Vouch>,
        stewards: Set<MemberId>,
        day: Int,
    ): Standing {
        val events = eventsFrom(loans, cfg.id, day, vouches, cfg)
        // A voucher counts by the tier their points earn (plus the steward floor), never by being vouched for (C12).
        val voucherTier = { who: MemberId ->
            floored(who, StandingCalculator.compute(who, cfg, events, emptyList(), { Tier.NEWCOMER }, day).tier, stewards)
        }
        val own = StandingCalculator.compute(member, cfg, events, vouches, voucherTier, day)
        val tier = floored(member, own.tier, stewards)
        return if (tier == own.tier) own else own.copy(tier = tier, reasons = own.reasons + "Stewards are treated as at least ${tier.name.lowercase()} in this circle.")
    }

    /** May [voucher] vouch on [day]? Their tier must reach `voucherMinTier`. */
    fun mayVouch(voucher: MemberId, cfg: CircleConfig, loans: Collection<LoanState>, vouches: List<Vouch>, stewards: Set<MemberId>, day: Int): Boolean {
        val events = eventsFrom(loans, cfg.id, day)
        val base = floored(voucher, StandingCalculator.compute(voucher, cfg, events, emptyList(), { Tier.NEWCOMER }, day).tier, stewards)
        return base.rank >= cfg.voucherMinTier.rank
    }

    /** May [borrower] ask for [item] on [day]? The library's access decision, fed with the standing above. */
    fun decide(
        borrower: MemberId,
        item: ItemState,
        cfg: CircleConfig,
        loans: Collection<LoanState>,
        vouches: List<Vouch>,
        stewards: Set<MemberId>,
        day: Int,
        pauses: List<circles.policy.Pause> = emptyList(),
    ): AccessDecision {
        val standing = standingOf(borrower, cfg, loans, vouches, stewards, day)
        val policyItem = Item(
            id = item.item, owner = item.owner, name = item.name, itemClass = item.itemClass, ownerMinTier = item.minTier, circles = setOf(cfg.id),
        )
        return AccessPolicy.canBorrow(borrower, policyItem, cfg, standing, pauses, day)
    }

    /** The circle's rules until config events exist (a later slice): the library defaults. */
    fun defaultConfig(circle: CircleId): CircleConfig = CircleConfig(circle, "this circle")
}
