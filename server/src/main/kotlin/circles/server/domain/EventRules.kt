package circles.server.domain

import circles.crypto.Crypto
import circles.encoding.Parsed
import circles.event.CircleKeys
import circles.event.DeviceRegistration
import circles.event.Envelope
import circles.event.EnvelopeChecks
import circles.event.EventId
import circles.event.EventType
import circles.event.FIRST_EPOCH
import circles.event.FOUNDING_STEWARD_DAY
import circles.event.PublicParts
import circles.event.StewardActivity
import circles.event.StewardChange
import circles.event.StewardOutcome
import circles.event.StewardPolicy
import circles.event.StewardRules
import circles.event.Rejection
import circles.event.RejectionCode
import circles.event.joinProofMessage

/** The outcome of judging one event: remember these things, or refuse with a reason. */
sealed interface Decision {
    data class Accept(val effects: List<Effect>) : Decision

    data class Reject(val rejection: Rejection) : Decision
}

/** Types this milestone accepts (ADR 0014, 0021). */
val SUPPORTED_TYPES: Set<EventType> = setOf(
    EventType.CIRCLE_CREATE,
    EventType.MEMBER_INVITE,
    EventType.MEMBER_JOIN,
    EventType.DEVICE_ADD,
    EventType.KEY_WRAP,
    EventType.KEY_ROTATE,
    EventType.DEVICE_REVOKE,
    EventType.RECOVERY_SHARE,
    EventType.STEWARD_ADD,
    EventType.STEWARD_REMOVE,
    EventType.STEWARD_POLICY,
    EventType.ITEM_UPSERT,
    EventType.ITEM_RETIRE,
    EventType.ITEM_DETAIL,
    EventType.LOAN_REQUEST,
    EventType.LOAN_RESPOND,
    EventType.LOAN_HANDOVER,
    EventType.LOAN_CANCEL,
    EventType.LOAN_RETURN,
    EventType.VOUCH,
    EventType.STANDING_CONFIRM,
    EventType.LOAN_DISPUTE,
    EventType.LOAN_RESOLVE,
    EventType.PAUSE_PROPOSE,
    EventType.PAUSE_REVIEW,
    EventType.PAUSE_APPEAL,
    EventType.PAUSE_LIFT,
    EventType.CONFIG_UPDATE,
    EventType.STANDING_NOTE,
    EventType.SKILL_UPSERT,
    EventType.SKILL_RETIRE,
)

/** Types whose whole payload is secret: empty `public`, a sealed `encrypted` (spec 3.2, ADR 0025). */
private val OPAQUE_TYPES: Set<EventType> = SUPPORTED_TYPES - setOf(
    EventType.CIRCLE_CREATE, EventType.MEMBER_INVITE, EventType.MEMBER_JOIN, EventType.DEVICE_ADD, EventType.DEVICE_REVOKE,
    EventType.KEY_WRAP, EventType.KEY_ROTATE, EventType.RECOVERY_SHARE, EventType.STEWARD_ADD, EventType.STEWARD_REMOVE, EventType.STEWARD_POLICY,
)

/**
 * Server-side validation of one pushed event (spec 3.1). These checks keep spam out; they are not a security
 * boundary, because clients re-validate everything they fold.
 */
