package circles.event

import circles.encoding.ByteString
import circles.encoding.CborArray
import circles.encoding.CborBool
import circles.encoding.CborBytes
import circles.encoding.CborInt
import circles.encoding.CborMap
import circles.encoding.CborText
import circles.encoding.CborValue
import circles.encoding.Parsed
import circles.policy.ItemClass
import circles.policy.ItemId
import circles.policy.MemberId
import circles.policy.Tier

/** Limits of ADR 0025. */
const val MAX_ITEM_NAME_CHARS = 80
const val MAX_LOAN_NOTE_CHARS = 256
const val MAX_LOAN_DAYS = 90
const val MAX_KEY_WRAPS = 16
const val MAX_ITEM_DETAIL_BYTES = 16 * 1024
private const val MAX_DAY = 1_000_000L

/** A loan's id: random 128-bit lowercase hex, made by the borrower. */
data class LoanId(val v: String)

enum class ReturnOutcome { OK, LATE, DAMAGED_REPORTED, LOST_REPORTED }

/** An item key sealed to one device (ADR 0025). */
data class DeviceKeyWrap(val device: DeviceId, val wrapped: ByteString)

data class ItemUpsert(
    val item: ItemId,
    val name: String,
    val itemClass: ItemClass,
    val minTier: Tier?,
    val detailKeyId: String,
    val keyWraps: List<DeviceKeyWrap>,
)

data class ItemRetire(val item: ItemId)

data class ItemDetail(val item: ItemId, val detailKeyId: String, val sealed: ByteString)

data class LoanRequest(val loan: LoanId, val item: ItemId, val fromDay: Int, val toDay: Int, val note: String?)

data class LoanRespond(val loan: LoanId, val approve: Boolean, val fromDay: Int, val toDay: Int, val note: String?, val keyWraps: List<DeviceKeyWrap>)

data class LoanHandover(val loan: LoanId)

/** `loan.cancel`: the borrower withdraws a request, or an approval before the hand-over (ADR 0052). */
data class LoanCancel(val loan: LoanId)

/** What the owner says went wrong that the borrower did not report. */
enum class DisputeClaim { DAMAGE, LOSS }

enum class DisputeOutcome { UPHELD, DISMISSED }

data class ReturnConfirmation(val loan: LoanId)

/** The contributions that can be noted (ADR 0053), each worth the library's points for the matching standing event. */
enum class ContributionKind(val standing: circles.policy.EventKind) {
    SKILL_SHARE_HELPED(circles.policy.EventKind.SKILL_SHARE_HELPED),
    HOSTED_OR_ORGANISED(circles.policy.EventKind.HOSTED_OR_ORGANISED),
    MENTORED_NEWCOMER(circles.policy.EventKind.MENTORED_NEWCOMER),
}

/** A note's id: random 128-bit lowercase hex, made by its author. */
data class NoteId(val v: String)

/** `standing.note`: [with] confirms that the author did [kind] for or with them; it counts only once confirmed (ADR 0053). */
data class StandingNote(val note: NoteId, val kind: ContributionKind, val with: MemberId, val text: String)

/** `standing.confirm` for a note (the same event type also confirms a return). */
data class NoteConfirmation(val note: NoteId)

const val MAX_NOTE_TEXT_CHARS = 140

data class LoanDispute(val loan: LoanId, val claim: DisputeClaim, val note: String?)

data class LoanResolution(val loan: LoanId, val outcome: DisputeOutcome)

/** A member vouches for another until [expiresDay] (the policy caps it; ADR 0042). */
data class VouchTerms(val vouchee: MemberId, val expiresDay: Int)

data class LoanReturn(val loan: LoanId, val outcome: ReturnOutcome)

/** A pause's id: random 128-bit lowercase hex, made by the proposer (ADR 0050). */
data class PauseId(val v: String)

/** `pause.propose`: ask for [subject]'s access (other than essentials) to be paused for [days] days. */
data class PauseProposal(val pause: PauseId, val subject: MemberId, val reason: String, val days: Int)

