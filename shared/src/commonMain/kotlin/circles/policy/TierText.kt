package circles.policy

/**
 * The only place a [Tier] becomes words people read (ADR 0070): levels are numbered from 1, so `NEW` is "Level 1",
 * `REGULAR` "Level 2", `TRUSTED` "Level 3" and the advisory `STEWARD` tier "Level 4". The enum names are identifiers
 * (they travel in sealed payloads) and must never reach a screen: every message calls this instead of printing a tier.
 */
object TierText {
    private const val FIRST_LEVEL = 1

    /** The level number shown for [tier]: 1 for the lowest. */
    fun number(tier: Tier): Int = tier.rank + FIRST_LEVEL

    /** "Level 2". */
    fun label(tier: Tier): String = "Level ${number(tier)}"

    /** "Level 2 or above". */
    fun orAbove(tier: Tier): String = "${label(tier)} or above"
}
