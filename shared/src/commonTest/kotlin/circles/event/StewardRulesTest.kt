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
    fun theSettingsStayWithinTheirLimits() {
        assertFailsWith<IllegalArgumentException> { StewardPolicy(quorum = 0) }
        assertFailsWith<IllegalArgumentException> { StewardPolicy(quorum = MAX_STEWARD_QUORUM + 1) }
        assertFailsWith<IllegalArgumentException> { StewardPolicy(inactiveDays = MIN_STEWARD_INACTIVE_DAYS - 1) }
        assertFailsWith<IllegalArgumentException> { StewardPolicy(inactiveDays = MAX_STEWARD_INACTIVE_DAYS + 1) }
        assertFailsWith<IllegalArgumentException> { StewardPolicy(fallbackDelayDays = MIN_STEWARD_FALLBACK_DELAY_DAYS - 1) }
        assertFailsWith<IllegalArgumentException> { StewardPolicy(fallbackDelayDays = MAX_STEWARD_FALLBACK_DELAY_DAYS + 1) }
        assertFailsWith<IllegalArgumentException> { StewardPolicy(proposalDays = -1) }
        StewardPolicy(quorum = MIN_STEWARD_QUORUM, inactiveDays = MIN_STEWARD_INACTIVE_DAYS, fallbackDelayDays = MIN_STEWARD_FALLBACK_DELAY_DAYS)
        StewardPolicy(quorum = MAX_STEWARD_QUORUM, inactiveDays = MAX_STEWARD_INACTIVE_DAYS, fallbackDelayDays = MAX_STEWARD_FALLBACK_DELAY_DAYS)
        assertEquals(7, MIN_STEWARD_INACTIVE_DAYS, "ADR 0029")
        assertEquals(null, StewardSettings(inactiveDays = 7).problem())
        assertTrue(StewardSettings(inactiveDays = 6).problem()!!.contains("inactiveDays"))
        assertEquals(StewardSettings(2, 60, 14), StewardSettings())
        assertEquals(null, StewardSettings().problem())
        assertTrue(StewardSettings(quorum = 0).problem()!!.contains("quorum"))
        assertTrue(StewardSettings(inactiveDays = 5).problem()!!.contains("inactiveDays"))
        assertTrue(StewardSettings(fallbackDelayDays = 1).problem()!!.contains("fallbackDelayDays"))
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
    fun inATwoStewardCircleARemovalCanOnlyWaitForTheFallback() {
        val stewards = mapOf(a to founding, b to founding)
        assertEquals(StewardOutcome.Pending, remove(b, a, 100, stewards))
        assertEquals(StewardOutcome.Pending, remove(b, a, 200, stewards, listOf(StewardVote(a, 100, 5))), "no votes of the target count, and no fallback without activity facts")
        assertEquals(StewardOutcome.Pending, remove(a, b, 100, stewards))
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

    // ---- ADR 0027: quorum of one, policy changes and the inactivity fallback ----

    private val soloPolicy = StewardPolicy(quorum = 1)

    private fun addSolo(target: MemberId, voter: MemberId, day: Int, stewards: Map<MemberId, Int>) =
        StewardRules.decide(soloPolicy, StewardChange.ADD, target, voter, day, stewards, emptyList())

    private fun removeSolo(target: MemberId, voter: MemberId, day: Int, stewards: Map<MemberId, Int>) =
        StewardRules.decide(soloPolicy, StewardChange.REMOVE, target, voter, day, stewards, emptyList())

    @Test
    fun withAQuorumOfOneAnyStewardActsAloneAtOnceWithNoCoolOff() {
        val stewards = mapOf(a to founding, b to 100, c to 100)
        assertEquals(StewardOutcome.Effective, addSolo(d, b, 100, stewards), "a steward added today acts today")
        assertEquals(StewardOutcome.Effective, removeSolo(c, b, 100, stewards), "and may remove another steward alone")
        assertEquals(StewardOutcome.Effective, removeSolo(a, c, 100, stewards))
        assertEquals("a circle keeps at least one steward", refused(removeSolo(a, a, 100, mapOf(a to founding))))
        assertEquals("only a steward may change stewards", refused(addSolo(d, outsider, 100, stewards)))
    }

    @Test
    fun aPolicyChangeNeedsTheQuorumAndNeverUsesTheFallback() {
        val stewards = mapOf(a to founding, b to founding)
        val proposed = StewardSettings(quorum = 1, inactiveDays = 45, fallbackDelayDays = 10)
        fun decide(voter: MemberId, day: Int, prior: List<StewardVote> = emptyList()) = StewardRules.decidePolicy(policy, proposed, voter, day, stewards, prior)
        assertEquals(StewardOutcome.Pending, decide(a, 100))
        assertEquals(StewardOutcome.Effective, decide(b, 101, listOf(StewardVote(a, 100, 5))))
        assertEquals(StewardOutcome.Pending, decide(a, 500, listOf(StewardVote(a, 100, 5))), "a quiet co-steward does not unlock a lone steward's policy change")
        assertEquals(StewardOutcome.Effective, StewardRules.decidePolicy(policy, proposed, a, 100, mapOf(a to founding), emptyList()), "a lone steward may change it")
    }

    @Test
    fun aPolicyChangeMustBeValidDifferentAndFromASteward() {
        val stewards = mapOf(a to founding, b to founding)
        fun decide(settings: StewardSettings, voter: MemberId = a) = StewardRules.decidePolicy(policy, settings, voter, 100, stewards, emptyList())
        assertTrue(refused(decide(StewardSettings(quorum = 0))).contains("quorum"))
        assertTrue(refused(decide(StewardSettings(inactiveDays = MIN_STEWARD_INACTIVE_DAYS - 1))).contains("inactiveDays"))
        assertTrue(refused(decide(StewardSettings(fallbackDelayDays = MIN_STEWARD_FALLBACK_DELAY_DAYS - 1))).contains("fallbackDelayDays"))
        assertTrue(refused(decide(StewardSettings())).contains("already"))
        assertEquals("only a steward may change stewards", refused(decide(StewardSettings(quorum = 3), outsider)))
    }

    /** Facts the fallback reads: each member's last event day, and who has spoken since the proposal. */
    private class Activity(val lastDay: Map<MemberId, Int> = emptyMap(), val activeSince: Set<MemberId> = emptySet()) : StewardActivity {
        override fun lastEventDayBefore(member: MemberId, position: Long): Int? = lastDay[member]

        override fun hasEventAfter(member: MemberId, position: Long): Boolean = member in activeSince
    }

    private val proposalAt100 = listOf(StewardVote(a, 100, 10))

    private fun fallbackAdd(day: Int, activity: Activity, stewards: Map<MemberId, Int> = mapOf(a to founding, b to founding), prior: List<StewardVote> = proposalAt100) =
        StewardRules.decide(policy, StewardChange.ADD, c, a, day, stewards, prior, activity)

    @Test
    fun theFallbackNeedsTheDelayToHavePassed() {
        val quiet = Activity(lastDay = mapOf(b to 30))
        assertEquals(StewardOutcome.Pending, fallbackAdd(100 + policy.fallbackDelayDays - 1, quiet))
        assertEquals(StewardOutcome.Effective, fallbackAdd(100 + policy.fallbackDelayDays, quiet))
    }

    @Test
    fun theFallbackNeedsTheOthersToHaveBeenSilentLongEnoughBeforeTheProposal() {
        val delayed = 100 + policy.fallbackDelayDays
        assertEquals(StewardOutcome.Pending, fallbackAdd(delayed, Activity(lastDay = mapOf(b to 100 - policy.inactiveDays + 1))))
        assertEquals(StewardOutcome.Effective, fallbackAdd(delayed, Activity(lastDay = mapOf(b to 100 - policy.inactiveDays))))
    }

    @Test
    fun anyActivityByAnotherStewardSinceTheProposalCancelsTheFallback() {
        val delayed = 100 + policy.fallbackDelayDays
        assertEquals(StewardOutcome.Pending, fallbackAdd(delayed, Activity(lastDay = mapOf(b to 1), activeSince = setOf(b))))
    }

    @Test
    fun aFirstProposalNeverTakesEffectByTheFallback() {
        assertEquals(StewardOutcome.Pending, fallbackAdd(100, Activity(lastDay = mapOf(b to 1)), prior = emptyList()))
        assertEquals(StewardOutcome.Pending, fallbackAdd(500, Activity(lastDay = mapOf(b to 1)), prior = listOf(StewardVote(b, 1, 3))), "another steward's old vote is not mine")
    }

    @Test
    fun aStewardWhoNeverSpokeIsQuietFromTheDayTheyBecameOne() {
        val stewards = mapOf(a to founding, b to 50)
        // b became a steward on day 50 and has sent nothing: on day 100 that is only 50 quiet days.
        assertEquals(StewardOutcome.Pending, fallbackAdd(100 + policy.fallbackDelayDays, Activity(), stewards))
        val later = listOf(StewardVote(a, 110, 10))
        assertEquals(StewardOutcome.Effective, fallbackAdd(110 + policy.fallbackDelayDays, Activity(), stewards, later))
        assertEquals(StewardOutcome.Effective, fallbackAdd(100 + policy.fallbackDelayDays, Activity(), mapOf(a to founding, b to founding)), "a founding steward who never spoke counts from the start")
    }

    @Test
    fun everyOtherStewardMustBeQuietNotJustOne() {
        val stewards = mapOf(a to founding, b to founding, c to founding)
        val delayed = 100 + policy.fallbackDelayDays
        val bQuiet = Activity(lastDay = mapOf(b to 1, c to 99))
        assertEquals(StewardOutcome.Pending, StewardRules.decide(policy, StewardChange.ADD, d, a, delayed, stewards, proposalAt100, bQuiet))
        val bothQuiet = Activity(lastDay = mapOf(b to 1, c to 2))
        assertEquals(StewardOutcome.Effective, StewardRules.decide(policy, StewardChange.ADD, d, a, delayed, stewards, proposalAt100, bothQuiet))
    }

    @Test
    fun theFallbackLetsALoneActiveStewardRemoveAQuietHelper() {
        val stewards = mapOf(a to founding, b to founding)
        val outcome = StewardRules.decide(policy, StewardChange.REMOVE, b, a, 100 + policy.fallbackDelayDays, stewards, proposalAt100, Activity(lastDay = mapOf(b to 5)))
        assertEquals(StewardOutcome.Effective, outcome)
        val stillActive = StewardRules.decide(policy, StewardChange.REMOVE, b, a, 100 + policy.fallbackDelayDays, stewards, proposalAt100, Activity(lastDay = mapOf(b to 99)))
        assertEquals(StewardOutcome.Pending, stillActive)
    }

    @Test
    fun theFallbackStillRespectsTheLastStewardAndTheNewStewardWait() {
        val quiet = Activity(lastDay = mapOf(b to 1))
        assertEquals("a circle keeps at least one steward", refused(StewardRules.decide(policy, StewardChange.REMOVE, a, a, 300, mapOf(a to founding), proposalAt100, quiet)))
        val fresh = mapOf(a to 290, b to founding)
        assertTrue(refused(StewardRules.decide(policy, StewardChange.ADD, c, a, 291, fresh, proposalAt100, quiet)).contains("cannot vote until day"))
    }

    @Test
    fun theSettingsInForceDecideTheFallbackTimes() {
        val shortTimes = policy.with(StewardSettings(quorum = 2, inactiveDays = MIN_STEWARD_INACTIVE_DAYS, fallbackDelayDays = MIN_STEWARD_FALLBACK_DELAY_DAYS))
        val stewards = mapOf(a to founding, b to founding)
        val activity = Activity(lastDay = mapOf(b to 100 - MIN_STEWARD_INACTIVE_DAYS))
        val day = 100 + MIN_STEWARD_FALLBACK_DELAY_DAYS
        assertEquals(StewardOutcome.Effective, StewardRules.decide(shortTimes, StewardChange.ADD, c, a, day, stewards, proposalAt100, activity))
        assertEquals(StewardOutcome.Pending, StewardRules.decide(policy, StewardChange.ADD, c, a, day, stewards, proposalAt100, activity))
    }

    @Test
    fun aWeekOfSilenceAndAWeekOfDelayIsEnoughAtTheNewMinimum() {
        val shortest = policy.with(StewardSettings(quorum = 2, inactiveDays = 7, fallbackDelayDays = 7))
        val stewards = mapOf(a to founding, b to founding)
        val activity = Activity(lastDay = mapOf(b to 93))
        assertEquals(StewardOutcome.Effective, StewardRules.decide(shortest, StewardChange.ADD, c, a, 107, stewards, proposalAt100, activity))
        val sixDaysQuiet = Activity(lastDay = mapOf(b to 94))
        assertEquals(StewardOutcome.Pending, StewardRules.decide(shortest, StewardChange.ADD, c, a, 107, stewards, proposalAt100, sixDaysQuiet))
    }
}
