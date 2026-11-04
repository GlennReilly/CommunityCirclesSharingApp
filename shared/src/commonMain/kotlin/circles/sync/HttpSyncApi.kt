package circles.sync

import circles.api.API_PREFIX
import circles.api.Wire
import circles.api.ChallengeRequest
import circles.api.ChallengeResponse
import circles.api.MAX_PUSH_EVENTS
import circles.api.PullResponse
import circles.api.PushRequest
import circles.api.PushResult
import circles.api.PushStatus
import circles.api.SessionRequest
import circles.api.SessionResponse
import circles.api.SingleEventRequest
import circles.crypto.Crypto
import circles.encoding.ByteString
import circles.event.DeviceIdentity
import circles.event.Envelope
import circles.event.EventType
import circles.event.authMessage
import circles.event.toDto
import circles.policy.CircleId
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer

const val HTTP_OK = 200
const val HTTP_CREATED = 201
const val HTTP_UNAUTHORIZED = 401
const val HTTP_UNPROCESSABLE = 422
const val HTTP_TOO_MANY_REQUESTS = 429
const val HTTP_SERVER_ERROR = 500

/** What came back; the body is text because every endpoint here speaks JSON. */
class HttpReply(val status: Int, val body: String, val retryAfter: String? = null)

/**
 * The network, reduced to one call. The browser implements it with `fetch`, tests with an in-process server. Returns
 * null when the request could not be made at all (no connection, timeout).
 */
fun interface HttpTransport {
    suspend fun send(method: String, path: String, body: String?, token: String?): HttpReply?
}

/**
 * [SyncApi] over the HTTP API of spec 4.3: logs in by signing the server's challenge, keeps the token, logs in again
 * once if the server says 401. `circle.create` and `member.join` go to their public endpoints because the device is
 * not registered until they are accepted; everything else is pushed in batches with the token.
 */