class EventRules(
    private val crypto: Crypto,
    private val stewardPolicy: StewardPolicy = StewardPolicy(),
) {
    fun decide(envelope: Envelope, id: EventId, today: Int, directory: Directory, creationAllowed: Boolean): Decision {
        val type = EventType.fromWire(envelope.type)?.takeIf { it in SUPPORTED_TYPES }
            ?: return reject(RejectionCode.UNSUPPORTED_TYPE, "type '${envelope.type}' is not supported yet")
        if (envelope.day > today + 1) return reject(RejectionCode.FUTURE_DAY, "day ${envelope.day} is after today ($today) plus one")
        epochProblem(envelope, type, directory)?.let { return it }
        // A sealed payload is opaque to the server; only whether there is one is checked.
        val payloadEmpty = envelope.encrypted.isEmpty()
        return when (type) {
            EventType.CIRCLE_CREATE -> circleCreate(envelope, payloadEmpty, directory, creationAllowed)
            EventType.MEMBER_INVITE -> invite(envelope, id, directory)
            EventType.MEMBER_JOIN -> join(envelope, payloadEmpty, directory)
            EventType.KEY_WRAP -> keyWrap(envelope, payloadEmpty, directory)
            EventType.KEY_ROTATE -> keyRotate(envelope, payloadEmpty, directory)
            EventType.DEVICE_REVOKE -> deviceRevoke(envelope, directory)
            EventType.RECOVERY_SHARE -> recoveryShare(envelope, payloadEmpty, directory)
            in OPAQUE_TYPES -> opaque(envelope, payloadEmpty, directory)
            EventType.STEWARD_ADD -> stewardChange(envelope, StewardChange.ADD, directory)
            EventType.STEWARD_REMOVE -> stewardChange(envelope, StewardChange.REMOVE, directory)
            EventType.STEWARD_POLICY -> stewardPolicyChange(envelope, directory)
            else -> deviceAdd(envelope, directory)
        }
    }

    /**
     * Epoch rules (ADR 0021). Types without a secret part use epoch 0, except the two key types, which are checked
     * against the circle's current epoch in their own rules. A secret part needs a key epoch the circle has reached.
     */
    private fun epochProblem(envelope: Envelope, type: EventType, directory: Directory): Decision.Reject? {
        if (type == EventType.KEY_WRAP || type == EventType.KEY_ROTATE || type == EventType.RECOVERY_SHARE) return null
        if (envelope.encrypted.isEmpty()) {
            return if (envelope.epoch == 0) null else reject(RejectionCode.MALFORMED, "epoch must be 0 when the event has no sealed secret part")
        }
        val current = directory.currentEpoch(envelope.circle)
        return if (envelope.epoch in FIRST_EPOCH..current) null else reject(RejectionCode.MALFORMED, "epoch ${envelope.epoch} is not an epoch of this circle (current $current)")
    }

    private fun keyWrap(envelope: Envelope, payloadEmpty: Boolean, directory: Directory): Decision {
        val sender = sender(envelope, directory).deviceOrReject { return it }
        if (!payloadEmpty) return reject(RejectionCode.INVALID_PAYLOAD, "key.wrap has no secret part")
        val terms = when (val parsed = CircleKeys.keyWrap(envelope.public)) {
            is Parsed.Ok -> parsed.value
            is Parsed.Invalid -> return reject(RejectionCode.INVALID_PUBLIC, parsed.reason)
        }
        if (terms.epoch != envelope.epoch) return reject(RejectionCode.INVALID_PUBLIC, "public epoch must equal the envelope epoch")
        if (terms.set != null) return reject(RejectionCode.INVALID_PUBLIC, "only a recovery share names a set")
        val target = directory.device(terms.device)
        if (target == null || target.circle != envelope.circle) return reject(RejectionCode.UNKNOWN_DEVICE, "the target device is not in this circle")
        if (target.isRevoked) return reject(RejectionCode.DEVICE_REVOKED, "the target device has been revoked")
        val current = directory.currentEpoch(envelope.circle)
        return when {
            current == 0 -> {
                if (terms.epoch != FIRST_EPOCH) return reject(RejectionCode.MALFORMED, "the first key of a circle is epoch $FIRST_EPOCH")
                if (!directory.isSteward(envelope.circle, sender.member)) return reject(RejectionCode.NOT_STEWARD, "only a steward may start the first key epoch")
                if (terms.keyHash == null) return reject(RejectionCode.INVALID_PUBLIC, "the first key.wrap must carry a keyHash (ADR 0045)")
                Decision.Accept(listOf(Effect.SetEpoch(envelope.circle, FIRST_EPOCH)))
            }
            terms.epoch in FIRST_EPOCH..current -> Decision.Accept(emptyList())
            else -> reject(RejectionCode.MALFORMED, "epoch ${terms.epoch} has not been started (current $current)")
        }
    }

    /** Item and loan events: the server can only check the sender, that nothing is public, and that a sealed payload exists. */
    private fun opaque(envelope: Envelope, payloadEmpty: Boolean, directory: Directory): Decision {
        sender(envelope, directory).deviceOrReject { return it }
        if (envelope.public.entries.isNotEmpty()) return reject(RejectionCode.INVALID_PUBLIC, "${envelope.type} has no public part")
        if (payloadEmpty) return reject(RejectionCode.INVALID_PAYLOAD, "${envelope.type} needs a sealed payload")
        return Decision.Accept(emptyList())
    }

    private fun stewardChange(envelope: Envelope, change: StewardChange, directory: Directory): Decision {
        val sender = sender(envelope, directory).deviceOrReject { return it }
        val target = when (val parsed = PublicParts.steward(envelope.public)) {
            is Parsed.Ok -> parsed.value
            is Parsed.Invalid -> return reject(RejectionCode.INVALID_PUBLIC, parsed.reason)
        }
        if (change == StewardChange.ADD && !directory.memberExists(envelope.circle, target)) {
            return reject(RejectionCode.INVALID_PUBLIC, "member is not in this circle")
        }
        val stewards = directory.stewardsWithDays(envelope.circle)
        val outcome = StewardRules.decide(
            policyOf(envelope, directory), change, target, sender.member, envelope.day, stewards,
            directory.stewardVotes(envelope.circle, change, target.v), activityOf(envelope, directory),
        )
        return stewardDecision(envelope, change, target.v, sender.member, stewards, outcome) {
            if (change == StewardChange.ADD) Effect.AddSteward(envelope.circle, target, envelope.day) else Effect.RemoveSteward(envelope.circle, target)
        }
    }

    private fun stewardPolicyChange(envelope: Envelope, directory: Directory): Decision {
        val sender = sender(envelope, directory).deviceOrReject { return it }
        val proposed = when (val parsed = PublicParts.stewardSettings(envelope.public)) {
            is Parsed.Ok -> parsed.value
            is Parsed.Invalid -> return reject(RejectionCode.INVALID_PUBLIC, parsed.reason)
        }
        val stewards = directory.stewardsWithDays(envelope.circle)
        val outcome = StewardRules.decidePolicy(
            policyOf(envelope, directory), proposed, sender.member, envelope.day, stewards,
            directory.stewardVotes(envelope.circle, StewardChange.POLICY, proposed.subject()),
        )
        return stewardDecision(envelope, StewardChange.POLICY, proposed.subject(), sender.member, stewards, outcome) {
            Effect.SetStewardSettings(envelope.circle, proposed)
        }
    }

    /** Turns a [StewardOutcome] into a decision: refuse, record a pending vote, or apply [effect] and forget the votes. */
    private inline fun stewardDecision(
        envelope: Envelope,
        change: StewardChange,
        subject: String,
        voter: circles.policy.MemberId,
        stewards: Map<circles.policy.MemberId, Int>,
        outcome: StewardOutcome,
        effect: () -> Effect,
    ): Decision =
        when (outcome) {
            is StewardOutcome.Refused -> reject(if (voter in stewards) RejectionCode.INVALID_PUBLIC else RejectionCode.NOT_STEWARD, outcome.reason)
            StewardOutcome.Pending -> Decision.Accept(listOf(Effect.RecordStewardVote(envelope.circle, change, subject, voter, envelope.day)))
            StewardOutcome.Effective -> Decision.Accept(listOf(effect(), Effect.ClearStewardVotes(envelope.circle, subject)))
        }

    /** The base policy with this circle's own settings (ADR 0027). */
    private fun policyOf(envelope: Envelope, directory: Directory): StewardPolicy =
        stewardPolicy.with(directory.stewardSettings(envelope.circle))

    /** What the circle's log says about each steward's activity, for the inactivity fallback. */
    private fun activityOf(envelope: Envelope, directory: Directory): StewardActivity =
        object : StewardActivity {
            override fun lastEventDayBefore(member: circles.policy.MemberId, position: Long): Int? =
                directory.lastEventDayBefore(envelope.circle, member, position)

            override fun hasEventAfter(member: circles.policy.MemberId, position: Long): Boolean =
                directory.hasEventAfter(envelope.circle, member, position)
        }

    private fun recoveryShare(envelope: Envelope, payloadEmpty: Boolean, directory: Directory): Decision {
        val sender = sender(envelope, directory).deviceOrReject { return it }
        if (!payloadEmpty) return reject(RejectionCode.INVALID_PAYLOAD, "recovery.share has no secret part")
        if (!directory.isSteward(envelope.circle, sender.member)) return reject(RejectionCode.NOT_STEWARD, "only a steward may send recovery shares")
        val terms = when (val parsed = CircleKeys.keyWrap(envelope.public)) {
            is Parsed.Ok -> parsed.value
            is Parsed.Invalid -> return reject(RejectionCode.INVALID_PUBLIC, parsed.reason)
        }
        if (terms.epoch != envelope.epoch) return reject(RejectionCode.INVALID_PUBLIC, "public epoch must equal the envelope epoch")
        if (terms.keyHash != null) return reject(RejectionCode.INVALID_PUBLIC, "a recovery share carries no key commitment")
        val target = directory.device(terms.device)
        if (target == null || target.circle != envelope.circle) return reject(RejectionCode.UNKNOWN_DEVICE, "the target device is not in this circle")
        if (target.isRevoked) return reject(RejectionCode.DEVICE_REVOKED, "the target device has been revoked")
        if (!directory.isSteward(envelope.circle, target.member)) return reject(RejectionCode.NOT_STEWARD, "a recovery share may only go to a steward's device")
        val current = directory.currentEpoch(envelope.circle)
        return if (terms.epoch in FIRST_EPOCH..current) Decision.Accept(emptyList())
        else reject(RejectionCode.MALFORMED, "epoch ${terms.epoch} has not been started (current $current)")
    }

    private fun deviceRevoke(envelope: Envelope, directory: Directory): Decision {
        val sender = sender(envelope, directory).deviceOrReject { return it }
        val target = when (val parsed = PublicParts.revoke(envelope.public)) {
            is Parsed.Ok -> directory.device(parsed.value)
            is Parsed.Invalid -> return reject(RejectionCode.INVALID_PUBLIC, parsed.reason)
        }
        if (target == null || target.circle != envelope.circle) return reject(RejectionCode.UNKNOWN_DEVICE, "the device is not in this circle")
        if (target.isRevoked) return reject(RejectionCode.DEVICE_REVOKED, "the device is already revoked")
        if (target.member != sender.member && !directory.isSteward(envelope.circle, sender.member)) {
            return reject(RejectionCode.NOT_STEWARD, "only a steward may revoke another member's device")
        }
        // A steward leaves office by the quorum of ADR 0027, not by having their devices revoked (ADR 0044).
        if (target.member != sender.member && directory.isSteward(envelope.circle, target.member)) {
            return reject(RejectionCode.STEWARD_PROTECTED, "a steward's devices can only be revoked by that steward")
        }
        return Decision.Accept(listOf(Effect.RevokeDevice(envelope.circle, target.device)))
    }

    private fun keyRotate(envelope: Envelope, payloadEmpty: Boolean, directory: Directory): Decision {
        val sender = sender(envelope, directory).deviceOrReject { return it }
        if (!payloadEmpty) return reject(RejectionCode.INVALID_PAYLOAD, "key.rotate has no secret part")
        if (!directory.isSteward(envelope.circle, sender.member)) return reject(RejectionCode.NOT_STEWARD, "only a steward may rotate the circle key")
        val terms = when (val parsed = CircleKeys.keyRotateTerms(envelope.public)) {
            is Parsed.Ok -> parsed.value
            is Parsed.Invalid -> return reject(RejectionCode.INVALID_PUBLIC, parsed.reason)
        }
        val epoch = terms.epoch
        if (epoch != envelope.epoch) return reject(RejectionCode.INVALID_PUBLIC, "public epoch must equal the envelope epoch")
        val current = directory.currentEpoch(envelope.circle)
        if (current == 0 || epoch != current + 1) return reject(RejectionCode.MALFORMED, "the new epoch must be the current epoch ($current) plus one, after a first key")
        if (terms.keyHash == null) return reject(RejectionCode.INVALID_PUBLIC, "key.rotate must carry a keyHash (ADR 0045)")
        return Decision.Accept(listOf(Effect.SetEpoch(envelope.circle, epoch)))
    }

    private fun circleCreate(envelope: Envelope, payloadEmpty: Boolean, directory: Directory, creationAllowed: Boolean): Decision {
        if (!creationAllowed) return reject(RejectionCode.CREATION_DISABLED, "this server does not accept new circles")
        if (directory.circleExists(envelope.circle)) return reject(RejectionCode.CIRCLE_EXISTS, "circle already exists")
        if (!payloadEmpty) return reject(RejectionCode.INVALID_PAYLOAD, "circle.create has no payload")
        val terms = when (val parsed = PublicParts.circleCreate(envelope.public)) {
            is Parsed.Ok -> parsed.value
            is Parsed.Invalid -> return reject(RejectionCode.INVALID_PUBLIC, parsed.reason)
        }
        val registration = selfRegistration(envelope, Parsed.Ok(terms.registration), directory).registrationOrReject { return it }
        return Decision.Accept(
            listOf(
                Effect.CreateCircle(envelope.circle, envelope.day, terms.settings),
                Effect.RegisterDevice(envelope.circle, registration),
                Effect.AddSteward(envelope.circle, registration.member, FOUNDING_STEWARD_DAY),
            ),
        )
    }

    private fun invite(envelope: Envelope, id: EventId, directory: Directory): Decision {
        val device = sender(envelope, directory).deviceOrReject { return it }
        val terms = when (val parsed = PublicParts.invite(envelope.public)) {
            is Parsed.Ok -> parsed.value
            is Parsed.Invalid -> return reject(RejectionCode.INVALID_PUBLIC, parsed.reason)
        }
        if (terms.expiresDay < envelope.day) return reject(RejectionCode.INVALID_PUBLIC, "expiresDay is before the invite's own day")
        return Decision.Accept(listOf(Effect.RecordInvite(envelope.circle, id, device.member, terms)))
    }

    private fun join(envelope: Envelope, payloadEmpty: Boolean, directory: Directory): Decision {
        if (!directory.circleExists(envelope.circle)) return reject(RejectionCode.UNKNOWN_CIRCLE, "circle does not exist")
        if (!payloadEmpty) return reject(RejectionCode.INVALID_PAYLOAD, "member.join has no payload")
        val terms = when (val parsed = PublicParts.join(envelope.public)) {
            is Parsed.Ok -> parsed.value
            is Parsed.Invalid -> return reject(RejectionCode.INVALID_PUBLIC, parsed.reason)
        }
        val registration = selfRegistration(envelope, Parsed.Ok(terms.registration), directory).registrationOrReject { return it }
        if (directory.memberExists(envelope.circle, registration.member)) return reject(RejectionCode.MEMBER_EXISTS, "member id is already used")
        val invite = directory.invite(envelope.circle, terms.invite) ?: return reject(RejectionCode.UNKNOWN_INVITE, "no such invite in this circle")
        if (envelope.day > invite.terms.expiresDay) return reject(RejectionCode.INVITE_EXPIRED, "invite expired on day ${invite.terms.expiresDay}")
        if (invite.used >= invite.terms.maxUses) return reject(RejectionCode.INVITE_EXHAUSTED, "invite has no uses left")
        if (!crypto.verify(invite.terms.invitePub, joinProofMessage(envelope.circle, registration), terms.proof)) {
            return reject(RejectionCode.BAD_INVITE_PROOF, "proof was not signed by the invite key")
        }
        return Decision.Accept(listOf(Effect.RegisterDevice(envelope.circle, registration), Effect.UseInvite(envelope.circle, invite.id)))
    }

    private fun deviceAdd(envelope: Envelope, directory: Directory): Decision {
        val device = sender(envelope, directory).deviceOrReject { return it }
        val registration = when (val parsed = PublicParts.registration(envelope.public)) {
            is Parsed.Ok -> parsed.value
            is Parsed.Invalid -> return reject(RejectionCode.INVALID_PUBLIC, parsed.reason)
        }
        if (registration.member != device.member) {
            if (!directory.isSteward(envelope.circle, device.member)) return reject(RejectionCode.NOT_STEWARD, "only a steward may add a device for another member")
            if (!directory.memberExists(envelope.circle, registration.member)) return reject(RejectionCode.INVALID_PUBLIC, "member is not in this circle")
        }
        if (directory.device(registration.device) != null) return reject(RejectionCode.DEVICE_EXISTS, "device id is already registered")
        return Decision.Accept(listOf(Effect.RegisterDevice(envelope.circle, registration)))
    }

    /** For events from a device the server has not seen: the registration must be the author's own and signed by its key. */
    private fun selfRegistration(envelope: Envelope, parsed: Parsed<DeviceRegistration>, directory: Directory): Checked<DeviceRegistration> {
        val registration = when (parsed) {
            is Parsed.Ok -> parsed.value
            is Parsed.Invalid -> return Checked.Refused(Rejection(RejectionCode.INVALID_PUBLIC, parsed.reason))
        }
        if (registration.member != envelope.author || registration.device != envelope.device) {
            return Checked.Refused(Rejection(RejectionCode.AUTHOR_MISMATCH, "the registered member and device must be the event's author and device"))
        }
        if (!envelope.hasValidSignature(crypto, registration.signPub)) {
            return Checked.Refused(Rejection(RejectionCode.BAD_SIGNATURE, "signature does not match the registered signing key"))
        }
        if (directory.device(registration.device) != null) {
            return Checked.Refused(Rejection(RejectionCode.DEVICE_EXISTS, "device id is already registered"))
        }
        return Checked.Passed(registration)
    }

    /** For events from a registered device: it must exist, belong to this circle, not be revoked, speak for the author and sign. */
    private fun sender(envelope: Envelope, directory: Directory): Checked<DeviceRecord> {
        val device = directory.device(envelope.device)
            ?: return Checked.Refused(Rejection(RejectionCode.UNKNOWN_DEVICE, "device is not registered"))
        return when {
            device.circle != envelope.circle -> Checked.Refused(Rejection(RejectionCode.WRONG_CIRCLE, "device belongs to another circle"))
            device.isRevoked -> Checked.Refused(Rejection(RejectionCode.DEVICE_REVOKED, "device has been revoked"))
            device.member != envelope.author -> Checked.Refused(Rejection(RejectionCode.AUTHOR_MISMATCH, "device does not belong to the author"))
            !envelope.hasValidSignature(crypto, device.signPub) -> Checked.Refused(Rejection(RejectionCode.BAD_SIGNATURE, "signature does not verify"))
            else -> Checked.Passed(device)
        }
    }

    private fun reject(code: RejectionCode, detail: String): Decision.Reject = Decision.Reject(Rejection(code, detail))

    private sealed interface Checked<out T> {
        data class Passed<T>(val value: T) : Checked<T>

        data class Refused(val rejection: Rejection) : Checked<Nothing>
    }

    private inline fun Checked<DeviceRegistration>.registrationOrReject(onRefused: (Decision.Reject) -> Nothing): DeviceRegistration =
        when (this) {
            is Checked.Passed -> value
            is Checked.Refused -> onRefused(Decision.Reject(rejection))
        }

    private inline fun Checked<DeviceRecord>.deviceOrReject(onRefused: (Decision.Reject) -> Nothing): DeviceRecord =
        when (this) {
            is Checked.Passed -> value
            is Checked.Refused -> onRefused(Decision.Reject(rejection))
        }
}

