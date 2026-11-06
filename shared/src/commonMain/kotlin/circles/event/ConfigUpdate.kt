package circles.event

import circles.policy.CircleConfig
import circles.policy.Tier

private const val TENTHS = 10.0
private const val ROUND_UP = 0.5

/** Limits for the settings a circle may change (ADR 0051). Wider changes need a new ADR. */
const val MAX_THRESHOLD_TENTHS = 100_000
const val MIN_HALF_LIFE_DAYS = 30
const val MAX_HALF_LIFE_DAYS = 730
const val MIN_VOUCHES_FOR_REGULAR = 1
const val MAX_VOUCHES_FOR_REGULAR = 5
const val MIN_VOUCH_VALIDITY_DAYS = 30
const val MAX_VOUCH_VALIDITY_DAYS = 730
const val MIN_REVIEWERS_FOR_PAUSE = 2
const val MAX_REVIEWERS_FOR_PAUSE = 9
const val MAX_INCIDENT_COOLDOWN_DAYS = 365

/**
 * The circle rules a `config.update` may set (ADR 0051). Thresholds are in tenths of a point so the payload holds whole
 * numbers only. Everything else about standing (event weights, counterpart caps) keeps the library defaults.
 */
/** Shown when the points for the levels do not rise from one level to the next. */
const val LEVEL_ORDER_PROBLEM = "the points must rise from each level to the next"

data class ConfigSettings(
    val regularTenths: Int,
    val trustedTenths: Int,
    val stewardTenths: Int,
    val halfLifeDays: Int,
    val vouchesForRegular: Int,
    val vouchValidityDays: Int,
    val reviewersForPause: Int,
    val defaultPauseDays: Int,
    val incidentCooldownDays: Int,
) {
    /** The first thing wrong with these values, or null. Ranges first, then the library's own check on the resulting config. */
    fun problem(base: CircleConfig): String? = when {
        listOf(regularTenths, trustedTenths, stewardTenths).any { it !in 1..MAX_THRESHOLD_TENTHS } -> "the points for each level must be from 0.1 to ${MAX_THRESHOLD_TENTHS / 10}"
        halfLifeDays !in MIN_HALF_LIFE_DAYS..MAX_HALF_LIFE_DAYS -> "the days for points to fade by half must be from $MIN_HALF_LIFE_DAYS to $MAX_HALF_LIFE_DAYS"
        vouchesForRegular !in MIN_VOUCHES_FOR_REGULAR..MAX_VOUCHES_FOR_REGULAR -> "the vouches needed for ${circles.policy.TierText.label(Tier.REGULAR)} must be from $MIN_VOUCHES_FOR_REGULAR to $MAX_VOUCHES_FOR_REGULAR"
        vouchValidityDays !in MIN_VOUCH_VALIDITY_DAYS..MAX_VOUCH_VALIDITY_DAYS -> "the days a vouch lasts must be from $MIN_VOUCH_VALIDITY_DAYS to $MAX_VOUCH_VALIDITY_DAYS"
        reviewersForPause !in MIN_REVIEWERS_FOR_PAUSE..MAX_REVIEWERS_FOR_PAUSE -> "the reviewers needed for a pause must be from $MIN_REVIEWERS_FOR_PAUSE to $MAX_REVIEWERS_FOR_PAUSE"
        defaultPauseDays !in MIN_PAUSE_DAYS..MAX_PAUSE_DAYS -> "the days a pause lasts must be from $MIN_PAUSE_DAYS to $MAX_PAUSE_DAYS"
        incidentCooldownDays !in 0..MAX_INCIDENT_COOLDOWN_DAYS -> "the days on hold after an incident must be from 0 to $MAX_INCIDENT_COOLDOWN_DAYS"
        // The library's own check speaks in identifiers; only the order of the thresholds can fail it here (ADR 0070).
        else -> applyTo(base).validate().firstOrNull()?.let { LEVEL_ORDER_PROBLEM }
    }

    fun applyTo(base: CircleConfig): CircleConfig = base.copy(
        thresholds = mapOf(Tier.REGULAR to regularTenths / TENTHS, Tier.TRUSTED to trustedTenths / TENTHS, Tier.STEWARD to stewardTenths / TENTHS),
        halfLifeDays = halfLifeDays,
        vouchesForRegular = vouchesForRegular,
        vouchValidityDays = vouchValidityDays,
        reviewersForPause = reviewersForPause,
        defaultPauseDays = defaultPauseDays,
        incidentCooldownDays = incidentCooldownDays,
    )

    companion object {
        /** The settings a config currently holds. */
        fun of(config: CircleConfig): ConfigSettings = ConfigSettings(
            regularTenths = tenths(config.thresholds[Tier.REGULAR] ?: 0.0),
            trustedTenths = tenths(config.thresholds[Tier.TRUSTED] ?: 0.0),
            stewardTenths = tenths(config.thresholds[Tier.STEWARD] ?: 0.0),
            halfLifeDays = config.halfLifeDays,
            vouchesForRegular = config.vouchesForRegular,
            vouchValidityDays = config.vouchValidityDays,
            reviewersForPause = config.reviewersForPause,
            defaultPauseDays = config.defaultPauseDays,
            incidentCooldownDays = config.incidentCooldownDays,
        )

        private fun tenths(points: Double): Int = (points * TENTHS + ROUND_UP).toInt()
    }
}

/** A config that took effect on [fromDay]; a decision dated on or after it uses it until a later one takes over. */
data class ConfigVersion(val fromDay: Int, val settings: ConfigSettings)

/** A proposed config that has some stewards' votes but not yet enough. */
data class PendingConfig(val settings: ConfigSettings, val voters: Set<circles.policy.MemberId>, val firstDay: Int)