class HttpSyncApi(
    private val transport: HttpTransport,
    private val crypto: Crypto,
    private val identity: DeviceIdentity,
) : SyncApi, TokenSource {
    private var token: String? = null

    override suspend fun token(): ApiOutcome<String> = token?.let { ApiOutcome.Ok(it) } ?: login().also { if (it is ApiOutcome.Ok) token = it.value }

    override fun rejectToken() {
        token = null
    }

    override suspend fun push(circle: CircleId, events: List<Envelope>): ApiOutcome<List<PushResult>> {
        val results = mutableListOf<PushResult>()
        var index = 0
        while (index < events.size) {
            val event = events[index]
            val outcome = when (EventType.fromWire(event.type)) {
                EventType.CIRCLE_CREATE -> { index++; single("$API_PREFIX/circles", event) }
                EventType.MEMBER_JOIN -> { index++; single("$API_PREFIX/circles/${circle.v}/join", event) }
                else -> {
                    val end = (index until events.size).firstOrNull { isPublicEndpointEvent(events[it]) || it - index >= MAX_PUSH_EVENTS } ?: events.size
                    val batch = events.subList(index, end)
                    index = end
                    batch(circle, batch)
                }
            }
            when (outcome) {
                is ApiOutcome.Ok -> results += outcome.value
                else -> return outcome.failure()
            }
        }
        return ApiOutcome.Ok(results)
    }

    override suspend fun pull(circle: CircleId, after: Long, limit: Int): ApiOutcome<PullResponse> {
        val path = "$API_PREFIX/circles/${circle.v}/events?after=$after&limit=$limit"
        return when (val reply = authorised("GET", path, null)) {
            is ApiOutcome.Ok -> decode(reply.value) { Wire.decodePull(it) }
            else -> reply.failure()
        }
    }

    private fun isPublicEndpointEvent(e: Envelope): Boolean = EventType.fromWire(e.type).let { it == EventType.CIRCLE_CREATE || it == EventType.MEMBER_JOIN }

    private suspend fun single(path: String, event: Envelope): ApiOutcome<List<PushResult>> {
        val body = Wire.encode(SingleEventRequest(event.toDto()))
        val reply = transport.send("POST", path, body, null) ?: return ApiOutcome.Offline
        return when (reply.status) {
            HTTP_OK, HTTP_CREATED, HTTP_UNPROCESSABLE -> decode(reply) { Wire.decodePushResult(it) }.map { listOf(it) }
            // Any other answer is about this one event (creation disabled, malformed, too large): refuse it, keep going.
            in HTTP_UNAUTHORIZED + 1 until HTTP_TOO_MANY_REQUESTS -> ApiOutcome.Ok(listOf(PushResult(event.eventId(crypto).v, PushStatus.REJECTED, reason = "http_${reply.status}")))
            else -> reply.failure()
        }
    }

    private suspend fun batch(circle: CircleId, events: List<Envelope>): ApiOutcome<List<PushResult>> {
        val body = Wire.encode(PushRequest(events.map { it.toDto() }))
        return when (val reply = authorised("POST", "$API_PREFIX/circles/${circle.v}/events", body)) {
            is ApiOutcome.Ok -> decode(reply.value) { Wire.decodePushResults(it) }
            else -> reply.failure()
        }
    }

    /** A request with the bearer token; on 401 it logs in again once and repeats. */
    private suspend fun authorised(method: String, path: String, body: String?): ApiOutcome<HttpReply> {
        repeat(2) { attempt ->
            val current = token ?: when (val login = login()) {
                is ApiOutcome.Ok -> login.value.also { token = it }
                else -> return login.failure()
            }
            val reply = transport.send(method, path, body, current) ?: return ApiOutcome.Offline
            if (reply.status == HTTP_UNAUTHORIZED) {
                token = null
                if (attempt == 1) return ApiOutcome.Unauthorized
            } else {
                return reply.takeIf { it.status == HTTP_OK }?.let { ApiOutcome.Ok(it) } ?: reply.failure()
            }
        }
        return ApiOutcome.Unauthorized
    }

    private suspend fun login(): ApiOutcome<String> {
        val challenge = transport.send("POST", "$API_PREFIX/auth/challenge", Wire.encode(ChallengeRequest(identity.device.v)), null)
            ?: return ApiOutcome.Offline
        if (challenge.status != HTTP_OK) return challenge.failure(loginFailure = true)
        val nonce = (decode(challenge) { Wire.decodeChallenge(it) } as? ApiOutcome.Ok)?.value?.nonce ?: return ApiOutcome.Refused(challenge.status, "bad challenge answer")
        val nonceBytes = ByteString.fromBase64Url(nonce) ?: return ApiOutcome.Refused(challenge.status, "bad challenge nonce")
        val signature = crypto.sign(identity.signing.secretSeed, authMessage(identity.device, nonceBytes))
        val request = SessionRequest(identity.device.v, nonce, signature.toBase64Url())
        val session = transport.send("POST", "$API_PREFIX/auth/session", Wire.encode(request), null)
            ?: return ApiOutcome.Offline
        if (session.status != HTTP_OK) return session.failure(loginFailure = true)
        return decode(session) { Wire.decodeSession(it) }.map { it.token }
    }

    /** Reads the body with [parse]; a body that does not fit is a refusal, never a crash. */
    private fun <T : Any> decode(reply: HttpReply, parse: (String) -> T?): ApiOutcome<T> =
        parse(reply.body)?.let { ApiOutcome.Ok(it) } ?: ApiOutcome.Refused(reply.status, "the server's answer was not understood")

    private fun HttpReply.failure(loginFailure: Boolean = false): ApiOutcome<Nothing> = when {
        status == HTTP_UNAUTHORIZED || (loginFailure && status == HTTP_FORBIDDEN) -> ApiOutcome.Unauthorized
        status == HTTP_TOO_MANY_REQUESTS || status >= HTTP_SERVER_ERROR -> ApiOutcome.Busy(retryAfter?.trim()?.toLongOrNull())
        else -> ApiOutcome.Refused(status, "HTTP $status")
    }

    private fun <T> ApiOutcome<T>.failure(): ApiOutcome<Nothing> = when (this) {
        is ApiOutcome.Ok -> error("not a failure")
        ApiOutcome.Offline -> ApiOutcome.Offline
        ApiOutcome.Unauthorized -> ApiOutcome.Unauthorized
        is ApiOutcome.Busy -> this
        is ApiOutcome.Refused -> this
    }

    private inline fun <T, R> ApiOutcome<T>.map(transform: (T) -> R): ApiOutcome<R> =
        if (this is ApiOutcome.Ok) ApiOutcome.Ok(transform(value)) else failure()

    private companion object {
        const val HTTP_FORBIDDEN = 403
    }
}
