package circles.event

import circles.crypto.Cipher
import circles.encoding.ByteString
import circles.encoding.CborMap
import circles.encoding.Parsed
import circles.policy.CircleId
import circles.policy.ItemClass
import circles.policy.ItemId
import circles.policy.MemberId
import circles.policy.Tier

enum class LoanStatus { REQUESTED, DECLINED, APPROVED, ACTIVE, RETURNED, CANCELLED }

/** An item as the log has shaped it. [keyWraps] holds the owner's own item keys by `detailKeyId`; sealed detail likewise. */
data class ItemState(
    val item: ItemId,
    val owner: MemberId,
    val name: String,
    val itemClass: ItemClass,
    val minTier: Tier?,
    val detailKeyId: String,
    val retired: Boolean,
    val keyWraps: Map<String, Map<DeviceId, ByteString>>,
    val sealedDetails: Map<String, ByteString>,
    /** The item's photos as of its latest `item.upsert` (ADR 0060). */
    val photos: List<ItemPhoto> = emptyList(),
)

data class LoanState(
    val loan: LoanId,
    val item: ItemId,
    val borrower: MemberId,
    val owner: MemberId,
    val fromDay: Int,
    val toDay: Int,
    val status: LoanStatus,
    /** The item key the approval wrapped to the borrower's devices, and those wraps. */
    val detailKeyId: String?,
    val keyWraps: Map<DeviceId, ByteString>,
    val ownerConfirmedHandover: Boolean,
    val borrowerConfirmedHandover: Boolean,
    val outcome: ReturnOutcome?,
    /** The day of the borrower's `loan.return`, which starts the grace period before it counts. */
    val returnedDay: Int? = null,
    /** The borrower's note on the request, if any. */
    val requestNote: String? = null,
    /** The day the owner confirmed the return, if they did (ADR 0043). */
    val confirmedDay: Int? = null,
    val dispute: DisputeState? = null,
    /** The dates the borrower asked for; [fromDay] and [toDay] are the agreed ones, which the owner may have changed (ADR 0061). */
    val requestedFromDay: Int = fromDay,
    val requestedToDay: Int = toDay,
)

/** An owner's dispute of a return, and how it ended. [resolvedDay] and [outcome] are null while it is pending. */
data class DisputeState(
    val claim: DisputeClaim,
    val note: String?,
    val day: Int,
    val outcome: DisputeOutcome? = null,
    val resolvedDay: Int? = null,
)

/** The stewards at one moment of the log: who, how many must agree, and when each joined (ADR 0051). */
class StewardFacts(val stewards: Set<MemberId>, val quorum: Int, val since: Map<MemberId, Int>)

/** A contribution note and whether the named person confirmed it (ADR 0053); it counts for [author] from [confirmedDay]. */
data class ContributionState(
    val note: NoteId,
    val author: MemberId,
    val with: MemberId,
    val kind: ContributionKind,
    val text: String,
    val day: Int,
    val confirmedDay: Int? = null,
)

/** A skill offer as the log has shaped it (ADR 0055). */
data class SkillState(val skill: SkillId, val owner: MemberId, val name: String, val details: String, val openTo: Set<OpenTo>, val retired: Boolean = false)

/** A note may be made at most this many times by one author in any [NOTE_WINDOW_DAYS] days, and is confirmed within that many days (ADR 0053). */
const val MAX_NOTES_PER_WINDOW = 3
const val NOTE_WINDOW_DAYS = 30

/** Days a pause proposal stays open for reviews (ADR 0050). */
const val PAUSE_PROPOSAL_WINDOW_DAYS = 14

/** A pause always needs at least two reviewers, so no one acts alone (ADR 0050). */
const val MIN_PAUSE_REVIEWERS = 2

/** A pause as the log has shaped it (ADR 0050). [reviews] holds each reviewer's latest answer. */
data class PauseState(
    val pause: PauseId,
    val subject: MemberId,
    val proposer: MemberId,
    val reason: String,
    val days: Int,
    val proposedDay: Int,
    val reviews: Map<MemberId, Boolean>,
    /** Reviewers needed, fixed when the pause took effect (or last worked out while it is only proposed). */
    val needed: Int,
    val liveFrom: Int? = null,
    val appealNote: String? = null,
    val appealDay: Int? = null,
    val liftedDay: Int? = null,
) {
    val untilDay: Int? get() = liveFrom?.plus(days)
    val agreeing: Set<MemberId> get() = reviews.filterValues { it }.keys

    /** Where the pause stands on [day]. */
    fun statusOn(day: Int): PauseStatus = when {
        liftedDay != null -> PauseStatus.LIFTED
        liveFrom != null -> if (day < liveFrom + days) PauseStatus.LIVE else PauseStatus.ENDED
        day > proposedDay + PAUSE_PROPOSAL_WINDOW_DAYS -> PauseStatus.LAPSED
        else -> PauseStatus.PROPOSED
    }
}

enum class PauseStatus { PROPOSED, LIVE, LIFTED, ENDED, LAPSED }

