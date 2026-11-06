package circles.policy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CircleConfigTest {

    @Test
    fun defaultsAreValid() {
        assertTrue(CFG.validate().isEmpty())
    }

    @Test
    fun c5_nonIncreasingThresholdsAreReportedAndFallBackToDefaults() {
        val bad = CFG.copy(thresholds = mapOf(Tier.REGULAR to 10.0, Tier.TRUSTED to 3.0, Tier.STEWARD to 25.0))
        assertTrue(bad.validate().isNotEmpty())
        assertEquals(CFG.thresholds, bad.validatedOrDefault().thresholds)
    }

    @Test
    fun c5_missingThresholdsAreReported() {
        assertTrue(CFG.copy(thresholds = mapOf(Tier.REGULAR to 3.0)).validate().isNotEmpty())
    }

    @Test
    fun c5_equalThresholdsAreReported() {
        val equal = CFG.copy(thresholds = mapOf(Tier.REGULAR to 3.0, Tier.TRUSTED to 3.0, Tier.STEWARD to 25.0))
        assertTrue(equal.validate().isNotEmpty())
    }

    @Test
    fun c5_everyNonPositiveDurationOrCountIsReported() {
        val broken = listOf(
            CFG.copy(halfLifeDays = 0),
            CFG.copy(vouchesForRegular = 0),
            CFG.copy(vouchValidityDays = 0),
            CFG.copy(reviewersForPause = 0),
            CFG.copy(defaultPauseDays = 0),
            CFG.copy(incidentCooldownDays = -1),
            CFG.copy(maxCreditedPerCounterpart = 0),
            CFG.copy(counterpartWindowDays = 0)
        )
        broken.forEach { assertEquals(1, it.validate().size, "expected exactly one issue for $it") }
    }

    @Test
    fun c5_aValidConfigIsKeptAsIs() {
        val custom = CFG.copy(halfLifeDays = 90)
        assertEquals(custom, custom.validatedOrDefault())
    }
}
