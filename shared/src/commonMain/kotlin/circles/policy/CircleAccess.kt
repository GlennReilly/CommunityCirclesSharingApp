/**
 * CircleAccess.kt - v0.2 implementation of a non-punitive, access-based trust model for a
 * community lending / skill-share network.
 *
 * v0.2 implements the policy corrections C1-C13 in the spec (section 2.1). Compared with
 * v0.1: integer days instead of java.time (so it compiles for Kotlin/JS), no String.format,
 * points rounded to two decimals before comparison, distinct vouchers, deterministic sponsor
 * nudges, incident cooldown, per-counterpart credit cap, validated config, and a library
 * `computeAll` for the two-pass tier computation.
 *
 * Ideas encoded here:
 *  - Trust is local: standing is computed per circle, never as one global score.
 *  - Access is graded (tiers). The floor tier is never "banned".
 *  - Standing decays toward neutral, it does not go negative.
 *  - Vouching gives new members an on-ramp; the sponsor shares a little of the risk.
 *  - Essentials are always borrowable by any member (need never depends on contribution).
 *  - Owners always keep consent over their own items.
 *  - Every decision carries human-readable reasons (no opaque scores).
 *  - Pauses are the last resort: they need several trusted reviewers, expire, and can be appealed.
 *
 * Pure functions, no I/O, common Kotlin only (runs on the JVM and in the browser). Tests are in
 * shared/src/commonTest and cover corrections C1-C13 from the spec (section 2.1).
 */
package circles.policy

import kotlin.jvm.JvmInline
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.floor

// ---------- Constants ----------

private const val POINTS_SCALE = 100.0          // points are rounded to 1/100 before comparing (C10)
private const val TENTHS_SCALE = 10.0
private const val HALF = 0.5
private const val DEFAULT_REGULAR_POINTS = 3.0
private const val DEFAULT_TRUSTED_POINTS = 10.0
private const val DEFAULT_STEWARD_POINTS = 25.0
private const val DEFAULT_HALF_LIFE_DAYS = 180
private const val DEFAULT_VOUCHES_FOR_REGULAR = 2
private const val DEFAULT_VOUCH_VALIDITY_DAYS = 180
private const val DEFAULT_REVIEWERS_FOR_PAUSE = 3
private const val DEFAULT_PAUSE_DAYS = 30
private const val DEFAULT_INCIDENT_COOLDOWN_DAYS = 90
private const val DEFAULT_MAX_CREDITED_PER_COUNTERPART = 3
private const val DEFAULT_COUNTERPART_WINDOW_DAYS = 90

// ---------- Core entities ----------

@JvmInline value class MemberId(val v: String)
@JvmInline value class CircleId(val v: String)
@JvmInline value class ItemId(val v: String)

/** Days since 1970-01-01 UTC. The envelope already carries a day, so all policy maths uses days. */
typealias Day = Int

data class Member(val id: MemberId, val name: String)

enum class Tier(val rank: Int) { NEW(0), REGULAR(1), TRUSTED(2), STEWARD(3) }

/** What kind of thing is being shared. Drives the minimum tier needed. */
enum class ItemClass(val minTier: Tier, val heldDuringCooldown: Boolean) {
    ESSENTIAL(Tier.NEW, heldDuringCooldown = false),   // ladder, jump leads: always open to members
    EVERYDAY(Tier.NEW, heldDuringCooldown = false),    // crockpot, air mattress
    VALUABLE(Tier.REGULAR, heldDuringCooldown = true),    // power tools, camping gear
    HIGH_VALUE(Tier.TRUSTED, heldDuringCooldown = true)     // trailer, chainsaw, generator
}

data class Item(
    val id: ItemId,
    val owner: MemberId,
    val name: String,
    val itemClass: ItemClass,
    /** Owner may require a higher tier than the class default, never a lower one. */
    val ownerMinTier: Tier? = null,
    val circles: Set<CircleId>
)

// ---------- Events that build standing ----------

