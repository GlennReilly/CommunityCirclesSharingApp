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
import circles.event.PAYLOAD_VERSION_PLAINTEXT
import circles.event.PublicParts
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
)

/**
 * Server-side validation of one pushed event (spec 3.1). These checks keep spam out; they are not a security
 * boundary, because clients re-validate everything they fold.
 */
class EventRules(private val crypto: Crypto, private val allowPlaintext: Boolean = false) {
    fun decide(envelope: Envelope, id: EventId, today: Int, directory: Directory, creationAllowed: Boolean): Decision {
        val type = EventType.fromWire(envelope.type)?.takeIf { it in SUPPORTED_TYPES }
            ?: return reject(RejectionCode.UNSUPPORTED_TYPE, "type '${envelope.type}' is not supported yet")
        if (envelope.day > today + 1) return reject(RejectionCode.FUTURE_DAY, "day ${envelope.day} is after today ($today) plus one")
        if (envelope.v == PAYLOAD_VERSION_PLAINTEXT && !allowPlaintext) {
            return reject(RejectionCode.UNSUPPORTED_VERSION, "the plaintext development format (v=0) is disabled on this server")
        }
        epochProblem(envelope, type, directory)?.let { return it }
        // A sealed payload is opaque to the server; only whether there is one is checked. A plaintext one is parsed (M1).
        val payloadEmpty = envelope.encrypted.isEmpty()
        if (envelope.v == PAYLOAD_VERSION_PLAINTEXT && (EnvelopeChecks.payload(envelope) as? Parsed.Ok) == null) {
            return reject(RejectionCode.INVALID_PAYLOAD, "payload is not a CBOR map")
        }
        return when (type) {
            EventType.CIRCLE_CREATE -> circleCreate(envelope, payloadEmpty, directory, creationAllowed)
            EventType.MEMBER_INVITE -> invite(envelope, id, directory)
            EventType.MEMBER_JOIN -> join(envelope, payloadEmpty, directory)
            EventType.KEY_WRAP -> keyWrap(envelope, payloadEmpty, directory)
            EventType.KEY_ROTATE -> keyRotate(envelope, payloadEmpty, directory)
            EventType.DEVICE_REVOKE -> deviceRevoke(envelope, directory)
            else -> deviceAdd(envelope, directory)
        }
    }

    /**
     * Epoch rules (ADR 0021). Types without a secret part use epoch 0, except the two key types, which are checked
     * against the circle's current epoch in their own rules. A secret part needs a key epoch the circle has reached.
     */
    private fun epochProblem(envelope: Envelope, type: EventType, directory: Directory): Decision.Reject? {
        if (type == EventType.KEY_WRAP || type == EventType.KEY_ROTATE) return null
        if (envelope.encrypted.isEmpty() || envelope.v == PAYLOAD_VERSION_PLAINTEXT) {
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
        val target = directory.device(terms.device)
        if (target == null || target.circle != envelope.circle) return reject(RejectionCode.UNKNOWN_DEVICE, "the target device is not in this circle")
        if (target.isRevoked) return reject(RejectionCode.DEVICE_REVOKED, "the target device has been revoked")
        val current = directory.currentEpoch(envelope.circle)
        return when {
            current == 0 -> {
                if (terms.epoch != FIRST_EPOCH) return reject(RejectionCode.MALFORMED, "the first key of a circle is epoch $FIRST_EPOCH")
                if (!directory.isSteward(envelope.circle, sender.member)) return reject(RejectionCode.NOT_STEWARD, "only a steward may start the first key epoch")
                Decision.Accept(listOf(Effect.SetEpoch(envelope.circle, FIRST_EPOCH)))
            }
            terms.epoch in FIRST_EPOCH..current -> Decision.Accept(emptyList())
            else -> reject(RejectionCode.MALFORMED, "epoch ${terms.epoch} has not been started (current $current)")
        }
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
        if (envelope.v == PAYLOAD_VERSION_PLAINTEXT) noteProblem(envelope, PublicParts.REASON)?.let { return it }
        return Decision.Accept(listOf(Effect.RevokeDevice(envelope.circle, target.device)))
    }

    private fun keyRotate(envelope: Envelope, payloadEmpty: Boolean, directory: Directory): Decision {
        val sender = sender(envelope, directory).deviceOrReject { return it }
        if (!payloadEmpty) return reject(RejectionCode.INVALID_PAYLOAD, "key.rotate has no secret part")
        if (!directory.isSteward(envelope.circle, sender.member)) return reject(RejectionCode.NOT_STEWARD, "only a steward may rotate the circle key")
        val epoch = when (val parsed = CircleKeys.keyRotate(envelope.public)) {
            is Parsed.Ok -> parsed.value
            is Parsed.Invalid -> return reject(RejectionCode.INVALID_PUBLIC, parsed.reason)
        }
        if (epoch != envelope.epoch) return reject(RejectionCode.INVALID_PUBLIC, "public epoch must equal the envelope epoch")
        val current = directory.currentEpoch(envelope.circle)
        if (current == 0 || epoch != current + 1) return reject(RejectionCode.MALFORMED, "the new epoch must be the current epoch ($current) plus one, after a first key")
        return Decision.Accept(listOf(Effect.SetEpoch(envelope.circle, epoch)))
    }

    private fun circleCreate(envelope: Envelope, payloadEmpty: Boolean, directory: Directory, creationAllowed: Boolean): Decision {
        if (!creationAllowed) return reject(RejectionCode.CREATION_DISABLED, "this server does not accept new circles")
        if (directory.circleExists(envelope.circle)) return reject(RejectionCode.CIRCLE_EXISTS, "circle already exists")
        if (!payloadEmpty) return reject(RejectionCode.INVALID_PAYLOAD, "circle.create has no payload")
        val registration = selfRegistration(envelope, PublicParts.registration(envelope.public), directory).registrationOrReject { return it }
        return Decision.Accept(
            listOf(
                Effect.CreateCircle(envelope.circle, envelope.day),
                Effect.RegisterDevice(envelope.circle, registration),
                Effect.AddSteward(envelope.circle, registration.member),
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
        if (envelope.v == PAYLOAD_VERSION_PLAINTEXT) noteProblem(envelope, PublicParts.NOTE)?.let { return it }
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
        if (envelope.v == PAYLOAD_VERSION_PLAINTEXT) noteProblem(envelope, PublicParts.LABEL)?.let { return it }
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

    private fun noteProblem(envelope: Envelope, name: String): Decision.Reject? {
        val payload = (EnvelopeChecks.payload(envelope) as Parsed.Ok).value
        return (PublicParts.optionalText(payload, name) as? Parsed.Invalid)?.let { reject(RejectionCode.INVALID_PAYLOAD, it.reason) }
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