/** `pause.review`: a reviewer's [agree] or, with false, a withdrawal of an earlier agreement. */
data class PauseReview(val pause: PauseId, val agree: Boolean)

/** `pause.appeal`: the subject's one note to the reviewers. */
data class PauseAppeal(val pause: PauseId, val note: String)

/** `pause.lift`: ends a pause or cancels a proposal. */
data class PauseLift(val pause: PauseId)

const val MAX_PAUSE_REASON_CHARS = 280
const val MAX_PAUSE_APPEAL_CHARS = 500
const val MIN_PAUSE_DAYS = 1
const val MAX_PAUSE_DAYS = 90

private const val K_ITEM = "item"
private const val K_NAME = "name"
private const val K_CLASS = "class"
private const val K_MIN_TIER = "minTier"
private const val K_DETAIL_KEY = "detailKeyId"
private const val K_KEY_WRAPS = "keyWraps"
private const val K_SEALED = "sealed"
private const val K_LOAN = "loan"
private const val K_FROM = "fromDay"
private const val K_TO = "toDay"
private const val K_NOTE = "note"
private const val K_APPROVE = "approve"
private const val K_OUTCOME = "outcome"
private const val K_CLAIM = "claim"
private const val K_VOUCHEE = "vouchee"
private const val K_EXPIRES = "expiresDay"
private const val K_NEIGHBOUR = "neighbourTenths"
private const val K_TRUSTED = "trustedTenths"
private const val K_STEWARD_TIER = "stewardTenths"
private const val K_HALF_LIFE = "halfLifeDays"
private const val K_VOUCHES = "vouchesForNeighbour"
private const val K_VOUCH_VALID = "vouchValidityDays"
private const val K_REVIEWERS = "reviewersForPause"
private const val K_PAUSE_DAYS = "defaultPauseDays"
private const val K_COOLDOWN = "incidentCooldownDays"
private const val K_NOTE_ID = "noteId"
private const val K_KIND = "kind"
private const val K_WITH = "with"
private const val K_TEXT = "text"
private const val K_PAUSE = "pause"
private const val K_SUBJECT = "subject"
private const val K_REASON = "reason"
private const val K_DAYS = "days"
private const val K_AGREE = "agree"
private const val K_DEVICE = "device"
private const val K_WRAPPED = "wrapped"

/** Builders and strict readers for the payloads of ADR 0025. Readers refuse unknown keys, wrong types and out-of-range values. */
object MarketPayloads {
    fun itemUpsert(v: ItemUpsert): CborMap = CborMap(
        buildMap {
            put(K_ITEM, CborText(v.item.v))
            put(K_NAME, CborText(v.name))
            put(K_CLASS, CborText(v.itemClass.name))
            v.minTier?.let { put(K_MIN_TIER, CborText(it.name)) }
            put(K_DETAIL_KEY, CborText(v.detailKeyId))
            put(K_KEY_WRAPS, wraps(v.keyWraps))
        },
    )

    fun configUpdate(v: ConfigSettings): CborMap = CborMap.of(
        K_NEIGHBOUR to CborInt(v.neighbourTenths.toLong()), K_TRUSTED to CborInt(v.trustedTenths.toLong()), K_STEWARD_TIER to CborInt(v.stewardTenths.toLong()),
        K_HALF_LIFE to CborInt(v.halfLifeDays.toLong()), K_VOUCHES to CborInt(v.vouchesForNeighbour.toLong()), K_VOUCH_VALID to CborInt(v.vouchValidityDays.toLong()),
        K_REVIEWERS to CborInt(v.reviewersForPause.toLong()), K_PAUSE_DAYS to CborInt(v.defaultPauseDays.toLong()), K_COOLDOWN to CborInt(v.incidentCooldownDays.toLong()),
    )

