package circles.server.domain

import circles.encoding.ByteString
import circles.event.DeviceId
import circles.event.DeviceRegistration
import circles.event.EventId
import circles.event.InviteTerms
import circles.event.StewardChange
import circles.event.StewardSettings
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

/** What `list` shows about a circle: ids and counts only, nothing a member wrote. */
data class CircleSummary(val circle: CircleId, val createdDay: Int, val events: Long, val devices: Int, val stewards: Int)

data class ChallengeRecord(val device: DeviceId, val expiresAtMillis: Long)

data class SessionRecord(val device: DeviceId, val expiresAtMillis: Long)

/** What the server learns from an accepted event and must remember (spec 3.1, 4.2). */
sealed interface Effect {
    data class CreateCircle(val circle: CircleId, val day: Int, val settings: StewardSettings) : Effect

    data class RegisterDevice(val circle: CircleId, val registration: DeviceRegistration) : Effect

    data class AddSteward(val circle: CircleId, val member: MemberId, val day: Int) : Effect

    data class RecordInvite(val circle: CircleId, val id: EventId, val author: MemberId, val terms: InviteTerms) : Effect

    data class UseInvite(val circle: CircleId, val id: EventId) : Effect

    /** [subject] is a member id for add and remove, or [StewardSettings.subject] for a policy change. */
    data class RecordStewardVote(val circle: CircleId, val change: StewardChange, val subject: String, val voter: MemberId, val day: Int) : Effect

    /** Forget pending votes about [target] once a change has taken effect. */
    data class ClearStewardVotes(val circle: CircleId, val subject: String) : Effect

    data class SetStewardSettings(val circle: CircleId, val settings: StewardSettings) : Effect

    data class RemoveSteward(val circle: CircleId, val member: MemberId) : Effect

    data class RevokeDevice(val circle: CircleId, val device: DeviceId) : Effect

    data class SetEpoch(val circle: CircleId, val epoch: Int) : Effect
}