enum class EventKind(val weight: Double) {
    LENT_ITEM_RETURNED_OK(1.0),
    BORROWED_AND_RETURNED_OK(0.5),   // good borrowing counts, so new members can climb
    SKILL_SHARE_HELPED(1.5),
    HOSTED_OR_ORGANISED(2.0),
    MENTORED_NEW_MEMBER(1.5),
    CONFIRMED_RETURN_PROMPTLY(0.25), // owner confirmed a return within promptConfirmDays (C13); never a penalty for not doing so
    RETURNED_LATE(-0.5),             // small and recoverable; decays like everything else
    DAMAGED_OR_LOST_UNREPORTED(-2.0); // the only sizeable negative; honest reporting is NOT penalised

    val isIncident: Boolean get() = this == DAMAGED_OR_LOST_UNREPORTED
}

/** Circle-level knobs. Each circle can tune its own values; they arrive via untrusted events, so validate. */
data class CircleConfig(
    val id: CircleId,
    val name: String,
    /** Standing points needed for each tier above NEW. Must be strictly increasing (C5). */
    val thresholds: Map<Tier, Double> = mapOf(
        Tier.REGULAR to DEFAULT_REGULAR_POINTS,
        Tier.TRUSTED to DEFAULT_TRUSTED_POINTS,
        Tier.STEWARD to DEFAULT_STEWARD_POINTS
    ),
    /** Standing points fade by half this often, so quiet members drift back to NEW, not below. */
    val halfLifeDays: Int = DEFAULT_HALF_LIFE_DAYS,
    /** How many distinct vouchers (at least [voucherMinTier]) lift a new member to REGULAR. */
    val vouchesForRegular: Int = DEFAULT_VOUCHES_FOR_REGULAR,
    val voucherMinTier: Tier = Tier.REGULAR,
    /** Caps a vouch's author-supplied expiry (C1). */
    val vouchValidityDays: Int = DEFAULT_VOUCH_VALIDITY_DAYS,
    /** Distinct reviewers needed to place any pause; protects against one person acting alone. */
    val reviewersForPause: Int = DEFAULT_REVIEWERS_FOR_PAUSE,
    /** Only reviewers at this tier or above count (C9). */
    val pauseReviewerMinTier: Tier = Tier.TRUSTED,
    val defaultPauseDays: Int = DEFAULT_PAUSE_DAYS,
    /** After an unreported incident, higher-value items are on hold this long (C7). */
    val incidentCooldownDays: Int = DEFAULT_INCIDENT_COOLDOWN_DAYS,
    /** Positive events with the same counterpart are credited at most this often per window (C8). */
    val maxCreditedPerCounterpart: Int = DEFAULT_MAX_CREDITED_PER_COUNTERPART,
    val counterpartWindowDays: Int = DEFAULT_COUNTERPART_WINDOW_DAYS,
    /** Points per event kind; a missing key falls back to the enum default (C11). */
    val weights: Map<EventKind, Double> = EventKind.entries.associateWith { it.weight }
) {
    fun weightOf(kind: EventKind): Double = weights[kind] ?: kind.weight

    /** Returns a list of problems; empty means the config is usable (C5). */
    fun validate(): List<String> = buildList {
        val regular = thresholds[Tier.REGULAR]
        val trusted = thresholds[Tier.TRUSTED]
        val steward = thresholds[Tier.STEWARD]
        if (regular == null || trusted == null || steward == null) {
            add("thresholds must define REGULAR, TRUSTED and STEWARD")
        } else if (!(0.0 < regular && regular < trusted && trusted < steward)) {
            add("thresholds must be positive and strictly increasing")
        }
        if (halfLifeDays <= 0) add("halfLifeDays must be positive")
        if (vouchesForRegular < 1) add("vouchesForRegular must be at least 1")
        if (vouchValidityDays <= 0) add("vouchValidityDays must be positive")
        if (reviewersForPause < 1) add("reviewersForPause must be at least 1")
        if (defaultPauseDays <= 0) add("defaultPauseDays must be positive")
        if (incidentCooldownDays < 0) add("incidentCooldownDays must not be negative")
        if (maxCreditedPerCounterpart < 1) add("maxCreditedPerCounterpart must be at least 1")
        if (counterpartWindowDays <= 0) add("counterpartWindowDays must be positive")
    }

    /** Use at fold time: a bad config from the log must not break everyone's app. */
    fun validatedOrDefault(): CircleConfig = if (validate().isEmpty()) this else CircleConfig(id, name)
}