    /** Reads the whole-number fields only; whether the values are acceptable is [ConfigSettings.problem]'s job. */
    fun parseConfigUpdate(map: CborMap): Parsed<ConfigSettings> {
        val names = listOf(K_NEIGHBOUR, K_TRUSTED, K_STEWARD_TIER, K_HALF_LIFE, K_VOUCHES, K_VOUCH_VALID, K_REVIEWERS, K_PAUSE_DAYS, K_COOLDOWN)
        keys(map, required = names.toSet())?.let { return Parsed.Invalid(it) }
        val n = names.map { name -> (map[name] as? CborInt)?.value?.takeIf { it in 0..MAX_DAY } ?: return Parsed.Invalid("$name must be a whole number") }.map { it.toInt() }
        return Parsed.Ok(ConfigSettings(n[0], n[1], n[2], n[3], n[4], n[5], n[6], n[7], n[8]))
    }

    fun standingNote(v: StandingNote): CborMap = CborMap.of(K_NOTE_ID to CborText(v.note.v), K_KIND to CborText(v.kind.name), K_WITH to CborText(v.with.v), K_TEXT to CborText(v.text))
    fun confirmNote(v: NoteConfirmation): CborMap = CborMap.of(K_NOTE_ID to CborText(v.note.v))

    fun parseStandingNote(map: CborMap): Parsed<StandingNote> {
        keys(map, required = setOf(K_NOTE_ID, K_KIND, K_WITH, K_TEXT))?.let { return Parsed.Invalid(it) }
        val note = randomId(map, K_NOTE_ID) ?: return Parsed.Invalid("noteId must be 32 lowercase hex characters")
        val kind = enumOf<ContributionKind>(map, K_KIND) ?: return Parsed.Invalid("kind is not a known contribution")
        val with = randomId(map, K_WITH) ?: return Parsed.Invalid("with must be 32 lowercase hex characters")
        val text = text(map, K_TEXT, MAX_NOTE_TEXT_CHARS) ?: return Parsed.Invalid("text must be at most $MAX_NOTE_TEXT_CHARS characters")
        return Parsed.Ok(StandingNote(NoteId(note), kind, MemberId(with), text))
    }

    fun parseNoteConfirmation(map: CborMap): Parsed<NoteConfirmation> {
        keys(map, required = setOf(K_NOTE_ID))?.let { return Parsed.Invalid(it) }
        return Parsed.Ok(NoteConfirmation(NoteId(randomId(map, K_NOTE_ID) ?: return Parsed.Invalid("noteId must be 32 lowercase hex characters"))))
    }

    fun pausePropose(v: PauseProposal): CborMap = CborMap.of(K_PAUSE to CborText(v.pause.v), K_SUBJECT to CborText(v.subject.v), K_REASON to CborText(v.reason), K_DAYS to CborInt(v.days.toLong()))
    fun pauseReview(v: PauseReview): CborMap = CborMap.of(K_PAUSE to CborText(v.pause.v), K_AGREE to CborBool(v.agree))
    fun pauseAppeal(v: PauseAppeal): CborMap = CborMap.of(K_PAUSE to CborText(v.pause.v), K_NOTE to CborText(v.note))
    fun pauseLift(v: PauseLift): CborMap = CborMap.of(K_PAUSE to CborText(v.pause.v))

    fun itemRetire(v: ItemRetire): CborMap = CborMap.of(K_ITEM to CborText(v.item.v))

    fun itemDetail(v: ItemDetail): CborMap =
        CborMap.of(K_ITEM to CborText(v.item.v), K_DETAIL_KEY to CborText(v.detailKeyId), K_SEALED to CborBytes(v.sealed))

    fun loanRequest(v: LoanRequest): CborMap = CborMap(
        buildMap {
            put(K_LOAN, CborText(v.loan.v))
            put(K_ITEM, CborText(v.item.v))
            put(K_FROM, CborInt(v.fromDay.toLong()))
            put(K_TO, CborInt(v.toDay.toLong()))
            v.note?.let { put(K_NOTE, CborText(it)) }
        },
    )

