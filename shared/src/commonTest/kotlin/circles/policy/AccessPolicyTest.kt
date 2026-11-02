package circles.policy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AccessPolicyTest {

    private val newcomer = standingWith(DEE)
    private val trusted = standingWith(ALICE, trustedHistory(ALICE))

    private fun decide(
        borrower: MemberId,
        item: Item,
        standing: Standing,
        pauses: List<Pause> = emptyList(),
        today: Day = TODAY
    ): AccessDecision = AccessPolicy.canBorrow(borrower, item, CFG, standing, pauses, today)

    // ---- Baseline behaviour listed in spec section 2 ----

    @Test
    fun essentialsAreAlwaysOpenEvenDuringAPause() {
        val pause = Pause(DEE, CIRCLE, "test", setOf(ANN, BEA, CAL), TODAY - 1, TODAY + 10)
        assertIs<AccessDecision.Eligible>(decide(DEE, item(ALICE, ItemClass.ESSENTIAL), newcomer, listOf(pause)))
        assertIs<AccessDecision.Paused>(decide(DEE, item(ALICE, ItemClass.EVERYDAY), newcomer, listOf(pause)))
    }

    @Test
    fun pauseEndsOnItsUntilDay() {
        val pause = Pause(DEE, CIRCLE, "test", setOf(ANN, BEA, CAL), TODAY - 10, TODAY)
        assertIs<AccessDecision.Eligible>(decide(DEE, item(ALICE, ItemClass.EVERYDAY), newcomer, listOf(pause)))
    }

    @Test
    fun ownerCannotLowerTheClassMinimum() {
        val valuable = item(ALICE, ItemClass.VALUABLE, ownerMinTier = Tier.NEWCOMER)
        assertIs<AccessDecision.NotYet>(decide(DEE, valuable, newcomer))
    }

    @Test
    fun ownerCanRaiseTheMinimum() {
        val neighbour = standingWith(BEA, listOf(event(BEA, EventKind.HOSTED_OR_ORGANISED), event(BEA, EventKind.HOSTED_OR_ORGANISED)))
        assertEquals(Tier.NEIGHBOUR, neighbour.tier)
        val strict = item(ALICE, ItemClass.EVERYDAY, ownerMinTier = Tier.TRUSTED)
        assertIs<AccessDecision.NotYet>(decide(BEA, strict, neighbour))
        assertIs<AccessDecision.Eligible>(decide(BEA, item(ALICE, ItemClass.EVERYDAY), neighbour))
    }

    @Test
    fun itemNotSharedInTheCircleIsNotYet() {
        val elsewhere = item(ALICE, ItemClass.EVERYDAY).copy(circles = setOf(CircleId("other")))
        assertIs<AccessDecision.NotYet>(decide(DEE, elsewhere, newcomer))
    }

    // ---- C3: own item ----

    @Test
    fun c3_ownerAskingForTheirOwnItemGetsOwnItem() {
        assertIs<AccessDecision.OwnItem>(decide(ALICE, item(ALICE, ItemClass.HIGH_VALUE), trusted))
    }

    // ---- C4: tailored unlock advice ----

    @Test
    fun c4_vouchRouteIsOfferedOnlyWhereVouchingHelps() {
        val forValuable = decide(DEE, item(ALICE, ItemClass.VALUABLE), newcomer) as AccessDecision.NotYet
        assertTrue(forValuable.howToUnlock.any { "vouch" in it })
        val forHighValue = decide(DEE, item(ALICE, ItemClass.HIGH_VALUE), newcomer) as AccessDecision.NotYet
        assertFalse(forHighValue.howToUnlock.any { "vouch" in it })
    }

    // ---- C7: incident cooldown holds higher-value items only ----

    @Test
    fun c7_newcomerWithAnIncidentKeepsEssentialsAndEverydayItems() {
        val s = standingWith(DEE, listOf(event(DEE, EventKind.DAMAGED_OR_LOST_UNREPORTED, daysAgo = 1)))
        assertIs<AccessDecision.Eligible>(decide(DEE, item(ALICE, ItemClass.ESSENTIAL), s))
        assertIs<AccessDecision.Eligible>(decide(DEE, item(ALICE, ItemClass.EVERYDAY), s))
        val valuable = decide(DEE, item(ALICE, ItemClass.VALUABLE), s)
        assertIs<AccessDecision.NotYet>(valuable)
        assertTrue(valuable.reasons.any { "on hold" in it })
    }

    @Test
    fun c7_trustedMemberIsAlsoHeldThenReleased() {
        val events = trustedHistory(BEA) + event(BEA, EventKind.DAMAGED_OR_LOST_UNREPORTED, daysAgo = 1)
        val during = standingWith(BEA, events)
        assertEquals(Tier.TRUSTED, during.tier)
        assertIs<AccessDecision.NotYet>(decide(BEA, item(ALICE, ItemClass.VALUABLE), during))

        val endDay = TODAY - 1 + CFG.incidentCooldownDays
        val after = standingWith(BEA, events, today = endDay)
        assertEquals(Tier.TRUSTED, after.tier)
        assertIs<AccessDecision.Eligible>(decide(BEA, item(ALICE, ItemClass.VALUABLE), after, today = endDay))
    }

    // ---- C9: pauses ----

    private val tiers = mapOf(
        ALICE to Tier.TRUSTED, ANN to Tier.TRUSTED, BEA to Tier.TRUSTED,
        CAL to Tier.NEWCOMER, DEE to Tier.NEWCOMER, BOB to Tier.NEIGHBOUR
    )
    private fun tierOf(m: MemberId): Tier = tiers[m] ?: Tier.NEWCOMER

    @Test
    fun c9_newcomerReviewersDoNotCount() {
        val result = AccessPolicy.tryPause(BOB, CFG, "reason", setOf(CAL, DEE, MemberId("x")), ::tierOf, TODAY)
        assertIs<PauseResult.Rejected>(result)
    }

    @Test
    fun c9_trustedReviewersPlaceAPauseWithTheDefaultDuration() {
        val result = AccessPolicy.tryPause(BOB, CFG, "reason", setOf(ALICE, ANN, BEA), ::tierOf, TODAY)
        val placed = assertIs<PauseResult.Placed>(result)
        assertEquals(TODAY + CFG.defaultPauseDays, placed.pause.untilDay)
        assertTrue(placed.pause.appealOpen)
    }

    @Test
    fun c9_theSubjectIsNeverCountedAsAReviewer() {
        val result = AccessPolicy.tryPause(ALICE, CFG, "reason", setOf(ALICE, ANN, BEA), ::tierOf, TODAY)
        assertIs<PauseResult.Rejected>(result)
    }

    @Test
    fun c9_aPauseNeedsAReason() {
        val result = AccessPolicy.tryPause(BOB, CFG, "  ", setOf(ALICE, ANN, BEA), ::tierOf, TODAY)
        assertIs<PauseResult.Rejected>(result)
    }
}
