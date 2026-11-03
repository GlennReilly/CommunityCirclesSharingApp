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

/** What the owner says went wrong that the borrower did not report. */
enum class DisputeClaim { DAMAGE, LOSS }

enum class DisputeOutcome { UPHELD, DISMISSED }

data class ReturnConfirmation(val loan: LoanId)

data class LoanDispute(val loan: LoanId, val claim: DisputeClaim, val note: String?)

data class LoanResolution(val loan: LoanId, val outcome: DisputeOutcome)

/** A member vouches for another until [expiresDay] (the policy caps it; ADR 0042). */
data class VouchTerms(val vouchee: MemberId, val expiresDay: Int)

data class LoanReturn(val loan: LoanId, val outcome: ReturnOutcome)

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