    fun loanRespond(v: LoanRespond): CborMap = CborMap(
        buildMap {
            put(K_LOAN, CborText(v.loan.v))
            put(K_APPROVE, CborBool(v.approve))
            put(K_FROM, CborInt(v.fromDay.toLong()))
            put(K_TO, CborInt(v.toDay.toLong()))
            v.note?.let { put(K_NOTE, CborText(it)) }
            if (v.keyWraps.isNotEmpty()) put(K_KEY_WRAPS, wraps(v.keyWraps))
        },
    )

    fun confirmReturn(v: ReturnConfirmation): CborMap = CborMap.of(K_LOAN to CborText(v.loan.v))

    fun dispute(v: LoanDispute): CborMap = CborMap(
        buildMap {
            put(K_LOAN, CborText(v.loan.v))
            put(K_CLAIM, CborText(v.claim.name))
            v.note?.let { put(K_NOTE, CborText(it)) }
        },
    )

    fun resolve(v: LoanResolution): CborMap = CborMap.of(K_LOAN to CborText(v.loan.v), K_OUTCOME to CborText(v.outcome.name))

    fun vouch(v: VouchTerms): CborMap = CborMap.of(K_VOUCHEE to CborText(v.vouchee.v), K_EXPIRES to CborInt(v.expiresDay.toLong()))

    fun loanHandover(v: LoanHandover): CborMap = CborMap.of(K_LOAN to CborText(v.loan.v))
    fun loanCancel(v: LoanCancel): CborMap = CborMap.of(K_LOAN to CborText(v.loan.v))

    fun loanReturn(v: LoanReturn): CborMap = CborMap.of(K_LOAN to CborText(v.loan.v), K_OUTCOME to CborText(v.outcome.name))

    fun parseItemUpsert(map: CborMap): Parsed<ItemUpsert> {
        keys(map, required = setOf(K_ITEM, K_NAME, K_CLASS, K_DETAIL_KEY, K_KEY_WRAPS), optional = setOf(K_MIN_TIER))?.let { return Parsed.Invalid(it) }
        val item = itemId(map) ?: return Parsed.Invalid("item must be 32 lowercase hex characters")
        val name = text(map, K_NAME, MAX_ITEM_NAME_CHARS)?.takeIf { it.isNotBlank() } ?: return Parsed.Invalid("name must be 1 to $MAX_ITEM_NAME_CHARS characters")
        val itemClass = enumOf<ItemClass>(map, K_CLASS) ?: return Parsed.Invalid("class is not a known item class")
        val minTier = if (map[K_MIN_TIER] == null) null else enumOf<Tier>(map, K_MIN_TIER) ?: return Parsed.Invalid("minTier is not a known tier")
        if (minTier != null && minTier.rank < itemClass.minTier.rank) return Parsed.Invalid("minTier may not be below the class default")
        val detailKeyId = randomId(map, K_DETAIL_KEY) ?: return Parsed.Invalid("detailKeyId must be 32 lowercase hex characters")
        val keyWraps = readWraps(map) ?: return Parsed.Invalid("keyWraps must list 1 to $MAX_KEY_WRAPS distinct devices with sealed keys")
        return Parsed.Ok(ItemUpsert(ItemId(item), name, itemClass, minTier, detailKeyId, keyWraps))
    }

    fun parseItemRetire(map: CborMap): Parsed<ItemRetire> {
        keys(map, required = setOf(K_ITEM))?.let { return Parsed.Invalid(it) }
        return Parsed.Ok(ItemRetire(ItemId(itemId(map) ?: return Parsed.Invalid("item must be 32 lowercase hex characters"))))
    }

