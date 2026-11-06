package circles.policy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StandingCalculatorTest {

    private fun assertPoints(expected: Double, actual: Double) =
        assertTrue(kotlin.math.abs(expected - actual) < POINTS_TOLERANCE, "expected $expected but was $actual")

    // ---- Baseline behaviour listed in spec section 2 ----

    @Test
    fun decayHalvesEveryHalfLife() {
        val half = CFG.halfLifeDays
        assertPoints(2.0, standingWith(ALICE, listOf(event(ALICE, EventKind.HOSTED_OR_ORGANISED, 0))).points)
        assertPoints(1.0, standingWith(ALICE, listOf(event(ALICE, EventKind.HOSTED_OR_ORGANISED, half))).points)
        assertPoints(0.5, standingWith(ALICE, listOf(event(ALICE, EventKind.HOSTED_OR_ORGANISED, 2 * half))).points)
    }

    @Test
    fun pointsClampAtZero() {
        val s = standingWith(ALICE, listOf(event(ALICE, EventKind.RETURNED_LATE), event(ALICE, EventKind.RETURNED_LATE)))
        assertEquals(0.0, s.points)
        assertEquals(Tier.NEW, s.tier)
    }

    @Test
    fun lateReturnIsRecoverable() {
        val s = standingWith(
            ALICE,
            listOf(
                event(ALICE, EventKind.RETURNED_LATE),
                event(ALICE, EventKind.LENT_ITEM_RETURNED_OK),
                event(ALICE, EventKind.LENT_ITEM_RETURNED_OK)
            )
        )
        assertPoints(1.5, s.points)
    }

    @Test
    fun futureEventsAreIgnored() {
        val s = standingWith(ALICE, listOf(event(ALICE, EventKind.HOSTED_OR_ORGANISED, daysAgo = -1)))
        assertEquals(0.0, s.points)
    }

    @Test
    fun resultDoesNotDependOnEventOrder() {
        val events = trustedHistory(ALICE, 12) + listOf(
            event(ALICE, EventKind.RETURNED_LATE, 3),
            event(ALICE, EventKind.BORROWED_AND_RETURNED_OK, 9, counterpart = BOB, sourceId = "x"),
            event(ALICE, EventKind.BORROWED_AND_RETURNED_OK, 8, counterpart = BOB, sourceId = "y")
        )
        assertEquals(standingWith(ALICE, events), standingWith(ALICE, events.reversed()))
        assertEquals(standingWith(ALICE, events), standingWith(ALICE, events.shuffled(kotlin.random.Random(7))))
    }

    @Test
    fun vouchExpiresOnItsExpiryDay() {
        val expiredToday = Vouch(ALICE, CAL, CIRCLE, TODAY - 10, TODAY)
        val liveTomorrow = Vouch(ANN, CAL, CIRCLE, TODAY - 10, TODAY + 1)
        assertEquals(Tier.NEW, standingWith(CAL, vouches = listOf(expiredToday, liveTomorrow)).tier)
        val bothLive = listOf(liveTomorrow, Vouch(ALICE, CAL, CIRCLE, TODAY - 10, TODAY + 1))
        assertEquals(Tier.REGULAR, standingWith(CAL, vouches = bothLive).tier)
    }

    @Test
    fun voucherBelowMinTierIsIgnored() {
        val vouches = listOf(vouch(ALICE, CAL), vouch(ANN, CAL))
        assertEquals(Tier.NEW, standingWith(CAL, vouches = vouches, tierOf = { Tier.NEW }).tier)
        assertEquals(Tier.REGULAR, standingWith(CAL, vouches = vouches, tierOf = { Tier.REGULAR }).tier)
    }

    // ---- C1: vouch counting ----

    @Test
    fun c1_oneVoucherVouchingTwiceCountsOnce() {
        val vouches = listOf(vouch(ALICE, CAL, daysAgo = 10), vouch(ALICE, CAL, daysAgo = 5))
        assertEquals(Tier.NEW, standingWith(CAL, vouches = vouches).tier)
    }

    @Test
    fun c1_twoDistinctVouchersOpenRegular() {
        val vouches = listOf(vouch(ALICE, CAL), vouch(ANN, CAL))
        assertEquals(Tier.REGULAR, standingWith(CAL, vouches = vouches).tier)
    }

    @Test
    fun c1_futureDatedVouchIsIgnored() {
        val future = Vouch(ANN, CAL, CIRCLE, TODAY + 1, TODAY + 100)
        assertEquals(Tier.NEW, standingWith(CAL, vouches = listOf(vouch(ALICE, CAL), future)).tier)
    }

    @Test
    fun c1_selfVouchIsIgnored() {
        val vouches = listOf(vouch(ALICE, CAL), vouch(CAL, CAL))
        assertEquals(Tier.NEW, standingWith(CAL, vouches = vouches).tier)
    }

    @Test
    fun c1_authorExpiryIsCappedByValidityWindow() {
        // Dated 200 days ago with an expiry far in the future: capped at day + 180, so it ended 20 days ago.
        val longVouch = { voucher: MemberId -> Vouch(voucher, CAL, CIRCLE, TODAY - 200, TODAY + 10_000) }
        assertEquals(Tier.NEW, standingWith(CAL, vouches = listOf(longVouch(ALICE), longVouch(ANN))).tier)
        val recent = { voucher: MemberId -> Vouch(voucher, CAL, CIRCLE, TODAY - 100, TODAY + 10_000) }
        assertEquals(Tier.REGULAR, standingWith(CAL, vouches = listOf(recent(ALICE), recent(ANN))).tier)
    }

    // ---- C2: sponsor nudges are deterministic ----

    @Test
    fun c2_nudgesUseTheIncidentDayAndLiveVouchersOnce() {
        val incident = event(CAL, EventKind.DAMAGED_OR_LOST_UNREPORTED, daysAgo = 100, sourceId = "inc1")
        val vouches = listOf(
            Vouch(ALICE, CAL, CIRCLE, incident.day - 50, incident.day + 100),
            Vouch(ALICE, CAL, CIRCLE, incident.day - 40, incident.day + 100), // duplicate voucher
            Vouch(BEA, CAL, CIRCLE, incident.day - 50, incident.day - 10),    // expired before the incident
            Vouch(ANN, CAL, CIRCLE, incident.day + 20, incident.day + 200)    // dated after the incident
        )
        val nudges = StandingCalculator.sponsorNudges(incident, vouches, CFG)
        assertEquals(1, nudges.size)
        assertEquals(ALICE, nudges.single().member)
        assertEquals(incident.day, nudges.single().day)
        assertEquals(EventKind.RETURNED_LATE, nudges.single().kind)
        assertEquals(nudges, StandingCalculator.sponsorNudges(incident, vouches, CFG))
    }

    @Test
    fun c2_onlyIncidentsNudgeSponsors() {
        val late = event(CAL, EventKind.RETURNED_LATE)
        assertTrue(StandingCalculator.sponsorNudges(late, listOf(vouch(ALICE, CAL)), CFG).isEmpty())
    }

    // ---- C6 / C12: demo expectations and two-pass tiers ----

    @Test
    fun c6_demoExpectations() {
        val events = trustedHistory(ALICE, 24) +
            List(16) { event(BEA, EventKind.SKILL_SHARE_HELPED, daysAgo = 14 * it + 10) } +
            event(CAL, EventKind.BORROWED_AND_RETURNED_OK, daysAgo = 14)
        val vouches = listOf(vouch(ALICE, CAL, 10), vouch(BEA, CAL, 9))
        val all = StandingCalculator.computeAll(listOf(ALICE, BEA, CAL, DEE), CFG, events, vouches, TODAY)
        assertEquals(Tier.TRUSTED, all.getValue(ALICE).tier)
        assertEquals(Tier.TRUSTED, all.getValue(BEA).tier)
        assertEquals(Tier.REGULAR, all.getValue(CAL).tier)
        assertEquals(Tier.NEW, all.getValue(DEE).tier)
    }

    @Test
    fun c12_aMemberVouchedInByOthersCannotVouchYet() {
        val events = trustedHistory(ALICE) + trustedHistory(ANN)
        val members = listOf(ALICE, ANN, BEA, CAL)
        val vouches = listOf(
            vouch(ALICE, BEA), vouch(ANN, BEA),   // Bea becomes REGULAR through vouches only
            vouch(BEA, CAL), vouch(ALICE, CAL)    // Bea's vouch for Cal must not count
        )
        val all = StandingCalculator.computeAll(members, CFG, events, vouches, TODAY)
        assertEquals(Tier.REGULAR, all.getValue(BEA).tier)
        assertEquals(Tier.NEW, all.getValue(CAL).tier)

        val withTwoRealVouchers = vouches + vouch(ANN, CAL)
        val all2 = StandingCalculator.computeAll(members, CFG, events, withTwoRealVouchers, TODAY)
        assertEquals(Tier.REGULAR, all2.getValue(CAL).tier)
    }

    // ---- C7: incident cooldown ----

    @Test
    fun c7_incidentStartsACooldownThatEnds() {
        val incident = event(DEE, EventKind.DAMAGED_OR_LOST_UNREPORTED, daysAgo = 1)
        val s = standingWith(DEE, listOf(incident))
        assertEquals(0.0, s.points) // the clamp hides the penalty, which is why the cooldown exists
        assertEquals(incident.day + CFG.incidentCooldownDays, s.incidentUntilDay)

        val endDay = incident.day + CFG.incidentCooldownDays
        assertNull(standingWith(DEE, listOf(incident), today = endDay).incidentUntilDay)
        assertEquals(endDay - TODAY, s.incidentUntilDay!! - TODAY)
    }

    // ---- C8: credit cap per counterpart ----

    @Test
    fun c8_repeatedCreditWithOneCounterpartIsCapped() {
        val withBob = List(10) { event(ALICE, EventKind.BORROWED_AND_RETURNED_OK, daysAgo = it + 1, counterpart = BOB, sourceId = "e$it") }
        val s = standingWith(ALICE, withBob)
        assertTrue(s.points > 1.4 && s.points <= 1.5, "three credits of 0.5 decayed slightly, was ${s.points}")
    }

    @Test
    fun c8_differentCounterpartsAllCount() {
        val many = List(10) {
            event(ALICE, EventKind.BORROWED_AND_RETURNED_OK, daysAgo = it + 1, counterpart = MemberId("peer$it"), sourceId = "e$it")
        }
        assertTrue(standingWith(ALICE, many).points > 4.5)
    }

    @Test
    fun c8_capDoesNotDependOnInputOrder() {
        val withBob = List(10) { event(ALICE, EventKind.BORROWED_AND_RETURNED_OK, daysAgo = it + 1, counterpart = BOB, sourceId = "e$it") }
        assertEquals(standingWith(ALICE, withBob).points, standingWith(ALICE, withBob.reversed()).points)
    }

    @Test
    fun c8_capResetsAfterTheWindow() {
        val old = List(3) { event(ALICE, EventKind.BORROWED_AND_RETURNED_OK, daysAgo = 200 + it, counterpart = BOB, sourceId = "o$it") }
        val recent = List(3) { event(ALICE, EventKind.BORROWED_AND_RETURNED_OK, daysAgo = it, counterpart = BOB, sourceId = "r$it") }
        val both = standingWith(ALICE, old + recent).points
        assertTrue(both > standingWith(ALICE, recent).points + 0.2)
    }

    // ---- C10: rounding and formatting ----

    @Test
    fun c10_pointsAtThresholdAfterRoundingGiveTheHigherTier() {
        val exact = CFG.copy(weights = mapOf(EventKind.HOSTED_OR_ORGANISED to 3.0))
        assertEquals(Tier.REGULAR, standingWith(ALICE, listOf(event(ALICE, EventKind.HOSTED_OR_ORGANISED)), cfg = exact).tier)
        val nearly = CFG.copy(weights = mapOf(EventKind.HOSTED_OR_ORGANISED to 2.996))
        assertEquals(Tier.REGULAR, standingWith(ALICE, listOf(event(ALICE, EventKind.HOSTED_OR_ORGANISED)), cfg = nearly).tier)
        val below = CFG.copy(weights = mapOf(EventKind.HOSTED_OR_ORGANISED to 2.99))
        assertEquals(Tier.NEW, standingWith(ALICE, listOf(event(ALICE, EventKind.HOSTED_OR_ORGANISED)), cfg = below).tier)
    }

    @Test
    fun c10_tiesRoundUpOnEveryPlatform() {
        val tie = CFG.copy(weights = mapOf(EventKind.HOSTED_OR_ORGANISED to 0.125))
        // 0.125 is exactly representable; to two decimals the tie rounds up to 0.13, not to even (0.12).
        assertPoints(0.13, standingWith(ALICE, listOf(event(ALICE, EventKind.HOSTED_OR_ORGANISED)), cfg = tie).points)
    }

    @Test
    fun c10_format1IsStable() {
        assertEquals("3.0", format1(3.0))
        assertEquals("-0.5", format1(-0.5))
        assertEquals("0.0", format1(0.0))
        assertEquals("0.1", format1(0.05)) // ties round up
        assertEquals("12.3", format1(12.34))
        assertEquals("0.0", format1(-0.04)) // no "-0.0"
    }

    // ---- C11 / C13: configurable weights and the prompt-confirmation kind ----

    @Test
    fun c11_changedWeightChangesPoints() {
        val heavy = CFG.copy(weights = mapOf(EventKind.LENT_ITEM_RETURNED_OK to 5.0))
        assertPoints(5.0, standingWith(ALICE, listOf(event(ALICE, EventKind.LENT_ITEM_RETURNED_OK)), cfg = heavy).points)
    }

    @Test
    fun c11_missingWeightFallsBackToTheEnumDefault() {
        val none = CFG.copy(weights = emptyMap())
        assertPoints(1.0, standingWith(ALICE, listOf(event(ALICE, EventKind.LENT_ITEM_RETURNED_OK)), cfg = none).points)
    }

    @Test
    fun c13_promptConfirmationIsCreditedAndDecays() {
        assertPoints(0.25, standingWith(ALICE, listOf(event(ALICE, EventKind.CONFIRMED_RETURN_PROMPTLY))).points)
        val old = event(ALICE, EventKind.CONFIRMED_RETURN_PROMPTLY, daysAgo = CFG.halfLifeDays)
        assertPoints(0.125, standingWith(ALICE, listOf(old)).points)
    }
}
