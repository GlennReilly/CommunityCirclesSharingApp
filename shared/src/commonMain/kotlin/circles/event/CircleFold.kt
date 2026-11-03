package circles.event

import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.encoding.CborMap
import circles.encoding.Parsed
import circles.policy.CircleId
import circles.policy.ItemId
import circles.policy.MemberId

/** A device as the log has shaped it: its keys and whether it has been revoked. */
data class DeviceState(val registration: DeviceRegistration, val revoked: Boolean)

/** An event that passed validation, with its payload opened if this device holds the key. */
class OpenedEvent(val id: EventId, val envelope: Envelope, val payload: Parsed<CborMap>)

/** An event the local validator refused; shown in a debug list, never folded (spec 3.4). */
data class RejectedEvent(val id: EventId, val type: String, val reason: String)

/** Everything one device learns from a circle's log. */
class CircleView(
    val devices: Map<DeviceId, DeviceState>,
    val stewards: Set<MemberId>,
    val currentEpoch: Int,
    val keys: KeyRing,
    val events: List<OpenedEvent>,
    val rejected: List<RejectedEvent>,
    val market: MarketView,
    /** The circle's steward settings in force at the end of the log (ADR 0027). */
    val stewardSettings: StewardSettings = StewardSettings(),
)

/**
 * Replays a circle's log in `serverSeq` order the way every client must (spec 3.1, 3.4): it trusts nothing the server
 * said. Signatures, device registration and revocation, roles and invites are re-checked; keys wrapped to [me] are
 * opened; then payloads are opened with those keys. A key may arrive after the events it reads, so payloads are
 * opened in a second pass.
 */