data class StandingEvent(
    val member: MemberId,
    val circle: CircleId,
    val kind: EventKind,
    val day: Day,
    /** The other party, when there is one. Used for the credit cap (C8). */
    val counterpart: MemberId? = null,
    /** Id of the source event; makes ordering deterministic. */
    val sourceId: String = ""
)

data class Vouch(
    val voucher: MemberId,
    val vouchee: MemberId,
    val circle: CircleId,
    val day: Day,
    val expiresDay: Day
) {
    /** The author's expiry, capped by the circle's validity window (C1). */
    fun effectiveExpiry(cfg: CircleConfig): Day = minOf(expiresDay, day + cfg.vouchValidityDays)

    /** Live on [at]: dated no later than [at], not expired, not a self-vouch (C1). */
    fun isLiveOn(at: Day, cfg: CircleConfig): Boolean =
        voucher != vouchee && day <= at && effectiveExpiry(cfg) > at
}

/** Last resort. Needs reviewers, an expiry, a stated reason and an appeal route. */
data class Pause(
    val member: MemberId,
    val circle: CircleId,
    val reason: String,
    val reviewers: Set<MemberId>,
    val fromDay: Day,
    val untilDay: Day,
    val appealOpen: Boolean = true
)

// ---------- Standing computation ----------

data class Standing(
    val points: Double,
    val tier: Tier,
    val reasons: List<String>,
    /** While today is before this day, higher-value items are on hold (C7). Null when no cooldown. */
    val incidentUntilDay: Day? = null
)

object StandingCalculator {

    /** Exponential decay: weight halves every [halfLifeDays]. */
    private fun decay(weight: Double, ageDays: Int, halfLifeDays: Int): Double =
        weight * 0.5.pow(ageDays.toDouble() / halfLifeDays.toDouble())

    /** Round half up with floor, not kotlin.math.round: the JVM rounds ties to even, JS rounds them up (C10). */
    private fun round2(x: Double): Double = floor(x * POINTS_SCALE + HALF) / POINTS_SCALE

    /**
     * Positive events with a counterpart are credited at most `maxCreditedPerCounterpart` times per
     * `counterpartWindowDays`, taking events in day order so the result never depends on input order (C8).
     * Negative events and events without a counterpart are always credited.
     */
    private fun credited(events: List<StandingEvent>, cfg: CircleConfig): List<StandingEvent> {
        val (capped, free) = events.partition { it.counterpart != null && cfg.weightOf(it.kind) > 0.0 }
        val ordered = capped.sortedWith(compareBy({ it.day }, { it.sourceId }, { it.kind.name }))
        val creditedDaysByPeer = HashMap<MemberId, MutableList<Day>>()
        val kept = ordered.filter { e ->
            val peer = e.counterpart ?: return@filter true
            val days = creditedDaysByPeer.getOrPut(peer) { mutableListOf() }
            val recent = days.count { it > e.day - cfg.counterpartWindowDays }
            if (recent < cfg.maxCreditedPerCounterpart) {
                days += e.day
                true
            } else {
                false
            }
        }
        return free + kept
    }

