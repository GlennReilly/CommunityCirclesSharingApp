package circles.api

import circles.event.EnvelopeDto
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** JSON settings shared by the server and every client. Unknown fields are refused, nothing is silently dropped. */
val ApiJson: Json = Json {
    ignoreUnknownKeys = false
    explicitNulls = false
}

const val API_PREFIX = "/api/v1"

/** Longest push batch (spec 4.3). */
/** Largest blob the server stores, in bytes (ADR 0026). */
const val MAX_BLOB_BYTES = 2 * 1024 * 1024

const val MAX_PUSH_EVENTS = 200

/** Pull page size limits (spec 4.3). */
const val DEFAULT_PULL_LIMIT = 500
const val MAX_PULL_LIMIT = 500

@Serializable
data class ChallengeRequest(val deviceId: String)

@Serializable
data class ChallengeResponse(val nonce: String)

@Serializable
data class SessionRequest(val deviceId: String, val nonce: String, val sig: String)

@Serializable
data class SessionResponse(val token: String, val expiresIn: Long)

@Serializable
data class PushRequest(val events: List<EnvelopeDto>)

/** Body of the two public endpoints, which take exactly one event. */
@Serializable
data class SingleEventRequest(val event: EnvelopeDto)

@Serializable
enum class PushStatus(val wire: String) {
    @SerialName("accepted")
    ACCEPTED("accepted"),

    @SerialName("duplicate")
    DUPLICATE("duplicate"),

    @SerialName("rejected")
    REJECTED("rejected"),
}

/** One entry of the push answer. [id] is null when the event was too malformed to have one. */
@Serializable
data class PushResult(
    val id: String? = null,
    val status: PushStatus,
    val serverSeq: Long? = null,
    val reason: String? = null,
    val detail: String? = null,
)

@Serializable
data class StoredEvent(val serverSeq: Long, val envelope: EnvelopeDto)

@Serializable
data class PullResponse(val events: List<StoredEvent>, val next: Long, val hasMore: Boolean)

/** RFC 9457 problem details. */
@Serializable
data class Problem(val type: String = "about:blank", val title: String, val status: Int, val detail: String? = null)

@Serializable
data class VersionResponse(val name: String, val version: String)