class CircleFold(
    private val crypto: Crypto,
    private val cipher: Cipher,
    private val me: DeviceIdentity?,
    private val stewardPolicy: StewardPolicy = StewardPolicy(),
    /**
     * Client-only rule of ADR 0023: may this member become a steward (has reached the STEWARD standing tier)?
     * Standing is private and needs vouch and loan events (M4), so until then the default allows everyone; the real
     * check must be supplied before steward changes are relied on.
     */
    private val mayBecomeSteward: (MemberId) -> Boolean = { true },
    /**
     * Client-only access rule for new loan requests (ADR 0025): may this member ask to borrow this item? The real check
     * (tier, pause, cooldown from `AccessPolicy.canBorrow`) arrives with standing in M4; until then everyone may.
     */
    private val mayBorrow: (MemberId, ItemId) -> Boolean = { _, _ -> true },
) {
    fun fold(circle: CircleId, log: List<Envelope>): CircleView {
        val state = State(circle)
        val accepted = mutableListOf<Pair<EventId, Envelope>>()
        for ((index, envelope) in log.withIndex()) {
            val id = envelope.eventId(crypto)
            val problem = if (envelope.circle != circle) "event belongs to another circle" else state.apply(envelope, id, index.toLong())
            if (problem == null) state.noteActivity(envelope.author, envelope.day, index.toLong())
            if (problem == null) accepted += id to envelope else state.rejected += RejectedEvent(id, envelope.type, problem)
        }
        val opened = accepted.map { (id, envelope) -> OpenedEvent(id, envelope, PayloadSealing.open(cipher, state.keys, envelope)) }
        val market = MarketFold(cipher, me, circle, state.devices, mayBorrow).fold(opened)
        val refused = opened.filter { it.id in market.rejected }.map { RejectedEvent(it.id, it.envelope.type, market.rejected.getValue(it.id)) }
        return CircleView(
            state.devices.toMap(), state.stewards.keys.toSet(), state.epoch, state.keys,
            opened.filter { it.id !in market.rejected }, state.rejected.toList() + refused, market.view, state.settings,
        )
    }

    private inner class State(val circle: CircleId) {
        val devices = mutableMapOf<DeviceId, DeviceState>()
        val stewards = mutableMapOf<MemberId, Int>()
        val stewardVotes = mutableListOf<Triple<StewardChange, String, StewardVote>>()
        var settings = StewardSettings()
        private val activityByMember = mutableMapOf<MemberId, MutableList<Pair<Long, Int>>>()

        /** Every event a member authored that the fold accepted, with its position and day (the fallback reads these). */
        fun noteActivity(member: MemberId, day: Int, position: Long) {
            activityByMember.getOrPut(member) { mutableListOf() } += position to day
        }

        val activity = object : StewardActivity {
            override fun lastEventDayBefore(member: MemberId, position: Long): Int? =
                activityByMember[member]?.filter { it.first < position }?.maxOfOrNull { it.second }

            override fun hasEventAfter(member: MemberId, position: Long): Boolean =
                activityByMember[member]?.any { it.first > position } == true
        }
        val rejected = mutableListOf<RejectedEvent>()
        val invites = mutableMapOf<EventId, Pair<InviteTerms, Int>>()
        var keys: KeyRing = KeyRing.EMPTY
        var epoch = 0
        var created = false

        /** Returns null if the event is valid and has been applied, otherwise why it was refused. */
        fun apply(e: Envelope, id: EventId, position: Long): String? {
            val type = EventType.fromWire(e.type)
            if (!created) return if (type == EventType.CIRCLE_CREATE) create(e) else "the log must start with circle.create"
            if (type == EventType.CIRCLE_CREATE) return "circle.create after the circle exists"
            if (type == EventType.MEMBER_JOIN) return join(e)
            val sender = devices[e.device]?.takeIf { !it.revoked }?.registration ?: return "sender device is unknown or revoked"
            if (sender.member != e.author) return "device does not belong to the author"
            if (!e.hasValidSignature(crypto, sender.signPub)) return "bad signature"
            return when (type) {
                EventType.MEMBER_INVITE -> invite(e, id)
                EventType.DEVICE_ADD -> deviceAdd(e, sender)
                EventType.DEVICE_REVOKE -> revoke(e, sender)
                EventType.STEWARD_ADD -> stewardChange(e, sender, StewardChange.ADD, position)
                EventType.STEWARD_REMOVE -> stewardChange(e, sender, StewardChange.REMOVE, position)
                EventType.STEWARD_POLICY -> stewardPolicyChange(e, sender, position)
                EventType.KEY_WRAP -> wrap(e, sender)
                EventType.KEY_ROTATE -> rotate(e, sender)
                else -> null
            }
        }

        private fun create(e: Envelope): String? {
            val terms = when (val parsed = PublicParts.circleCreate(e.public)) {
                is Parsed.Ok -> parsed.value
                is Parsed.Invalid -> return "bad circle.create: ${parsed.reason}"
            }
            val reg = terms.registration
            if (reg.member != e.author || reg.device != e.device) return "registration is not the author's"
            if (!e.hasValidSignature(crypto, reg.signPub)) return "bad signature"
            created = true
            settings = terms.settings
            devices[reg.device] = DeviceState(reg, revoked = false)
            stewards[reg.member] = FOUNDING_STEWARD_DAY
            return null
        }

        private fun invite(e: Envelope, id: EventId): String? {
            val terms = (PublicParts.invite(e.public) as? Parsed.Ok)?.value ?: return "bad invite"
            invites[id] = terms to 0
            return null
        }

        private fun join(e: Envelope): String? {
            val terms = (PublicParts.join(e.public) as? Parsed.Ok)?.value ?: return "bad join"
            val reg = terms.registration
            if (reg.member != e.author || reg.device != e.device) return "registration is not the author's"
            if (!e.hasValidSignature(crypto, reg.signPub)) return "bad signature"
            if (devices.containsKey(reg.device)) return "device already registered"
            if (devices.values.any { it.registration.member == reg.member }) return "member already in the circle"
            val (invite, used) = invites[terms.invite] ?: return "no such invite"
            if (e.day > invite.expiresDay) return "invite expired"
            if (used >= invite.maxUses) return "invite exhausted"
            if (!crypto.verify(invite.invitePub, joinProofMessage(circle, reg), terms.proof)) return "bad invite proof"
            invites[terms.invite] = invite to used + 1
            devices[reg.device] = DeviceState(reg, revoked = false)
            return null
        }

        private fun deviceAdd(e: Envelope, sender: DeviceRegistration): String? {
            val reg = (PublicParts.registration(e.public) as? Parsed.Ok)?.value ?: return "bad registration"
            if (devices.containsKey(reg.device)) return "device already registered"
            val isOwn = reg.member == sender.member
            if (!isOwn && !(sender.member in stewards && devices.values.any { it.registration.member == reg.member })) {
                return "only a steward may add a device for another member"
            }
            devices[reg.device] = DeviceState(reg, revoked = false)
            return null
        }

        private fun revoke(e: Envelope, sender: DeviceRegistration): String? {
            val target = (PublicParts.revoke(e.public) as? Parsed.Ok)?.value?.let { devices[it] } ?: return "unknown device"
            if (target.revoked) return "device already revoked"
            if (target.registration.member != sender.member && sender.member !in stewards) return "only a steward may revoke another member's device"
            devices[target.registration.device] = target.copy(revoked = true)
            return null
        }

        private fun policyNow(): StewardPolicy = stewardPolicy.with(settings)

        private fun stewardChange(e: Envelope, sender: DeviceRegistration, change: StewardChange, position: Long): String? {
            val target = (PublicParts.steward(e.public) as? Parsed.Ok)?.value ?: return "bad steward change"
            if (change == StewardChange.ADD) {
                if (devices.values.none { it.registration.member == target }) return "member is not in the circle"
                if (!mayBecomeSteward(target)) return "member has not reached the steward standing tier"
            }
            val prior = stewardVotes.filter { it.first == change && it.second == target.v }.map { it.third }
            val outcome = StewardRules.decide(policyNow(), change, target, sender.member, e.day, stewards, prior, activity)
            return applyOutcome(outcome, change, target.v, StewardVote(sender.member, e.day, position)) {
                if (change == StewardChange.ADD) stewards[target] = e.day else stewards.remove(target)
            }
        }

        private fun stewardPolicyChange(e: Envelope, sender: DeviceRegistration, position: Long): String? {
            val proposed = when (val parsed = PublicParts.stewardSettings(e.public)) {
                is Parsed.Ok -> parsed.value
                is Parsed.Invalid -> return "bad steward.policy: ${parsed.reason}"
            }
            val prior = stewardVotes.filter { it.first == StewardChange.POLICY && it.second == proposed.subject() }.map { it.third }
            val outcome = StewardRules.decidePolicy(policyNow(), proposed, sender.member, e.day, stewards, prior)
            return applyOutcome(outcome, StewardChange.POLICY, proposed.subject(), StewardVote(sender.member, e.day, position)) { settings = proposed }
        }

        private inline fun applyOutcome(outcome: StewardOutcome, change: StewardChange, subject: String, vote: StewardVote, effect: () -> Unit): String? =
            when (outcome) {
                is StewardOutcome.Refused -> outcome.reason
                StewardOutcome.Pending -> {
                    stewardVotes += Triple(change, subject, vote)
                    null
                }
                StewardOutcome.Effective -> {
                    effect()
                    stewardVotes.removeAll { it.second == subject }
                    null
                }
            }

        private fun wrap(e: Envelope, sender: DeviceRegistration): String? {
            val terms = (CircleKeys.keyWrap(e.public) as? Parsed.Ok)?.value ?: return "bad key.wrap"
            if (terms.epoch != e.epoch) return "public epoch differs from the envelope epoch"
            val target = devices[terms.device]?.takeIf { !it.revoked } ?: return "target device is unknown or revoked"
            if (epoch == 0) {
                if (terms.epoch != FIRST_EPOCH || sender.member !in stewards) return "the first key must be epoch $FIRST_EPOCH from a steward"
                epoch = FIRST_EPOCH
            } else if (terms.epoch !in FIRST_EPOCH..epoch) {
                return "epoch ${terms.epoch} has not been started"
            }
            val mine = me?.takeIf { it.device == target.registration.device }?.boxKeys ?: return null
            return when (val key = CircleKeys.unwrap(cipher, mine, circle, terms.epoch, terms.wrapped)) {
                is Parsed.Ok -> {
                    keys = keys.with(terms.epoch, key.value)
                    null
                }
                is Parsed.Invalid -> "the key wrapped to this device is not usable: ${key.reason}"
            }
        }

        private fun rotate(e: Envelope, sender: DeviceRegistration): String? {
            if (sender.member !in stewards) return "only a steward may rotate the circle key"
            val next = (CircleKeys.keyRotate(e.public) as? Parsed.Ok)?.value ?: return "bad key.rotate"
            if (next != e.epoch || epoch == 0 || next != epoch + 1) return "the new epoch must be the current epoch plus one"
            epoch = next
            return null
        }
    }
}
