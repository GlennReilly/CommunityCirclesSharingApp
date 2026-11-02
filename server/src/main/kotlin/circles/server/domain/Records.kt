package circles.server.domain

import circles.encoding.ByteString
import circles.event.DeviceId
import circles.event.DeviceRegistration
import circles.event.EventId
import circles.event.InviteTerms
import circles.policy.CircleId
import circles.policy.MemberId

data class DeviceRecord(
    val device: DeviceId,
    val circle: CircleId,
    val member: MemberId,
    val signPub: ByteString,
    val boxPub: ByteString,
    val addedSeq: Long,
    val revokedSeq: Long?,
) {
    val isRevoked: Boolean get() = revokedSeq != null
}

data class InviteRecord(val circle: CircleId, val id: EventId, val author: MemberId, val terms: InviteTerms, val used: Int)

data class ChallengeRecord(val device: DeviceId, val expiresAtMillis: Long)

data class SessionRecord(val device: DeviceId, val expiresAtMillis: Long)

/** What the server learns from an accepted event and must remember (spec 3.1, 4.2). */
sealed interface Effect {
    data class CreateCircle(val circle: CircleId, val day: Int) : Effect

    data class RegisterDevice(val circle: CircleId, val registration: DeviceRegistration) : Effect

    data class AddSteward(val circle: CircleId, val member: MemberId) : Effect

    data class RecordInvite(val circle: CircleId, val id: EventId, val author: MemberId, val terms: InviteTerms) : Effect

    data class UseInvite(val circle: CircleId, val id: EventId) : Effect

    data class RevokeDevice(val circle: CircleId, val device: DeviceId) : Effect

    data class SetEpoch(val circle: CircleId, val epoch: Int) : Effect
}