/** Items, loans and what this device can read of them. */
class MarketView(
    val items: Map<ItemId, ItemState>,
    val loans: Map<LoanId, LoanState>,
    /** Item keys this device holds, by item and `detailKeyId`. */
    val itemKeys: Map<Pair<ItemId, String>, ByteString>,
    /** The current detail of each item this device can read, or why it cannot. Items without detail are absent. */
    val details: Map<ItemId, Parsed<CborMap>>,
    /** Vouches that passed the rules (ADR 0042), in log order. */
    val vouches: List<circles.policy.Vouch> = emptyList(),
    /** Members who have left or been removed (every device revoked), and the day they went. */
    val departed: Map<MemberId, Int> = emptyMap(),
    /** Pauses (ADR 0050), in the order they were proposed. */
    val pauses: List<PauseState> = emptyList(),
    /** Circle rules that took effect (ADR 0051), earliest first, and the proposals still short of enough votes. */
    val configs: List<ConfigVersion> = emptyList(),
    val pendingConfigs: List<PendingConfig> = emptyList(),
    /** The defaults every circle starts from; [configOn] applies the versions on top. */
    val baseConfig: circles.policy.CircleConfig? = null,
    /** Contribution notes (ADR 0053), confirmed or not, in the order they were made. */
    val contributions: List<ContributionState> = emptyList(),
    /** Skill offers (ADR 0055), retired ones included, in the order they were first made. */
    val skills: List<SkillState> = emptyList(),
    /** Each member's current display name and its confirmations (ADR 0068). */
    val names: Map<MemberId, NameState> = emptyMap(),
    /** When each member's identity counted as confirmed, as stretches of the log (ADR 0079). */
    val identityIntervals: Map<MemberId, List<IdentityInterval>> = emptyMap(),
) {
    /** Who confirmed [member]'s current name and still counts: confirmers who have since left do not (ADR 0068). */
    fun nameConfirmers(member: MemberId): Set<MemberId> =
        names[member]?.confirmations?.keys?.filterTo(mutableSetOf()) { it !in departed }.orEmpty()

    /** True once enough current members confirmed [member]'s current name. */
    fun nameConfirmed(member: MemberId): Boolean = nameConfirmers(member).size >= NAME_CONFIRMATIONS_NEEDED

    /** The circle's rules for a decision dated [day]: the newest version that had taken effect by then (ADR 0051, C1). */
    fun configOn(circle: CircleId, day: Int): circles.policy.CircleConfig {
        val base = baseConfig ?: StandingRules.defaultConfig(circle)
        return configs.lastOrNull { it.fromDay <= day }?.settings?.applyTo(base) ?: base
    }

    /** The pauses in force on [day], in the form the access rule reads. */
    fun livePausesOn(circle: CircleId, day: Int): List<circles.policy.Pause> =
        pauses.filter { it.statusOn(day) == PauseStatus.LIVE }.map {
            circles.policy.Pause(it.subject, circle, it.reason, it.agreeing, it.liveFrom!!, it.untilDay!!, appealOpen = it.appealNote == null)
        }

    /** The vouches that still count for a decision dated [day]: those by someone who has left stop counting from the day they left (ADR 0044). */
    fun vouchesOn(day: Int): List<circles.policy.Vouch> = vouches.filter { vouch -> departed[vouch.voucher]?.let { day < it } ?: true }
}

internal class MarketResult(val view: MarketView, val rejected: Map<EventId, String>)

private val MARKET_TYPES = setOf(
    EventType.ITEM_UPSERT, EventType.ITEM_RETIRE, EventType.ITEM_RESTORE, EventType.ITEM_DETAIL,
    EventType.LOAN_REQUEST, EventType.LOAN_RESPOND, EventType.LOAN_HANDOVER, EventType.LOAN_RETURN, EventType.LOAN_CANCEL, EventType.VOUCH,
    EventType.STANDING_CONFIRM, EventType.LOAN_DISPUTE, EventType.LOAN_RESOLVE,
    EventType.PAUSE_PROPOSE, EventType.PAUSE_REVIEW, EventType.PAUSE_APPEAL, EventType.PAUSE_LIFT, EventType.CONFIG_UPDATE, EventType.STANDING_NOTE,
    EventType.SKILL_UPSERT, EventType.SKILL_RETIRE, EventType.MEMBER_PROFILE, EventType.MEMBER_NAME_CONFIRM,
)

internal fun isMarketType(type: String): Boolean = EventType.fromWire(type) in MARKET_TYPES

/**
 * Folds item and loan events in log order (ADR 0025). [events] are the structurally valid, already-opened events.
 * Events whose payload cannot be opened have no effect and are not rejected. Access policy plugs in through [mayBorrow].
 */
