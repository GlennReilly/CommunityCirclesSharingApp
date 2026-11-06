package circles.policy

import circles.event.ConfigSettings
import circles.event.StandingRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * People see level numbers, never the tier identifiers (ADR 0070). Every code path in the library that writes a tier
 * into a sentence is run here, and its words are checked for an enum name or an old label.
 */
class TierTextTest {

    /** Enum names (identifiers) and the labels of ADR 0067, which must not reach a person. */
    private val forbidden = Regex("""\b(NEW|REGULAR|TRUSTED|STEWARD|Regular|Trusted|Steward level)\b""")

    private fun assertPlain(texts: List<String>) {
        assertTrue(texts.isNotEmpty())
        for (text in texts) forbidden.find(text)?.let { fail("\"${it.value}\" leaks into: $text") }
    }

    @Test
    fun everyTierHasALevelNumberFromOne() {
        assertEquals(listOf(1, 2, 3, 4), Tier.entries.map { TierText.number(it) })
        assertEquals(listOf("Level 1", "Level 2", "Level 3", "Level 4"), Tier.entries.map { TierText.label(it) })
        assertEquals("Level 2 or above", TierText.orAbove(Tier.REGULAR))
        assertPlain(Tier.entries.flatMap { listOf(TierText.label(it), TierText.orAbove(it)) })
    }

    @Test
    fun accessDecisionsNameLevelsForEveryNeededTier() {
        val newMember = standingWith(DEE)
        val trusted = standingWith(ALICE, trustedHistory(ALICE))
        for (itemClass in ItemClass.entries) {
            for (ownerMin in listOf(null) + Tier.entries) {
                val thing = item(BOB, itemClass, ownerMin)
                for (standing in listOf(newMember, trusted)) {
                    val decision = AccessPolicy.canBorrow(DEE, thing, CFG, standing, emptyList(), TODAY)
                    assertPlain(decision.reasons + ((decision as? AccessDecision.NotYet)?.howToUnlock ?: emptyList()))
                }
            }
        }
    }

    @Test
    fun theNotYetReasonSaysWhichLevelIsNeededAndWhichYouHave() {
        val decision = AccessPolicy.canBorrow(DEE, item(BOB, ItemClass.VALUABLE).copy(name = "Cordless drill"), CFG, standingWith(DEE), emptyList(), TODAY)
        assertIs<AccessDecision.NotYet>(decision)
        assertTrue("Cordless drill needs Level 2; you are Level 1." in decision.reasons, decision.reasons.toString())
        assertTrue("Or get 2 members at Level 2 or above to vouch for you." in decision.howToUnlock, decision.howToUnlock.toString())
    }

    @Test
    fun theEligibleReasonNamesTheLevels() {
        val decision = AccessPolicy.canBorrow(ALICE, item(BOB, ItemClass.VALUABLE).copy(name = "Drill"), CFG, standingWith(ALICE, trustedHistory(ALICE)), emptyList(), TODAY)
        assertIs<AccessDecision.Eligible>(decision)
        assertTrue("You are at Level 3, which is what Drill needs (Level 2 or above)." in decision.reasons, decision.reasons.toString())
    }

    @Test
    fun theVouchReasonNamesTheLevel() {
        val vouched = standingWith(DEE, vouches = listOf(vouch(ANN, DEE), vouch(BEA, DEE)))
        assertEquals(Tier.REGULAR, vouched.tier)
        assertTrue(vouched.reasons.any { it.endsWith("which opens Level 2 access.") }, vouched.reasons.toString())
        assertPlain(vouched.reasons)
    }

    @Test
    fun aPauseRefusalNamesTheLevel() {
        val refused = AccessPolicy.tryPause(DEE, CFG, "reason", setOf(ANN), { Tier.NEW }, TODAY)
        assertIs<PauseResult.Rejected>(refused)
        assertTrue("reviewers at Level 3 or above" in refused.reason, refused.reason)
        assertPlain(listOf(refused.reason))
    }

    @Test
    fun theStewardFloorNamesTheLevel() {
        val standing = StandingRules.standingOf(DEE, CFG, emptyList(), emptyList(), setOf(DEE), TODAY)
        assertEquals(Tier.TRUSTED, standing.tier)
        assertTrue("Stewards are treated as at least Level 3 in this circle." in standing.reasons, standing.reasons.toString())
        assertPlain(standing.reasons)
    }

    @Test
    fun circleRuleProblemsSpeakInLevelsNotIdentifiers() {
        val rules = ConfigSettings.of(CFG)
        val problems = listOfNotNull(
            rules.copy(vouchesForRegular = 0).problem(CFG),
            rules.copy(regularTenths = rules.trustedTenths).problem(CFG),
            rules.copy(stewardTenths = 0).problem(CFG),
        )
        assertEquals(3, problems.size)
        assertTrue(problems[0].startsWith("the vouches needed for Level 2 must be from"), problems[0])
        assertPlain(problems)
        assertTrue(problems.none { Regex("[a-z][A-Z]").containsMatchIn(it) }, "a camelCase identifier in $problems")
    }
}
