package circles.server.support

import circles.api.API_PREFIX
import circles.api.ApiJson
import circles.api.ChallengeRequest
import circles.api.ChallengeResponse
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
import circles.event.authMessage
import circles.event.toDto
import circles.policy.CircleId
import kotlinx.serialization.builtins.ListSerializer

class Reply(val status: Int, val body: String, val contentType: String?, val retryAfter: String? = null)

/** How a test client reaches a server: in-process or over a real socket. */
interface Transport {
    suspend fun send(method: String, path: String, body: String? = null, token: String? = null, headers: Map<String, String> = emptyMap()): Reply
}

/**
 * A minimal sync client for tests: the same calls a real device will make (login by signing a challenge, push, pull),
 * written against the shared API types so the wire format is exercised end to end.
 */
class ApiClient(private val transport: Transport, private val crypto: Crypto) {
    suspend fun createCircle(event: Envelope): Pair<Reply, PushResult> = single("$API_PREFIX/circles", event)

    suspend fun join(circle: CircleId, event: Envelope): Pair<Reply, PushResult> = single("$API_PREFIX/circles/${circle.v}/join", event)

    suspend fun login(identity: DeviceIdentity): String {
        val challenge = transport.send("POST", "$API_PREFIX/auth/challenge", ApiJson.encodeToString(ChallengeRequest.serializer(), ChallengeRequest(identity.device.v)))
        check(challenge.status == 200) { "challenge failed: ${challenge.status} ${challenge.body}" }
        val nonce = ApiJson.decodeFromString(ChallengeResponse.serializer(), challenge.body).nonce
        val signature = crypto.sign(identity.signing.secretSeed, authMessage(identity.device, ByteString.fromBase64Url(nonce)!!))
        val request = SessionRequest(identity.device.v, nonce, signature.toBase64Url())
        val session = transport.send("POST", "$API_PREFIX/auth/session", ApiJson.encodeToString(SessionRequest.serializer(), request))
        check(session.status == 200) { "login failed: ${session.status} ${session.body}" }
        return ApiJson.decodeFromString(SessionResponse.serializer(), session.body).token
    }

    suspend fun push(token: String?, circle: CircleId, events: List<Envelope>): Pair<Reply, List<PushResult>> {
        val body = ApiJson.encodeToString(PushRequest.serializer(), PushRequest(events.map { it.toDto() }))
        val reply = transport.send("POST", "$API_PREFIX/circles/${circle.v}/events", body, token)
        val results = if (reply.status == 200) ApiJson.decodeFromString(ListSerializer(PushResult.serializer()), reply.body) else emptyList()
        return reply to results
    }

    suspend fun pull(token: String?, circle: CircleId, after: Long = 0, limit: Int? = null): Pair<Reply, PullResponse?> {
        val query = "after=$after" + (limit?.let { "&limit=$it" } ?: "")
        val reply = transport.send("GET", "$API_PREFIX/circles/${circle.v}/events?$query", token = token)
        return reply to if (reply.status == 200) ApiJson.decodeFromString(PullResponse.serializer(), reply.body) else null
    }

    suspend fun raw(method: String, path: String, body: String? = null, token: String? = null, headers: Map<String, String> = emptyMap()): Reply =
        transport.send(method, path, body, token, headers)

    private suspend fun single(path: String, event: Envelope): Pair<Reply, PushResult> {
        val reply = transport.send("POST", path, ApiJson.encodeToString(SingleEventRequest.serializer(), SingleEventRequest(event.toDto())))
        // Only these statuses carry a PushResult; anything else (400, 403, 413, 429) is a problem document.
        val result = if (reply.status in PUSH_RESULT_STATUSES) {
            ApiJson.decodeFromString(PushResult.serializer(), reply.body)
        } else {
            PushResult(status = PushStatus.REJECTED, reason = "http_${reply.status}")
        }
        return reply to result
    }
}

private val PUSH_RESULT_STATUSES = setOf(200, 201, 422)
