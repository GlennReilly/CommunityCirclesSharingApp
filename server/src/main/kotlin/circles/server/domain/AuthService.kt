package circles.server.domain

import circles.api.ChallengeResponse
import circles.api.SessionRequest
import circles.api.SessionResponse
import circles.crypto.Crypto
import circles.encoding.ByteString
import circles.event.DeviceId
import circles.event.authMessage
import circles.event.isValidRandomId
import java.time.Clock

const val CHALLENGE_TTL_MILLIS = 60_000L
const val SESSION_TTL_SECONDS = 12L * 60 * 60
const val CHALLENGE_NONCE_BYTES = 32
const val SESSION_TOKEN_BYTES = 32
private const val MILLIS_PER_SECOND = 1000L

sealed interface SessionResult {
    data class Granted(val session: SessionResponse) : SessionResult

    /** One answer for every failure, so a caller cannot tell a wrong signature from an unknown device. */
    data object Denied : SessionResult
}

/** Challenge-response login with the device's signing key (spec 4.3). Tokens are stored hashed. */
class AuthService(private val store: Store, private val crypto: Crypto, private val clock: Clock) {
    /** Always answers with a nonce, but only remembers it for a device that exists (no probing for device ids). */
    fun challenge(deviceId: String): ChallengeResponse {
        val nonce = crypto.randomBytes(CHALLENGE_NONCE_BYTES)
        val now = clock.millis()
        store.transaction { tx ->
            tx.deleteExpired(now)
            if (isValidRandomId(deviceId) && tx.device(DeviceId(deviceId)) != null) {
                tx.putChallenge(nonce, DeviceId(deviceId), now + CHALLENGE_TTL_MILLIS)
            }
        }
        return ChallengeResponse(nonce.toBase64Url())
    }

    fun session(request: SessionRequest): SessionResult {
        val nonce = ByteString.fromBase64Url(request.nonce) ?: return SessionResult.Denied
        val signature = ByteString.fromBase64Url(request.sig) ?: return SessionResult.Denied
        val now = clock.millis()
        return store.transaction { tx ->
            val challenge = tx.takeChallenge(nonce)
            val device = challenge?.let { tx.device(it.device) }
            val valid = challenge != null && device != null &&
                challenge.device.v == request.deviceId &&
                challenge.expiresAtMillis > now &&
                !device.isRevoked &&
                crypto.verify(device.signPub, authMessage(device.device, nonce), signature)
            if (!valid || device == null) {
                SessionResult.Denied
            } else {
                val token = crypto.randomBytes(SESSION_TOKEN_BYTES)
                tx.putSession(crypto.hash256(token.toByteArray()), device.device, now + SESSION_TTL_SECONDS * MILLIS_PER_SECOND)
                SessionResult.Granted(SessionResponse(token.toBase64Url(), SESSION_TTL_SECONDS))
            }
        }
    }

    /** The device behind a bearer token, if the token is known, unexpired and its device is still allowed. */
    fun authenticate(token: String): DeviceRecord? {
        val raw = ByteString.fromBase64Url(token) ?: return null
        val now = clock.millis()
        return store.transaction { tx ->
            val session = tx.findSession(crypto.hash256(raw.toByteArray()))
            session?.takeIf { it.expiresAtMillis > now }?.let { tx.device(it.device) }?.takeIf { !it.isRevoked }
        }
    }
}
