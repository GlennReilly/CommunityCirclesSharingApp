package circles.event

import circles.policy.MemberId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The quorum rules of ADR 0023, as a pure function. */
class StewardRulesTest {
    private val policy = StewardPolicy()
    private val a = MemberId("a".repeat(32))
    private val b = MemberId("b".repeat(32))
    private val c = MemberId("c".repeat(32))
    private val d = MemberId("d".repeat(32))
    private val outsider = MemberId("e".repeat(32))
    private val founding = FOUNDING_STEWARD_DAY

    private fun add(target: MemberId, voter: MemberId, day: Int, stewards: Map<MemberId, Int>, prior: List<StewardVote> = emptyList()) =
        StewardRules.decide(policy, StewardChange.ADD, target, voter, day, stewards, prior)

    private fun remove(target: MemberId, voter: MemberId, day: Int, stewards: Map<MemberId, Int>, prior: List<StewardVote> = emptyList()) =
        StewardRules.decide(policy, StewardChange.REMOVE, target, voter, day, stewards, prior)

    private fun refused(outcome: StewardOutcome): String = assertIs<StewardOutcome.Refused>(outcome).reason

    @Test
    fun theQuorumCannotBeBelowTwo() {
        assertFailsWith<IllegalArgumentException> { StewardPolicy(quorum = 1) }
        assertFailsWith<IllegalArgumentException> { StewardPolicy(proposalDays = -1) }
    }

    @Test
    fun aLoneStewardAddsAlone() {
        assertEquals(StewardOutcome.Effective, add(b, a, 100, mapOf(a to founding)))
    }

    @Test
    fun withTwoStewardsOneVoteIsPendingAndTwoDistinctVotesAreEffective() {
        val stewards = mapOf(a to founding, b to founding)
        assertEquals(StewardOutcome.Pending, add(c, a, 100, stewards))
        assertEquals(StewardOutcome.Effective, add(c, b, 101, stewards, listOf(StewardVote(a, 100))))
    }

    @Test
    fun theSameStewardVotingTwiceNeverMakesAQuorum() {
        val stewards = mapOf(a to founding, b to founding)
        assertEquals(StewardOutcome.Pending, add(c, a, 105, stewards, listOf(StewardVote(a, 100), StewardVote(a, 101))))
    }

    @Test
    fun votesFromStewardsWhoHaveLeftDoNotCount() {
        val stewards = mapOf(a to founding, b to founding)
        assertEquals(StewardOutcome.Pending, add(c, a, 100, stewards, listOf(StewardVote(outsider, 99))))
    }

    @Test
    fun votesMustFallWithinTheProposalWindow() {
        val stewards = mapOf(a to founding, b to founding)
        val old = listOf(StewardVote(a, 100))
        assertEquals(StewardOutcome.Effective, add(c, b, 100 + policy.proposalDays, stewards, old))
        assertEquals(StewardOutcome.Pending, add(c, b, 101 + policy.proposalDays, stewards, old))
    }

    @Test
    fun aNewStewardCannotVoteUntilTheCoolOffHasPassed() {
        val stewards = mapOf(a to founding, b to 100)
        assertTrue(refused(add(c, b, 100 + policy.coolOffDays - 1, stewards, listOf(StewardVote(a, 100)))).contains("cannot vote until day"))
        assertEquals(StewardOutcome.Effective, add(c, b, 100 + policy.coolOffDays, stewards, listOf(StewardVote(a, 100))))
    }

    @Test
    fun aNewStewardMayLeaveAtOnceBecauseLeavingIsNotAVote() {
        val stewards = mapOf(a to founding, b to 100)
        assertEquals(StewardOutcome.Effective, remove(b, b, 100, stewards))
    }

    @Test
    fun onlyStewardsVoteAndTargetsMustQualify() {
        val stewards = mapOf(a to founding, b to founding)
        assertEquals("only a steward may change stewards", refused(add(c, outsider, 100, stewards)))
        assertEquals("member is already a steward", refused(add(b, a, 100, stewards)))
        assertEquals("member is not a steward", refused(remove(c, a, 100, stewards)))
    }

    @Test
    fun aStewardMayAlwaysLeaveWhileAnotherRemainsAndTheLastCannot() {
        assertEquals(StewardOutcome.Effective, remove(a, a, 100, mapOf(a to founding, b to founding)))
        assertEquals("a circle keeps at least one steward", refused(remove(a, a, 100, mapOf(a to founding))))
    }

    @Test
    fun aTwoStewardCircleCannotRemoveEitherOfThem() {
        val stewards = mapOf(a to founding, b to founding)
        assertTrue(refused(remove(b, a, 100, stewards)).contains("too few other stewards"))
    }

    @Test
    fun removingAnotherStewardNeedsTwoOthersAndTheTargetsOwnVoteDoesNotCount() {
        val stewards = mapOf(a to founding, b to founding, c to founding)
        assertEquals(StewardOutcome.Pending, remove(c, a, 100, stewards))
        assertEquals(StewardOutcome.Effective, remove(c, b, 101, stewards, listOf(StewardVote(a, 100))))
        // c's own earlier vote (for example a remove it sent itself while pending) is ignored when counting.
        assertEquals(StewardOutcome.Pending, remove(c, a, 100, stewards, listOf(StewardVote(c, 99))))
    }

    @Test
    fun aFourthStewardChangesNothingAboutTheQuorumOfTwo() {
        val stewards = mapOf(a to founding, b to founding, c to founding, d to founding)
        assertEquals(StewardOutcome.Pending, remove(d, a, 100, stewards))
        assertEquals(StewardOutcome.Effective, remove(d, c, 100, stewards, listOf(StewardVote(a, 100))))
    }
}