    fun parseItemDetail(map: CborMap): Parsed<ItemDetail> {
        keys(map, required = setOf(K_ITEM, K_DETAIL_KEY, K_SEALED))?.let { return Parsed.Invalid(it) }
        val item = itemId(map) ?: return Parsed.Invalid("item must be 32 lowercase hex characters")
        val keyId = randomId(map, K_DETAIL_KEY) ?: return Parsed.Invalid("detailKeyId must be 32 lowercase hex characters")
        val sealed = (map[K_SEALED] as? CborBytes)?.value?.takeIf { it.size in 1..MAX_ITEM_DETAIL_BYTES } ?: return Parsed.Invalid("sealed must be 1 to $MAX_ITEM_DETAIL_BYTES bytes")
        return Parsed.Ok(ItemDetail(ItemId(item), keyId, sealed))
    }

    fun parseLoanRequest(map: CborMap): Parsed<LoanRequest> {
        keys(map, required = setOf(K_LOAN, K_ITEM, K_FROM, K_TO), optional = setOf(K_NOTE))?.let { return Parsed.Invalid(it) }
        val loan = randomId(map, K_LOAN) ?: return Parsed.Invalid("loan must be 32 lowercase hex characters")
        val item = itemId(map) ?: return Parsed.Invalid("item must be 32 lowercase hex characters")
        val (from, to) = window(map) ?: return Parsed.Invalid("the window must be 0 to $MAX_LOAN_DAYS days with fromDay not after toDay")
        val note = optionalNote(map) ?: return Parsed.Invalid("note must be at most $MAX_LOAN_NOTE_CHARS characters")
        return Parsed.Ok(LoanRequest(LoanId(loan), ItemId(item), from, to, note.value))
    }

    fun parseReturnConfirmation(map: CborMap): Parsed<ReturnConfirmation> {
        keys(map, required = setOf(K_LOAN))?.let { return Parsed.Invalid(it) }
        return Parsed.Ok(ReturnConfirmation(LoanId(randomId(map, K_LOAN) ?: return Parsed.Invalid("loan must be 32 lowercase hex characters"))))
    }

    fun parseDispute(map: CborMap): Parsed<LoanDispute> {
        keys(map, required = setOf(K_LOAN, K_CLAIM), optional = setOf(K_NOTE))?.let { return Parsed.Invalid(it) }
        val loan = randomId(map, K_LOAN) ?: return Parsed.Invalid("loan must be 32 lowercase hex characters")
        val claim = enumOf<DisputeClaim>(map, K_CLAIM) ?: return Parsed.Invalid("claim is not a known claim")
        val note = optionalNote(map) ?: return Parsed.Invalid("note must be at most $MAX_LOAN_NOTE_CHARS characters")
        return Parsed.Ok(LoanDispute(LoanId(loan), claim, note.value))
    }

    fun parseResolution(map: CborMap): Parsed<LoanResolution> {
        keys(map, required = setOf(K_LOAN, K_OUTCOME))?.let { return Parsed.Invalid(it) }
        val loan = randomId(map, K_LOAN) ?: return Parsed.Invalid("loan must be 32 lowercase hex characters")
        val outcome = enumOf<DisputeOutcome>(map, K_OUTCOME) ?: return Parsed.Invalid("outcome is not a known outcome")
        return Parsed.Ok(LoanResolution(LoanId(loan), outcome))
    }

    fun parsePausePropose(map: CborMap): Parsed<PauseProposal> {
        keys(map, required = setOf(K_PAUSE, K_SUBJECT, K_REASON, K_DAYS))?.let { return Parsed.Invalid(it) }
        val pause = randomId(map, K_PAUSE) ?: return Parsed.Invalid("pause must be 32 lowercase hex characters")
        val subject = randomId(map, K_SUBJECT) ?: return Parsed.Invalid("subject must be 32 lowercase hex characters")
        val reason = text(map, K_REASON, MAX_PAUSE_REASON_CHARS)?.takeIf { it.isNotBlank() } ?: return Parsed.Invalid("reason must be 1 to $MAX_PAUSE_REASON_CHARS characters")
        val days = (map[K_DAYS] as? CborInt)?.value?.takeIf { it in MIN_PAUSE_DAYS..MAX_PAUSE_DAYS } ?: return Parsed.Invalid("days must be $MIN_PAUSE_DAYS to $MAX_PAUSE_DAYS")
        return Parsed.Ok(PauseProposal(PauseId(pause), MemberId(subject), reason, days.toInt()))
    }