    fun compute(
        member: MemberId,
        cfg: CircleConfig,
        events: List<StandingEvent>,
        vouches: List<Vouch>,
        tierOf: (MemberId) -> Tier,   // the voucher's own tier; pass base tiers (see computeAll)
        today: Day
    ): Standing {
        val reasons = mutableListOf<String>()
        val mine = events.filter { it.member == member && it.circle == cfg.id && it.day <= today }

        // Sum decayed events; clamp at zero so the score never goes below neutral.
        val raw = credited(mine, cfg).sumOf { decay(cfg.weightOf(it.kind), today - it.day, cfg.halfLifeDays) }
        val points = round2(raw.coerceAtLeast(0.0))
        reasons += "Activity in this circle gives ${format1(points)} standing points (older activity counts less)."

        // Tier from points. Thresholds are strictly increasing once validated.
        var tier = Tier.entries
            .filter { it != Tier.NEW }
            .sortedByDescending { it.rank }
            .firstOrNull { t -> points >= (cfg.thresholds[t] ?: Double.MAX_VALUE) }
            ?: Tier.NEW

        // Vouching on-ramp: enough distinct, live vouchers of sufficient tier lift to REGULAR (C1).
        val liveVouchers = vouches
            .filter { it.vouchee == member && it.circle == cfg.id && it.isLiveOn(today, cfg) }
            .map { it.voucher }
            .distinct()
            .count { tierOf(it).rank >= cfg.voucherMinTier.rank }
        if (tier.rank < Tier.REGULAR.rank && liveVouchers >= cfg.vouchesForRegular) {
            tier = Tier.REGULAR
            reasons += "$liveVouchers members vouched for this member, which opens Regular access."
        }

        // Incident cooldown: the clamp means points alone cannot carry a consequence (C7).
        val latestIncident = mine.filter { it.kind.isIncident }.maxOfOrNull { it.day }
        val until = latestIncident?.plus(cfg.incidentCooldownDays)
        val incidentUntil = if (until != null && until > today) until else null
        if (incidentUntil != null) {
            reasons += "A recent unreported incident puts higher-value items on hold for ${incidentUntil - today} more days. " +
                "Essentials and everyday items stay open."
        }

        return Standing(points, tier, reasons, incidentUntil)
    }

    /**
     * Two-pass tiers (C12). Pass 1: tiers from points only. Pass 2: vouching, using each voucher's pass-1 tier.
     * A member who is REGULAR only through vouches cannot vouch for others until they earn it through points.
     */
    fun computeAll(
        members: Collection<MemberId>,
        cfg: CircleConfig,
        events: List<StandingEvent>,
        vouches: List<Vouch>,
        today: Day
    ): Map<MemberId, Standing> {
        val baseTier = members.associateWith {
            compute(it, cfg, events, emptyList(), { Tier.NEW }, today).tier
        }
        return members.associateWith {
            compute(it, cfg, events, vouches, { m -> baseTier[m] ?: Tier.NEW }, today)
        }
    }

    /**
     * Sponsor shares a little of the risk: a vouchee's serious negative event nudges each live voucher once.
     * Uses the incident's own day, not today, so every device folds to the same result (C2).
     */
    fun sponsorNudges(event: StandingEvent, vouches: List<Vouch>, cfg: CircleConfig): List<StandingEvent> =
        if (!event.kind.isIncident) emptyList()
        else vouches
            .filter { it.vouchee == event.member && it.circle == event.circle && it.isLiveOn(event.day, cfg) }
            .map { it.voucher }
            .distinct()
            .map { voucher ->
                StandingEvent(
                    member = voucher,
                    circle = event.circle,
                    kind = EventKind.RETURNED_LATE, // deliberately mild
                    day = event.day,
                    sourceId = "sponsor:${event.sourceId}"
                )
            }
}

// ---------- Access decisions ----------

sealed interface AccessDecision {
    val reasons: List<String>

    /** Eligible. The owner still has to say yes. */
    data class Eligible(override val reasons: List<String>) : AccessDecision

    /** Not yet. Says what would change that, so it is a path, not a wall. */
    data class NotYet(override val reasons: List<String>, val howToUnlock: List<String>) : AccessDecision

    /** Paused by circle review (rare). Always names the appeal route. */
    data class Paused(override val reasons: List<String>, val untilDay: Day) : AccessDecision

    /** The borrower owns the item (C3). */
    data class OwnItem(override val reasons: List<String>) : AccessDecision
}

sealed interface PauseResult {
    data class Placed(val pause: Pause) : PauseResult
    data class Rejected(val reason: String) : PauseResult
}

object AccessPolicy {

