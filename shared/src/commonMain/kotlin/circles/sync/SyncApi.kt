package circles.sync

import circles.api.PullResponse
import circles.api.PushResult
import circles.event.Envelope
import circles.policy.CircleId

/** What one call to the server came to. Network and server details stay behind [SyncApi]; the engine only sees these. */
sealed interface ApiOutcome<out T> {
    data class Ok<out T>(val value: T) : ApiOutcome<T>

    /** No connection, or the request timed out. */
    data object Offline : ApiOutcome<Nothing>

    /** The session is gone and logging in again failed. */
    data object Unauthorized : ApiOutcome<Nothing>

    /** 429 or 5xx: try again later, after [retryAfterSeconds] if the server said. */
    data class Busy(val retryAfterSeconds: Long? = null) : ApiOutcome<Nothing>

    /** Any other refusal (for example 403 for a revoked device); retrying will not help. */
    data class Refused(val status: Int, val title: String) : ApiOutcome<Nothing>
}

/**
 * The two calls a client makes to sync (spec 4.3). An implementation owns the login and the token; the browser one
 * uses `fetch`, tests use an in-process server.
 */
interface SyncApi {
    suspend fun push(circle: CircleId, events: List<Envelope>): ApiOutcome<List<PushResult>>

    suspend fun pull(circle: CircleId, after: Long, limit: Int): ApiOutcome<PullResponse>
}