    fun parsePauseReview(map: CborMap): Parsed<PauseReview> {
        keys(map, required = setOf(K_PAUSE, K_AGREE))?.let { return Parsed.Invalid(it) }
        val pause = randomId(map, K_PAUSE) ?: return Parsed.Invalid("pause must be 32 lowercase hex characters")
        val agree = (map[K_AGREE] as? CborBool)?.value ?: return Parsed.Invalid("agree must be true or false")
        return Parsed.Ok(PauseReview(PauseId(pause), agree))
    }

    fun parsePauseAppeal(map: CborMap): Parsed<PauseAppeal> {
        keys(map, required = setOf(K_PAUSE, K_NOTE))?.let { return Parsed.Invalid(it) }
        val pause = randomId(map, K_PAUSE) ?: return Parsed.Invalid("pause must be 32 lowercase hex characters")
        val note = text(map, K_NOTE, MAX_PAUSE_APPEAL_CHARS)?.takeIf { it.isNotBlank() } ?: return Parsed.Invalid("note must be 1 to $MAX_PAUSE_APPEAL_CHARS characters")
        return Parsed.Ok(PauseAppeal(PauseId(pause), note))
    }

    fun parsePauseLift(map: CborMap): Parsed<PauseLift> {
        keys(map, required = setOf(K_PAUSE))?.let { return Parsed.Invalid(it) }
        return Parsed.Ok(PauseLift(PauseId(randomId(map, K_PAUSE) ?: return Parsed.Invalid("pause must be 32 lowercase hex characters"))))
    }

    fun parseVouch(map: CborMap): Parsed<VouchTerms> {
        keys(map, required = setOf(K_VOUCHEE, K_EXPIRES))?.let { return Parsed.Invalid(it) }
        val vouchee = randomId(map, K_VOUCHEE) ?: return Parsed.Invalid("vouchee must be 32 lowercase hex characters")
        val expires = (map[K_EXPIRES] as? CborInt)?.value?.takeIf { it in 0..MAX_DAY } ?: return Parsed.Invalid("expiresDay must be a day number")
        return Parsed.Ok(VouchTerms(MemberId(vouchee), expires.toInt()))
    }

    fun parseLoanRespond(map: CborMap): Parsed<LoanRespond> {
        keys(map, required = setOf(K_LOAN, K_APPROVE, K_FROM, K_TO), optional = setOf(K_NOTE, K_KEY_WRAPS))?.let { return Parsed.Invalid(it) }
        val loan = randomId(map, K_LOAN) ?: return Parsed.Invalid("loan must be 32 lowercase hex characters")
        val approve = (map[K_APPROVE] as? CborBool)?.value ?: return Parsed.Invalid("approve must be true or false")
        val (from, to) = window(map) ?: return Parsed.Invalid("the window must be 0 to $MAX_LOAN_DAYS days with fromDay not after toDay")
        val note = optionalNote(map) ?: return Parsed.Invalid("note must be at most $MAX_LOAN_NOTE_CHARS characters")
        val hasWraps = map[K_KEY_WRAPS] != null
        if (approve != hasWraps) return Parsed.Invalid(if (approve) "an approval needs keyWraps" else "a decline has no keyWraps")
        val keyWraps = if (hasWraps) readWraps(map) ?: return Parsed.Invalid("keyWraps must list 1 to $MAX_KEY_WRAPS distinct devices with sealed keys") else emptyList()
        return Parsed.Ok(LoanRespond(LoanId(loan), approve, from, to, note.value, keyWraps))
    }

    fun parseLoanCancel(map: CborMap): Parsed<LoanCancel> {
        keys(map, required = setOf(K_LOAN))?.let { return Parsed.Invalid(it) }
        return Parsed.Ok(LoanCancel(LoanId(randomId(map, K_LOAN) ?: return Parsed.Invalid("loan must be 32 lowercase hex characters"))))
    }