    fun canBorrow(
        borrower: MemberId,
        item: Item,
        circle: CircleConfig,
        standing: Standing,
        pauses: List<Pause>,
        today: Day
    ): AccessDecision {
        if (circle.id !in item.circles) {
            return AccessDecision.NotYet(
                listOf("${item.name} is not shared in ${circle.name}."),
                listOf("Ask the owner to share it with this circle.")
            )
        }

        // 1. Your own item (C3).
        if (borrower == item.owner) {
            return AccessDecision.OwnItem(listOf("${item.name} is yours."))
        }

        // 2. Essentials are always open to members, even during a pause or cooldown. Need is not a reward.
        if (item.itemClass == ItemClass.ESSENTIAL) {
            return AccessDecision.Eligible(listOf("Essentials are open to every member of the circle."))
        }

        // 3. Pause check applies to everything except essentials.
        val pause = pauses.firstOrNull {
            it.member == borrower && it.circle == circle.id && today >= it.fromDay && today < it.untilDay
        }
        if (pause != null) {
            return AccessDecision.Paused(
                listOf(
                    "Access is paused for ${pause.untilDay - today} more days: ${pause.reason}",
                    "Placed by ${pause.reviewers.size} reviewers. Appeal ${if (pause.appealOpen) "is open" else "has closed"}."
                ),
                pause.untilDay
            )
        }

        // 4. Incident cooldown holds higher-value items only (C7).
        val cooldownEnds = standing.incidentUntilDay
        if (item.itemClass.heldDuringCooldown && cooldownEnds != null && cooldownEnds > today) {
            val daysLeft = cooldownEnds - today
            return AccessDecision.NotYet(
                standing.reasons + "${item.name} is on hold for $daysLeft more days after an unreported incident.",
                listOf(
                    "Everyday items and essentials are still open to you.",
                    "The hold ends in $daysLeft days; it is a pause, not a ban."
                )
            )
        }

        // 5. Tier check. Owner may raise the bar for their own item, never lower it.
        val needed = maxOf(item.itemClass.minTier, item.ownerMinTier ?: Tier.NEW, compareBy { it.rank })
        if (standing.tier.rank >= needed.rank) {
            return AccessDecision.Eligible(
                standing.reasons + "Your tier (${standing.tier}) meets what ${item.name} needs ($needed)."
            )
        }

        val gap = (circle.thresholds[needed] ?: 0.0) - standing.points
        return AccessDecision.NotYet(
            standing.reasons + "${item.name} needs $needed; you are ${standing.tier}.",
            buildList {
                add("Borrow and return everyday items; about ${format1(gap.coerceAtLeast(0.0))} more points opens it.")
                // Vouching only opens REGULAR (C4).
                if (needed.rank <= Tier.REGULAR.rank) {
                    add("Or get ${circle.vouchesForRegular} members of tier ${circle.voucherMinTier} or above to vouch for you.")
                }
                add("Or offer a skill share or help at a circle event.")
            }
        )
    }

    /**
     * Pausing needs enough distinct, trusted reviewers (not the subject), an expiry, and a reason (C9).
     * [tierOf] gives each reviewer's tier.
     */
    fun tryPause(
        member: MemberId,
        cfg: CircleConfig,
        reason: String,
        reviewers: Set<MemberId>,
        tierOf: (MemberId) -> Tier,
        today: Day,
        durationDays: Int = cfg.defaultPauseDays
    ): PauseResult {
        val qualified = reviewers.filter { it != member && tierOf(it).rank >= cfg.pauseReviewerMinTier.rank }.toSet()
        return when {
            reason.isBlank() -> PauseResult.Rejected("A pause needs a stated reason.")
            durationDays <= 0 -> PauseResult.Rejected("A pause needs an expiry.")
            qualified.size < cfg.reviewersForPause -> PauseResult.Rejected(
                "A pause needs ${cfg.reviewersForPause} reviewers of tier ${cfg.pauseReviewerMinTier} or above, " +
                    "not counting the member; ${qualified.size} qualify."
            )
            else -> PauseResult.Placed(Pause(member, cfg.id, reason, qualified, today, today + durationDays))
        }
    }
}

// ---------- Formatting (no String.format in common Kotlin, C10) ----------

/** One decimal place, rounding ties up, the same on JVM and JS. */
fun format1(x: Double): String {
    val tenths = floor(abs(x) * TENTHS_SCALE + HALF).toLong()
    val sign = if (x < 0 && tenths > 0) "-" else ""
    return "$sign${tenths / 10}.${tenths % 10}"
}