internal class MarketFold(
    private val cipher: Cipher,
    private val me: DeviceIdentity?,
    private val circle: CircleId,
    private val devices: Map<DeviceId, DeviceState>,
    private val mayBorrow: (MemberId, ItemId) -> Boolean,
    /** The stewards at the moment of each event (ADR 0042: the role floor uses the set as it was). */
    private val stewardsAt: (EventId) -> Set<MemberId> = { emptySet() },
    private val baseConfig: circles.policy.CircleConfig = StandingRules.defaultConfig(circle),
    private val stewardFactsAt: (EventId) -> StewardFacts = { StewardFacts(emptySet(), DEFAULT_STEWARD_QUORUM, emptyMap()) },
) {
    private val vouches = mutableListOf<circles.policy.Vouch>()
    private val items = mutableMapOf<ItemId, ItemState>()
    private val loans = mutableMapOf<LoanId, LoanState>()
    private val pauses = linkedMapOf<PauseId, PauseState>()
    private val configs = mutableListOf<ConfigVersion>()
    private val notes = linkedMapOf<NoteId, ContributionState>()
    private val skills = linkedMapOf<SkillId, SkillState>()
    private val names = linkedMapOf<MemberId, NameState>()
    private val identityIntervals = mutableMapOf<MemberId, MutableList<IdentityInterval>>()

    private fun confirmedNotes(): List<ContributionState> = notes.values.filter { it.confirmedDay != null }
    private val configProposals = linkedMapOf<ConfigSettings, PendingConfig>()

    /** The circle's rules in force on [day] (ADR 0051). */
    private fun config(day: Int): circles.policy.CircleConfig =
        configs.lastOrNull { it.fromDay <= day }?.settings?.applyTo(baseConfig) ?: baseConfig
    private val rejected = mutableMapOf<EventId, String>()

    fun fold(events: List<OpenedEvent>): MarketResult {
        for (event in events) {
            if (!isMarketType(event.envelope.type)) continue
            val payload = (event.payload as? Parsed.Ok)?.value ?: continue // unreadable: no effect, no rejection
            val problem = apply(event.envelope, payload, event.id)
            if (problem != null) rejected[event.id] = problem
        }
        val keys = myItemKeys()
        return MarketResult(MarketView(items.toMap(), loans.toMap(), keys, openDetails(keys), vouches.toList(), devices.values.map { it.registration.member }.toSet().mapNotNull { member -> removedOn(member)?.let { member to it } }.toMap(), pauses.values.toList(), configs.toList(), configProposals.values.toList(), baseConfig, notes.values.toList(), skills.values.toList(), names.toMap(), identityIntervals.mapValues { it.value.toList() }), rejected.toMap())
    }

    private fun apply(e: Envelope, payload: CborMap, id: EventId): String? = when (EventType.fromWire(e.type)) {
        EventType.ITEM_UPSERT -> parse(MarketPayloads.parseItemUpsert(payload)) { upsert(e.author, it) }
        EventType.ITEM_RETIRE -> parse(MarketPayloads.parseItemRetire(payload)) { retire(e.author, it) }
        EventType.ITEM_RESTORE -> parse(MarketPayloads.parseItemRestore(payload)) { restore(e.author, it) }
        EventType.ITEM_DETAIL -> parse(MarketPayloads.parseItemDetail(payload)) { detail(e.author, it) }
        EventType.LOAN_REQUEST -> parse(MarketPayloads.parseLoanRequest(payload)) { request(e.author, it, e.day, id) }
        EventType.STANDING_CONFIRM -> if (payload.entries.containsKey("noteId")) parse(MarketPayloads.parseNoteConfirmation(payload)) { confirmNote(e.author, it, e.day) } else parse(MarketPayloads.parseReturnConfirmation(payload)) { confirm(e.author, it, e.day) }
        EventType.LOAN_DISPUTE -> parse(MarketPayloads.parseDispute(payload)) { dispute(e.author, it, e.day) }
        EventType.LOAN_RESOLVE -> parse(MarketPayloads.parseResolution(payload)) { resolve(e.author, it, e.day, id) }
        EventType.VOUCH -> parse(MarketPayloads.parseVouch(payload)) { vouch(e.author, it, e.day, id) }
        EventType.SKILL_UPSERT -> parse(MarketPayloads.parseSkillUpsert(payload)) { offerSkill(e.author, it) }
        EventType.SKILL_RETIRE -> parse(MarketPayloads.parseSkillRetire(payload)) { retireSkill(e.author, it) }
        EventType.MEMBER_PROFILE -> parse(NamePayloads.parseProfile(payload)) { setName(e.author, it, e.day, id) }
        EventType.MEMBER_NAME_CONFIRM -> parse(NamePayloads.parseConfirmation(payload)) { confirmName(e.author, it, e.day, id) }
        EventType.STANDING_NOTE -> parse(MarketPayloads.parseStandingNote(payload)) { noteContribution(e.author, it, e.day, id) }
        EventType.CONFIG_UPDATE -> parse(MarketPayloads.parseConfigUpdate(payload)) { configUpdate(e.author, it, e.day, id) }
        EventType.PAUSE_PROPOSE -> parse(MarketPayloads.parsePausePropose(payload)) { proposePause(e.author, it, e.day, id) }
        EventType.PAUSE_REVIEW -> parse(MarketPayloads.parsePauseReview(payload)) { reviewPause(e.author, it, e.day, id) }
        EventType.PAUSE_APPEAL -> parse(MarketPayloads.parsePauseAppeal(payload)) { appealPause(e.author, it, e.day) }
        EventType.PAUSE_LIFT -> parse(MarketPayloads.parsePauseLift(payload)) { liftPause(e.author, it, e.day, id) }
        EventType.LOAN_RESPOND -> parse(MarketPayloads.parseLoanRespond(payload)) { respond(e.author, it) }
        EventType.LOAN_CANCEL -> parse(MarketPayloads.parseLoanCancel(payload)) { cancel(e.author, it) }
        EventType.LOAN_HANDOVER -> parse(MarketPayloads.parseLoanHandover(payload)) { handover(e.author, it) }
        EventType.LOAN_RETURN -> parse(MarketPayloads.parseLoanReturn(payload)) { giveBack(e.author, it, e.day) }
        else -> null
    }

    private inline fun <T> parse(parsed: Parsed<T>, then: (T) -> String?): String? =
        when (parsed) {
            is Parsed.Ok -> then(parsed.value)
            is Parsed.Invalid -> parsed.reason
        }

    private fun upsert(author: MemberId, v: ItemUpsert): String? {
        // Wraps go to the owner's devices and, when the key is changed after a removal (ADR 0049), to members whose loan is still open.
        val mayHold = loans.values.filter { it.item == v.item && (it.status == LoanStatus.APPROVED || it.status == LoanStatus.ACTIVE) }.map { it.borrower }.toSet() + author
        if (v.keyWraps.any { devices[it.device]?.registration?.member !in mayHold }) return "keyWraps must go to the owner's own devices or to borrowers with an open loan"
        val existing = items[v.item]
        if (existing != null) {
            if (existing.owner != author) return "only the owner may change an item"
            if (existing.retired) return "item is retired"
        }
        val wraps = (existing?.keyWraps.orEmpty()) + (v.detailKeyId to (existing?.keyWraps?.get(v.detailKeyId).orEmpty() + v.keyWraps.associate { it.device to it.wrapped }))
        items[v.item] = ItemState(v.item, author, v.name, v.itemClass, v.minTier, v.detailKeyId, false, wraps, existing?.sealedDetails.orEmpty(), v.photos)
        return null
    }

    private fun retire(author: MemberId, v: ItemRetire): String? {
        val item = items[v.item] ?: return "unknown item"
        if (item.owner != author) return "only the owner may retire an item"
        if (item.retired) return "item is already retired"
        items[v.item] = item.copy(retired = true)
        return null
    }

    /** Only the owner, only an archived item. Everything else about the item (id, key, details, photos, loans) is as it was (ADR 0075). */
    private fun restore(author: MemberId, v: ItemRestore): String? {
        val item = items[v.item] ?: return "unknown item"
        if (item.owner != author) return "only the owner may restore an item"
        if (!item.retired) return "item is not archived"
        items[v.item] = item.copy(retired = false)
        return null
    }

    private fun detail(author: MemberId, v: ItemDetail): String? {
        val item = items[v.item] ?: return "unknown item"
        if (item.owner != author) return "only the owner may attach detail"
        if (item.retired) return "item is retired"
        if (v.detailKeyId != item.detailKeyId) return "detail is sealed under a key that is not the item's current one"
        items[v.item] = item.copy(sealedDetails = item.sealedDetails + (v.detailKeyId to v.sealed))
        return null
    }

    /** The day a member left or was removed: the latest revocation, once every device of theirs is revoked (ADR 0044). */
    private fun removedOn(member: MemberId): Int? {
        val own = devices.values.filter { it.registration.member == member }
        return if (own.isNotEmpty() && own.all { it.revoked }) own.mapNotNull { it.revokedDay }.maxOrNull() else null
    }

    /** Vouches by someone who has left stop counting from the day they left. */
    private fun vouchesCounting(day: Int): List<circles.policy.Vouch> =
        vouches.filter { vouch -> removedOn(vouch.voucher)?.let { day < it } ?: true }

    // ---- pauses (ADR 0050) ----

    private fun livePauses(day: Int): List<circles.policy.Pause> =
        pauses.values.filter { it.statusOn(day) == PauseStatus.LIVE }.map {
            circles.policy.Pause(it.subject, circle, it.reason, it.agreeing, it.liveFrom!!, it.untilDay!!, appealOpen = it.appealNote == null)
        }

    private fun members(): Set<MemberId> = devices.values.filter { !it.revoked }.map { it.registration.member }.toSet()

    /**
     * Who may propose or review a pause on [day]: the members whose standing reaches the configured tier. The inputs that
     * do not depend on the member (rules, counted vouches, stewards, confirmed notes) are worked out once, not per member,
     * so a pause event costs one pass over the circle instead of one per member.
     */
    private fun reviewers(day: Int, id: EventId): Set<MemberId> {
        val cfg = config(day)
        val counted = vouchesCounting(day)
        val stewards = stewardsAt(id)
        val notes = confirmedNotes()
        val loansNow = loans.values
        return members().filterTo(mutableSetOf()) { member ->
            StandingRules.standingOf(member, cfg, loansNow, counted, stewards, day, notes).tier.rank >= cfg.pauseReviewerMinTier.rank
        }
    }

    /** Reviewers needed for a pause of [subject]: the configured number capped at the eligible reviewers minus one, but at least two (and never more than the eligible reviewers). */
    private fun reviewersNeeded(subject: MemberId, day: Int, reviewers: Set<MemberId>): Int {
        val eligible = reviewers.count { it != subject }
        return minOf(eligible, maxOf(MIN_PAUSE_REVIEWERS, minOf(config(day).reviewersForPause, eligible - 1)))
    }

    // ---- skill offers (ADR 0055) ----

    private fun offerSkill(author: MemberId, v: SkillOffer): String? {
        if (author !in members()) return "only a member may offer a skill"
        val existing = skills[v.skill]
        if (existing != null) {
            if (existing.owner != author) return "only the author may change an offer"
            if (existing.retired) return "the offer has been retired"
        } else if (skills.values.count { it.owner == author && !it.retired } >= MAX_LIVE_SKILLS_PER_MEMBER) {
            return "at most $MAX_LIVE_SKILLS_PER_MEMBER live skill offers"
        }
        skills[v.skill] = SkillState(v.skill, author, v.name, v.details, v.openTo)
        return null
    }

    private fun retireSkill(author: MemberId, v: SkillRetire): String? {
        val skill = skills[v.skill] ?: return "unknown skill"
        if (skill.owner != author) return "only the author may retire an offer"
        if (skill.retired) return "the offer is already retired"
        skills[v.skill] = skill.copy(retired = true)
        return null
    }

    // ---- display names (ADR 0068) ----

    /** A member names themselves; a new name starts with no confirmations. */
    private fun setName(author: MemberId, v: MemberProfile, day: Int, id: EventId): String? {
        if (names[author]?.name == v.name) return "that is already the member's name"
        // ADR 0069: log order decides; a name is taken while another member who has not left holds the same key.
        val key = NameKey.of(v.name)
        if (names.any { (member, state) -> member != author && NameKey.of(state.name) == key && removedOn(member) == null }) return NAME_TAKEN_REASON
        names[author] = NameState(author, v.name, id, day)
        // a new name ends any stretch in which the member counted as confirmed
        identityIntervals[author]?.let { list -> if (list.isNotEmpty() && list.last().until == null) list[list.lastIndex] = list.last().copy(until = id) }
        return null
    }

    /**
     * A member at REGULAR or above (stewards count as TRUSTED) confirms another member's current name. The confirmation
     * names the profile event, so one made for an earlier name, or for the same text set again, is stale and refused.
     */
    private fun confirmName(author: MemberId, v: NameConfirmation, day: Int, id: EventId): String? {
        if (v.member == author) return "a member cannot confirm their own identity"
        val current = names[v.member] ?: return "that member has not set a name"
        if (v.profile != current.profile || v.name != current.name) return "the name has changed since"
        if (author in current.confirmations) return "already confirmed"
        val tier = StandingRules.standingOf(author, config(day), loans.values, vouchesCounting(day), stewardsAt(id), day, confirmedNotes()).tier
        if (author !in stewardsAt(id) && tier.rank < Tier.REGULAR.rank) return "only a member at ${circles.policy.TierText.orAbove(Tier.REGULAR)}, or a steward, may confirm someone's identity"
        val confirmations = current.confirmations + (author to day)
        names[v.member] = current.copy(
            confirmations = confirmations,
            confirmedAt = current.confirmedAt ?: id.takeIf { confirmations.size >= NAME_CONFIRMATIONS_NEEDED },
        )
        if (current.confirmedAt == null && confirmations.size >= NAME_CONFIRMATIONS_NEEDED) identityIntervals.getOrPut(v.member) { mutableListOf() } += IdentityInterval(id, null)
        return null
    }

    // ---- contribution notes (ADR 0053) ----

    private fun noteContribution(author: MemberId, v: StandingNote, day: Int, id: EventId): String? {
        if (notes.containsKey(v.note)) return "note id already used"
        if (v.with == author) return "a note must name someone else"
        if (author !in members() || v.with !in members()) return "both people must be in the circle"
        if (notes.values.count { it.author == author && it.day > day - NOTE_WINDOW_DAYS && it.day <= day } >= MAX_NOTES_PER_WINDOW) return "at most $MAX_NOTES_PER_WINDOW contribution notes in $NOTE_WINDOW_DAYS days"
        if (v.kind == ContributionKind.MENTORED_NEW_MEMBER &&
            StandingRules.standingOf(v.with, config(day), loans.values, vouchesCounting(day), stewardsAt(id), day, confirmedNotes()).tier != circles.policy.Tier.NEW
        ) return "only a new member can be mentored"
        notes[v.note] = ContributionState(v.note, author, v.with, v.kind, v.text, day)
        return null
    }

    private fun confirmNote(author: MemberId, v: NoteConfirmation, day: Int): String? {
        val note = notes[v.note] ?: return "unknown note"
        if (note.with != author) return "only the person named can confirm"
        if (note.confirmedDay != null) return "already confirmed"
        if (day > note.day + NOTE_WINDOW_DAYS) return "the note has lapsed"
        notes[v.note] = note.copy(confirmedDay = maxOf(day, note.day))
        return null
    }

    // ---- circle rules (ADR 0051) ----

    private fun configUpdate(author: MemberId, v: ConfigSettings, day: Int, id: EventId): String? {
        val facts = stewardFactsAt(id)
        if (author !in facts.stewards) return "only a steward may change the circle's rules"
        val since = facts.since[author]
        if (facts.quorum > 1 && since != null && since != FOUNDING_STEWARD_DAY && day < since + DEFAULT_STEWARD_COOL_OFF_DAYS) return "A new steward cannot vote until ${circles.app.ReasonDates.token(since + DEFAULT_STEWARD_COOL_OFF_DAYS)}."
        v.problem(baseConfig)?.let { return it }
        if (v == ConfigSettings.of(config(day))) return "those are already the circle's rules"
        val open = configProposals[v]?.takeIf { day <= it.firstDay + DEFAULT_STEWARD_PROPOSAL_DAYS }
        val proposal = (open ?: PendingConfig(v, emptySet(), day)).let { it.copy(voters = it.voters + author) }
        // Only votes from people who are stewards at this moment count: a steward who has since left does not make up a quorum.
        if (proposal.voters.count { it in facts.stewards } >= minOf(facts.quorum, facts.stewards.size)) {
            configs += ConfigVersion(day, v)
            configs.sortBy { it.fromDay }
            configProposals.remove(v)
        } else {
            configProposals[v] = proposal
        }
        return null
    }

    private fun proposePause(author: MemberId, v: PauseProposal, day: Int, id: EventId): String? {
        if (pauses.containsKey(v.pause)) return "pause id already used"
        val eligible = reviewers(day, id)
        if (author !in eligible) return "only a member at ${circles.policy.TierText.orAbove(config(day).pauseReviewerMinTier)} may propose a pause"
        if (v.subject == author) return "a member cannot propose pausing themselves"
        if (v.subject !in members()) return "the subject is not in the circle"
        if (v.subject in stewardsAt(id)) return "a steward is not paused: the steward rules apply"
        if (pauses.values.any { it.subject == v.subject && it.statusOn(day).let { s -> s == PauseStatus.PROPOSED || s == PauseStatus.LIVE } }) return "that member already has a pause or a proposal"
        val needed = reviewersNeeded(v.subject, day, eligible)
        if (needed < MIN_PAUSE_REVIEWERS) return "the circle has too few members at ${circles.policy.TierText.orAbove(config(day).pauseReviewerMinTier)} to review a pause"
        pauses[v.pause] = PauseState(v.pause, v.subject, author, v.reason, v.days, day, mapOf(author to true), needed)
        return null
    }

    private fun reviewPause(author: MemberId, v: PauseReview, day: Int, id: EventId): String? {
        val pause = pauses[v.pause] ?: return "unknown pause"
        if (author == pause.subject) return "the subject cannot review their own pause"
        if (pause.liftedDay != null) return "the pause has been lifted"
        val status = pause.statusOn(day)
        if (status == PauseStatus.LAPSED || status == PauseStatus.ENDED) return "the pause is over"
        val eligible = reviewers(day, id)
        if (author !in eligible) return "only a member at ${circles.policy.TierText.orAbove(config(day).pauseReviewerMinTier)} may review"
        if (pause.liveFrom != null) {
            // Once in force, only a reviewer who agreed may step back; stepping back below the number needed ends the pause.
            if (v.agree || author !in pause.agreeing) return "the pause is already in force"
            val remaining = pause.reviews + (author to false)
            val lifted = remaining.count { it.value } < pause.needed
            pauses[v.pause] = pause.copy(reviews = remaining, liftedDay = if (lifted) day else null)
            return null
        }
        val reviews = pause.reviews + (author to v.agree)
        val needed = reviewersNeeded(pause.subject, day, eligible)
        val live = needed >= MIN_PAUSE_REVIEWERS && reviews.count { it.value } >= needed
        pauses[v.pause] = pause.copy(reviews = reviews, needed = needed, liveFrom = if (live) day else null)
        return null
    }

    private fun appealPause(author: MemberId, v: PauseAppeal, day: Int): String? {
        val pause = pauses[v.pause] ?: return "unknown pause"
        if (author != pause.subject) return "only the subject may appeal"
        if (pause.appealNote != null) return "the subject has already appealed"
        val status = pause.statusOn(day)
        if (status != PauseStatus.PROPOSED && status != PauseStatus.LIVE) return "the pause is over"
        pauses[v.pause] = pause.copy(appealNote = v.note, appealDay = day)
        return null
    }

    private fun liftPause(author: MemberId, v: PauseLift, day: Int, id: EventId): String? {
        val pause = pauses[v.pause] ?: return "unknown pause"
        val status = pause.statusOn(day)
        if (status != PauseStatus.PROPOSED && status != PauseStatus.LIVE) return "the pause is already over"
        val byReviewerAfterAppeal = author in pause.agreeing && pause.appealNote != null
        val bySteward = author in stewardsAt(id) && author != pause.proposer
        val byProposerBeforeEffect = author == pause.proposer && pause.liveFrom == null
        if (!byReviewerAfterAppeal && !bySteward && !byProposerBeforeEffect) return "only a reviewer after an appeal, another steward, or the proposer before it takes effect may lift a pause"
        pauses[v.pause] = pause.copy(liftedDay = day)
        return null
    }

    private fun vouch(author: MemberId, v: VouchTerms, day: Int, id: EventId): String? {
        if (v.vouchee == author) return "a member cannot vouch for themselves"
        if (devices.values.none { it.registration.member == v.vouchee }) return "the vouchee is not in the circle"
        if (!StandingRules.mayVouch(author, config(day), loans.values, vouchesCounting(day), stewardsAt(id), day, confirmedNotes())) return "only a member at ${circles.policy.TierText.orAbove(config(day).voucherMinTier)} may vouch"
        vouches += circles.policy.Vouch(author, v.vouchee, circle, day, v.expiresDay)
        return null
    }

    private fun request(author: MemberId, v: LoanRequest, day: Int, id: EventId): String? {
        val item = items[v.item] ?: return "unknown item"
        if (item.retired) return "item is retired"
        if (item.owner == author) return "a member cannot borrow their own item"
        if (loans.containsKey(v.loan)) return "loan id already used"
        if (!mayBorrow(author, v.item)) return "member may not borrow this item yet"
        if (bookedOver(v.item, v.fromDay, v.toDay, except = null)) return "the item is already promised for some of those days"
        val decision = StandingRules.decide(author, item, config(day), loans.values, vouchesCounting(day), stewardsAt(id), day, livePauses(day), confirmedNotes())
        if (decision !is circles.policy.AccessDecision.Eligible) return "access check: " + (decision.reasons + ((decision as? circles.policy.AccessDecision.NotYet)?.howToUnlock.orEmpty())).joinToString(" ")
        loans[v.loan] = LoanState(v.loan, v.item, author, item.owner, v.fromDay, v.toDay, LoanStatus.REQUESTED, null, emptyMap(), false, false, null, requestNote = v.note)
        return null
    }

    private fun respond(author: MemberId, v: LoanRespond): String? {
        val loan = loans[v.loan] ?: return "unknown loan"
        if (loan.owner != author) return "only the item's owner may respond"
        val item = items.getValue(loan.item)
        when (loan.status) {
            LoanStatus.REQUESTED -> Unit
            LoanStatus.APPROVED, LoanStatus.ACTIVE -> {
                // A second approval only adds wraps for the borrower's newer devices.
                if (!v.approve || v.fromDay != loan.fromDay || v.toDay != loan.toDay) return "the loan is already answered"
            }
            else -> return "the loan is already answered"
        }
        if (!v.approve) {
            loans[v.loan] = loan.copy(status = LoanStatus.DECLINED)
            return null
        }
        if (loan.status == LoanStatus.REQUESTED && bookedOver(loan.item, v.fromDay, v.toDay, except = loan.loan)) return "the item is already promised for some of those days"
        if (v.keyWraps.any { devices[it.device]?.registration?.member != loan.borrower }) return "keyWraps must go to the borrower's devices"
        val keyId = loan.detailKeyId ?: item.detailKeyId
        loans[v.loan] = loan.copy(
            status = if (loan.status == LoanStatus.REQUESTED) LoanStatus.APPROVED else loan.status,
            fromDay = v.fromDay,
            toDay = v.toDay,
            detailKeyId = keyId,
            keyWraps = loan.keyWraps + v.keyWraps.associate { it.device to it.wrapped },
        )
        return null
    }

    /** Is the item already promised (approved or out) for any day of the window? Asking is allowed over other requests (ADR 0052). */
    private fun bookedOver(item: ItemId, fromDay: Int, toDay: Int, except: LoanId?): Boolean =
        loans.values.any {
            it.item == item && it.loan != except && (it.status == LoanStatus.APPROVED || it.status == LoanStatus.ACTIVE) && it.fromDay <= toDay && fromDay <= it.toDay
        }

    private fun cancel(author: MemberId, v: LoanCancel): String? {
        val loan = loans[v.loan] ?: return "unknown loan"
        if (loan.borrower != author) return "only the borrower may cancel"
        if (loan.status != LoanStatus.REQUESTED && loan.status != LoanStatus.APPROVED) return "only a request or an approval before the hand-over can be cancelled"
        loans[v.loan] = loan.copy(status = LoanStatus.CANCELLED)
        return null
    }

    private fun handover(author: MemberId, v: LoanHandover): String? {
        val loan = loans[v.loan] ?: return "unknown loan"
        if (loan.status != LoanStatus.APPROVED) return "hand-over needs an approved loan that is not yet active"
        val updated = when (author) {
            loan.owner -> if (loan.ownerConfirmedHandover) return "already confirmed" else loan.copy(ownerConfirmedHandover = true)
            loan.borrower -> if (loan.borrowerConfirmedHandover) return "already confirmed" else loan.copy(borrowerConfirmedHandover = true)
            else -> return "only the owner or the borrower may confirm a hand-over"
        }
        loans[v.loan] = if (updated.ownerConfirmedHandover && updated.borrowerConfirmedHandover) updated.copy(status = LoanStatus.ACTIVE) else updated
        return null
    }

    private fun confirm(author: MemberId, v: ReturnConfirmation, day: Int): String? {
        val loan = loans[v.loan] ?: return "unknown loan"
        if (loan.owner != author) return "only the item's owner confirms a return"
        if (loan.status != LoanStatus.RETURNED) return "only a returned loan can be confirmed"
        if (loan.outcome != ReturnOutcome.OK && loan.outcome != ReturnOutcome.LATE) return "a reported problem needs no confirmation"
        if (loan.confirmedDay != null) return "already confirmed"
        if (loan.dispute != null) return "the return is in dispute"
        loans[v.loan] = loan.copy(confirmedDay = maxOf(day, loan.returnedDay ?: day))
        return null
    }

    private fun dispute(author: MemberId, v: LoanDispute, day: Int): String? {
        val loan = loans[v.loan] ?: return "unknown loan"
        if (loan.owner != author) return "only the item's owner may dispute a return"
        if (loan.status != LoanStatus.RETURNED) return "only a returned loan can be disputed"
        if (loan.outcome != ReturnOutcome.OK && loan.outcome != ReturnOutcome.LATE) return "the borrower already reported a problem"
        if (loan.dispute != null) return "the return is already disputed"
        val returned = loan.returnedDay ?: return "the return has no day"
        if (day < returned || day - returned > DAMAGE_CLAIM_DAYS) return "a dispute must come within $DAMAGE_CLAIM_DAYS days of the return"
        loans[v.loan] = loan.copy(dispute = DisputeState(v.claim, v.note, day))
        return null
    }

    private fun resolve(author: MemberId, v: LoanResolution, day: Int, id: EventId): String? {
        val loan = loans[v.loan] ?: return "unknown loan"
        val dispute = loan.dispute ?: return "the return is not in dispute"
        if (dispute.outcome != null) return "the dispute is already resolved"
        if (author !in stewardsAt(id)) return "only a steward may resolve a dispute"
        if (author == loan.owner || author == loan.borrower) return "a party to the loan cannot resolve its dispute"
        loans[v.loan] = loan.copy(dispute = dispute.copy(outcome = v.outcome, resolvedDay = maxOf(day, dispute.day)))
        return null
    }

    private fun giveBack(author: MemberId, v: LoanReturn, day: Int): String? {
        val loan = loans[v.loan] ?: return "unknown loan"
        if (loan.borrower != author) return "only the borrower reports a return"
        if (loan.status != LoanStatus.ACTIVE) return "only an active loan can be returned"
        loans[v.loan] = loan.copy(status = LoanStatus.RETURNED, outcome = v.outcome, returnedDay = day)
        return null
    }

    /** Opens the item keys wrapped to this device: as owner (in `item.upsert`) and as approved borrower (in `loan.respond`). */
    private fun myItemKeys(): Map<Pair<ItemId, String>, ByteString> {
        val mine = me ?: return emptyMap()
        val keys = mutableMapOf<Pair<ItemId, String>, ByteString>()
        fun learn(item: ItemId, keyId: String, wrapped: ByteString?) {
            val opened = wrapped?.let { ItemKeys.unwrap(cipher, mine.boxKeys, circle, item.v, it) } as? Parsed.Ok ?: return
            keys[item to keyId] = opened.value
        }
        items.values.forEach { item -> item.keyWraps.forEach { (keyId, wraps) -> learn(item.item, keyId, wraps[mine.device]) } }
        loans.values.filter { it.borrower == mine.member && it.detailKeyId != null }.forEach { learn(it.item, it.detailKeyId!!, it.keyWraps[mine.device]) }
        return keys
    }

    private fun openDetails(keys: Map<Pair<ItemId, String>, ByteString>): Map<ItemId, Parsed<CborMap>> =
        items.values.mapNotNull { item ->
            val sealed = item.sealedDetails[item.detailKeyId] ?: return@mapNotNull null
            val key = keys[item.item to item.detailKeyId]
            item.item to (key?.let { ItemKeys.openDetail(cipher, it, circle, item.item.v, sealed) } ?: Parsed.Invalid("no key for this item's detail"))
        }.toMap()
}