    fun parseLoanHandover(map: CborMap): Parsed<LoanHandover> {
        keys(map, required = setOf(K_LOAN))?.let { return Parsed.Invalid(it) }
        return Parsed.Ok(LoanHandover(LoanId(randomId(map, K_LOAN) ?: return Parsed.Invalid("loan must be 32 lowercase hex characters"))))
    }

    fun parseLoanReturn(map: CborMap): Parsed<LoanReturn> {
        keys(map, required = setOf(K_LOAN, K_OUTCOME))?.let { return Parsed.Invalid(it) }
        val loan = randomId(map, K_LOAN) ?: return Parsed.Invalid("loan must be 32 lowercase hex characters")
        val outcome = enumOf<ReturnOutcome>(map, K_OUTCOME) ?: return Parsed.Invalid("outcome is not a known outcome")
        return Parsed.Ok(LoanReturn(LoanId(loan), outcome))
    }

    private fun wraps(list: List<DeviceKeyWrap>): CborValue =
        CborArray(list.map { CborMap.of(K_DEVICE to CborText(it.device.v), K_WRAPPED to CborBytes(it.wrapped)) })

    private fun readWraps(map: CborMap): List<DeviceKeyWrap>? {
        val items = (map[K_KEY_WRAPS] as? CborArray)?.items?.takeIf { it.size in 1..MAX_KEY_WRAPS } ?: return null
        val wraps = items.map { entry ->
            val m = entry as? CborMap ?: return null
            if (m.entries.keys != setOf(K_DEVICE, K_WRAPPED)) return null
            val device = (m[K_DEVICE] as? CborText)?.value?.takeIf { isValidRandomId(it) } ?: return null
            val wrapped = (m[K_WRAPPED] as? CborBytes)?.value?.takeIf { it.size in 1..MAX_WRAPPED_BYTES_PER_KEY } ?: return null
            DeviceKeyWrap(DeviceId(device), wrapped)
        }
        return wraps.takeIf { list -> list.map { it.device }.toSet().size == list.size }
    }

    private const val MAX_WRAPPED_BYTES_PER_KEY = 1024

    private fun keys(map: CborMap, required: Set<String>, optional: Set<String> = emptySet()): String? {
        val present = map.entries.keys
        return if (present.containsAll(required) && (required + optional).containsAll(present)) null
        else "expected the keys ${required.sorted()} and optionally ${optional.sorted()}"
    }

    private fun text(map: CborMap, name: String, max: Int): String? = (map[name] as? CborText)?.value?.takeIf { it.length <= max }

    private fun randomId(map: CborMap, name: String): String? = (map[name] as? CborText)?.value?.takeIf { isValidRandomId(it) }

    private fun itemId(map: CborMap): String? = randomId(map, K_ITEM)

    private inline fun <reified E : Enum<E>> enumOf(map: CborMap, name: String): E? {
        val wire = (map[name] as? CborText)?.value ?: return null
        return enumValues<E>().firstOrNull { it.name == wire }
    }

    private fun window(map: CborMap): Pair<Int, Int>? {
        val from = (map[K_FROM] as? CborInt)?.value?.takeIf { it in 0..Int.MAX_VALUE } ?: return null
        val to = (map[K_TO] as? CborInt)?.value?.takeIf { it in 0..Int.MAX_VALUE } ?: return null
        return if (from <= to && to - from <= MAX_LOAN_DAYS) from.toInt() to to.toInt() else null
    }

    /** Boxes the note so "absent" and "invalid" differ: null means invalid. */
    private class Note(val value: String?)

    private fun optionalNote(map: CborMap): Note? {
        val raw = map[K_NOTE] ?: return Note(null)
        return (raw as? CborText)?.value?.takeIf { it.length <= MAX_LOAN_NOTE_CHARS }?.let { Note(it) }
    }
}
