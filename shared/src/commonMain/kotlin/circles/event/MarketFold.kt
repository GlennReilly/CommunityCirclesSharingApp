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

enum class LoanStatus { REQUESTED, DECLINED, APPROVED, ACTIVE, RETURNED }

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
)

/** An owner's dispute of a return, and how it ended. [resolvedDay] and [outcome] are null while it is pending. */
data class DisputeState(
    val claim: DisputeClaim,
    val note: String?,
    val day: Int,
    val outcome: DisputeOutcome? = null,
    val resolvedDay: Int? = null,
)

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
) {
    /** The vouches that still count for a decision dated [day]: those by someone who has left stop counting from the day they left (ADR 0044). */
    fun vouchesOn(day: Int): List<circles.policy.Vouch> = vouches.filter { vouch -> departed[vouch.voucher]?.let { day < it } ?: true }
}

internal class MarketResult(val view: MarketView, val rejected: Map<EventId, String>)

private val MARKET_TYPES = setOf(
    EventType.ITEM_UPSERT, EventType.ITEM_RETIRE, EventType.ITEM_DETAIL,
    EventType.LOAN_REQUEST, EventType.LOAN_RESPOND, EventType.LOAN_HANDOVER, EventType.LOAN_RETURN, EventType.VOUCH,
    EventType.STANDING_CONFIRM, EventType.LOAN_DISPUTE, EventType.LOAN_RESOLVE,
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
    private val config: circles.policy.CircleConfig = StandingRules.defaultConfig(circle),
) {
    private val vouches = mutableListOf<circles.policy.Vouch>()
    private val items = mutableMapOf<ItemId, ItemState>()
    private val loans = mutableMapOf<LoanId, LoanState>()
    private val rejected = mutableMapOf<EventId, String>()

    fun fold(events: List<OpenedEvent>): MarketResult {
        for (event in events) {
            if (!isMarketType(event.envelope.type)) continue
            val payload = (event.payload as? Parsed.Ok)?.value ?: continue // unreadable: no effect, no rejection
            val problem = apply(event.envelope, payload, event.id)
            if (problem != null) rejected[event.id] = problem
        }
        val keys = myItemKeys()
        return MarketResult(MarketView(items.toMap(), loans.toMap(), keys, openDetails(keys), vouches.toList(), devices.values.map { it.registration.member }.toSet().mapNotNull { member -> removedOn(member)?.let { member to it } }.toMap()), rejected.toMap())
    }

    private fun apply(e: Envelope, payload: CborMap, id: EventId): String? = when (EventType.fromWire(e.type)) {
        EventType.ITEM_UPSERT -> parse(MarketPayloads.parseItemUpsert(payload)) { upsert(e.author, it) }
        EventType.ITEM_RETIRE -> parse(MarketPayloads.parseItemRetire(payload)) { retire(e.author, it) }
        EventType.ITEM_DETAIL -> parse(MarketPayloads.parseItemDetail(payload)) { detail(e.author, it) }
        EventType.LOAN_REQUEST -> parse(MarketPayloads.parseLoanRequest(payload)) { request(e.author, it, e.day, id) }
        EventType.STANDING_CONFIRM -> parse(MarketPayloads.parseReturnConfirmation(payload)) { confirm(e.author, it, e.day) }
        EventType.LOAN_DISPUTE -> parse(MarketPayloads.parseDispute(payload)) { dispute(e.author, it, e.day) }
        EventType.LOAN_RESOLVE -> parse(MarketPayloads.parseResolution(payload)) { resolve(e.author, it, e.day, id) }
        EventType.VOUCH -> parse(MarketPayloads.parseVouch(payload)) { vouch(e.author, it, e.day, id) }
        EventType.LOAN_RESPOND -> parse(MarketPayloads.parseLoanRespond(payload)) { respond(e.author, it) }
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
        if (v.keyWraps.any { devices[it.device]?.registration?.member != author }) return "keyWraps must go to the owner's own devices"
        val existing = items[v.item]
        if (existing != null) {
            if (existing.owner != author) return "only the owner may change an item"
            if (existing.retired) return "item is retired"
        }
        val wraps = (existing?.keyWraps.orEmpty()) + (v.detailKeyId to (existing?.keyWraps?.get(v.detailKeyId).orEmpty() + v.keyWraps.associate { it.device to it.wrapped }))
        items[v.item] = ItemState(v.item, author, v.name, v.itemClass, v.minTier, v.detailKeyId, false, wraps, existing?.sealedDetails.orEmpty())
        return null
    }

    private fun retire(author: MemberId, v: ItemRetire): String? {
        val item = items[v.item] ?: return "unknown item"
        if (item.owner != author) return "only the owner may retire an item"
        if (item.retired) return "item is already retired"
        items[v.item] = item.copy(retired = true)
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

    private fun vouch(author: MemberId, v: VouchTerms, day: Int, id: EventId): String? {
        if (v.vouchee == author) return "a member cannot vouch for themselves"
        if (devices.values.none { it.registration.member == v.vouchee }) return "the vouchee is not in the circle"
        if (!StandingRules.mayVouch(author, config, loans.values, vouchesCounting(day), stewardsAt(id), day)) return "only a neighbour or above may vouch"
        vouches += circles.policy.Vouch(author, v.vouchee, circle, day, v.expiresDay)
        return null
    }

    private fun request(author: MemberId, v: LoanRequest, day: Int, id: EventId): String? {
        val item = items[v.item] ?: return "unknown item"
        if (item.retired) return "item is retired"
        if (item.owner == author) return "a member cannot borrow their own item"
        if (loans.containsKey(v.loan)) return "loan id already used"
        if (!mayBorrow(author, v.item)) return "member may not borrow this item yet"
        val decision = StandingRules.decide(author, item, config, loans.values, vouchesCounting(day), stewardsAt(id), day)
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
