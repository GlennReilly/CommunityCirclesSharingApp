package circles.server.domain

import circles.api.StoredEvent
import circles.encoding.ByteString
import circles.event.DeviceId
import circles.event.Envelope
import circles.event.EventId
import circles.event.StewardChange
import circles.event.StewardSettings
import circles.event.StewardVote
import circles.policy.CircleId
import circles.policy.MemberId

/** Read-only view of what the server already knows. The rules in [EventRules] only look at this. */
interface Directory {
    fun circleExists(circle: CircleId): Boolean

    fun device(id: DeviceId): DeviceRecord?

    fun memberExists(circle: CircleId, member: MemberId): Boolean

    fun isSteward(circle: CircleId, member: MemberId): Boolean

    fun stewardCount(circle: CircleId): Int

    /** Current stewards and the day each became one (ADR 0023). */
    fun stewardsWithDays(circle: CircleId): Map<MemberId, Int>

    /** Pending steward events for [subject], as recorded by [Effect.RecordStewardVote], with their `serverSeq` as position. */
    fun stewardVotes(circle: CircleId, change: StewardChange, subject: String): List<StewardVote>

    /** The circle's steward settings (ADR 0027); the defaults if the circle is unknown. */
    fun stewardSettings(circle: CircleId): StewardSettings

    /** The day of the latest event [member] authored in the circle before `serverSeq` [position], or null. */
    fun lastEventDayBefore(circle: CircleId, member: MemberId, position: Long): Int?

    /** True if [member] authored any event in the circle after `serverSeq` [position]. */
    fun hasEventAfter(circle: CircleId, member: MemberId, position: Long): Boolean

    /** The newest `serverSeq` of the circle, or 0 if it has no events. */
    fun latestSeq(circle: CircleId): Long

    /** The stored size of a blob, or null if there is none. */
    fun blobSize(circle: CircleId, hash: String): Long?

    /** Total bytes of blobs stored for the circle. */
    fun blobBytesUsed(circle: CircleId): Long

    /** One line per circle on this host, for the operator's `list` command. */
    fun circleSummaries(): List<CircleSummary>

    /** Every blob stored for the circle with its size, by hash. */
    fun blobList(circle: CircleId): List<Pair<String, Long>>

    fun insertBlob(circle: CircleId, hash: String, size: Long, createdAtMillis: Long)

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

    /** Remembers an operator-issued import token by its hash (ADR 0034). */
    fun putImportToken(tokenHash: ByteString, circle: CircleId, expiresAtMillis: Long)

    /** Uses up the token if it exists, names [circle] and has not expired; true if it did. A token works once. */
    fun consumeImportToken(tokenHash: ByteString, circle: CircleId, nowMillis: Long): Boolean

    fun deleteExpired(nowMillis: Long)
}

interface Store {
    /** Runs [block] atomically; an exception rolls everything back. */
    fun <T> transaction(block: (StoreTransaction) -> T): T

    fun isReady(): Boolean
}
