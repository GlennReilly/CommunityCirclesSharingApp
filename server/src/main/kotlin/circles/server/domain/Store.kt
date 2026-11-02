package circles.server.domain

import circles.api.StoredEvent
import circles.encoding.ByteString
import circles.event.DeviceId
import circles.event.Envelope
import circles.event.EventId
import circles.policy.CircleId
import circles.policy.MemberId

/** Read-only view of what the server already knows. The rules in [EventRules] only look at this. */
interface Directory {
    fun circleExists(circle: CircleId): Boolean

    fun device(id: DeviceId): DeviceRecord?

    fun memberExists(circle: CircleId, member: MemberId): Boolean

    fun isSteward(circle: CircleId, member: MemberId): Boolean

    /** The newest circle key epoch announced by a `key.wrap` or `key.rotate`; 0 before the first key. */
    fun currentEpoch(circle: CircleId): Int

    fun invite(circle: CircleId, id: EventId): InviteRecord?
}

/** Everything done inside one database transaction. */
interface StoreTransaction : Directory {
    fun findEventSeq(circle: CircleId, id: EventId): Long?

    /** Stores the event with the next `serverSeq` of its circle and returns that number. */
    fun appendEvent(id: EventId, envelope: Envelope, receivedAtMillis: Long): Long

    fun apply(effect: Effect, serverSeq: Long)

    /** Events with `serverSeq > after`, in order, at most [limit]. */
    fun eventsAfter(circle: CircleId, after: Long, limit: Int): List<StoredEvent>

    fun putChallenge(nonce: ByteString, device: DeviceId, expiresAtMillis: Long)

    /** Returns the challenge and deletes it, so a nonce can be used once. */
    fun takeChallenge(nonce: ByteString): ChallengeRecord?

    fun putSession(tokenHash: ByteString, device: DeviceId, expiresAtMillis: Long)

    fun findSession(tokenHash: ByteString): SessionRecord?

    fun deleteExpired(nowMillis: Long)
}

interface Store {
    /** Runs [block] atomically; an exception rolls everything back. */
    fun <T> transaction(block: (StoreTransaction) -> T): T

    fun isReady(): Boolean
}
