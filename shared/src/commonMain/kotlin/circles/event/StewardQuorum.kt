package circles.event

import circles.policy.MemberId

/** Smallest quorum allowed (ADR 0023): a quorum of one would let a single steward act alone. */
const val MIN_STEWARD_QUORUM = 2
const val DEFAULT_STEWARD_PROPOSAL_DAYS = 14
const val DEFAULT_STEWARD_COOL_OFF_DAYS = 7

/** The `addedDay` of a steward present at `circle.create`: far enough in the past that cooling-off never applies. */
const val FOUNDING_STEWARD_DAY = -1_000_000

/** The numbers behind ADR 0023. Both the server and the client fold take one, so they cannot drift apart. */
data class StewardPolicy(
    val quorum: Int = MIN_STEWARD_QUORUM,
    val proposalDays: Int = DEFAULT_STEWARD_PROPOSAL_DAYS,
    val coolOffDays: Int = DEFAULT_STEWARD_COOL_OFF_DAYS,
) {
    init {
        require(quorum >= MIN_STEWARD_QUORUM) { "the steward quorum cannot be below $MIN_STEWARD_QUORUM" }
        require(proposalDays >= 0 && coolOffDays >= 0) { "days must not be negative" }
    }
}

enum class StewardChange { ADD, REMOVE }

/** One steward's `steward.add` or `steward.remove` for a target, on its signed `day`. */
data class StewardVote(val voter: MemberId, val day: Int)

/** What one steward event does (ADR 0023). */
sealed interface StewardOutcome {
    /** Enough distinct stewards agree: apply the change now. */
    data object Effective : StewardOutcome

    /** Valid, recorded, and waiting for more stewards. */
    data object Pending : StewardOutcome

    data class Refused(val reason: String) : StewardOutcome
}

/** The quorum rules of ADR 0023 as one pure function, shared by the server and every client. */
object StewardRules {
    /**
     * Judges [voter]'s [change] for [target] on [day].
     * [stewards] maps each current steward to the day they became one ([FOUNDING_STEWARD_DAY] for founders);
     * [priorVotes] are earlier events for the same change and target. Votes are counted by distinct member.
     */
    fun decide(
        policy: StewardPolicy,
        change: StewardChange,
        target: MemberId,
        voter: MemberId,
        day: Int,
        stewards: Map<MemberId, Int>,
        priorVotes: List<StewardVote>,
    ): StewardOutcome {
        val voterSince = stewards[voter] ?: return StewardOutcome.Refused("only a steward may change stewards")
        // Leaving is a right, not a vote, so the cool-off never delays it.
        val leaving = change == StewardChange.REMOVE && voter == target
        if (!leaving && day < voterSince + policy.coolOffDays) {
            return StewardOutcome.Refused("a new steward cannot vote until day ${voterSince + policy.coolOffDays}")
        }
        val required = when (change) {
            StewardChange.ADD -> {
                if (target in stewards) return StewardOutcome.Refused("member is already a steward")
                minOf(policy.quorum, stewards.size)
            }
            StewardChange.REMOVE -> {
                if (target !in stewards) return StewardOutcome.Refused("member is not a steward")
                if (stewards.size == 1) return StewardOutcome.Refused("a circle keeps at least one steward")
                if (voter == target) return StewardOutcome.Effective // a steward may always leave while another remains
                if (stewards.size - 1 < policy.quorum) {
                    return StewardOutcome.Refused("too few other stewards to remove one; the steward must leave")
                }
                policy.quorum
            }
        }
        val counted = (priorVotes + StewardVote(voter, day)).filter { it.voter in stewards && it.voter != target }
        return if (quorumWithinWindow(counted, required, policy.proposalDays)) StewardOutcome.Effective else StewardOutcome.Pending
    }

    /** True if [required] distinct voters have votes no more than [windowDays] apart. */
    private fun quorumWithinWindow(votes: List<StewardVote>, required: Int, windowDays: Int): Boolean {
        val sorted = votes.sortedBy { it.day }
        return sorted.any { start ->
            sorted.filter { it.day >= start.day && it.day - start.day <= windowDays }.map { it.voter }.toSet().size >= required
        }
    }
}
