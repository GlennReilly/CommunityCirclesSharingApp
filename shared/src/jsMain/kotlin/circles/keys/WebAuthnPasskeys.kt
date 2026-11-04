package circles.keys

import circles.encoding.ByteString
import circles.event.DeviceId
import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array
import kotlin.js.Promise

private const val CHALLENGE_BYTES = 32
private const val ALG_ES256 = -7
private const val ALG_RS256 = -257
private const val PASSKEY_TIMEOUT_MILLIS = 120_000

/**
 * [PasskeyProvider] over WebAuthn with the PRF extension (ADR 0063). A credential is made on this device (user verification
 * required), then asked for its secret with a fixed salt; the secret never leaves memory. Any refusal, cancel or missing
 * support is null.
 */
class WebAuthnPasskeys(private val randomBytes: (Int) -> ByteString) : PasskeyProvider {
    override val available: Boolean get() = js("typeof PublicKeyCredential !== 'undefined' && !!navigator.credentials") as Boolean

    private fun bytes(value: ByteString): Uint8Array {
        val raw = value.toByteArray().unsafeCast<Int8Array>()
        return Uint8Array(raw.buffer, raw.byteOffset, raw.length)
    }

    private fun toByteString(buffer: dynamic): ByteString = ByteString.of(Int8Array(buffer.unsafeCast<ArrayBuffer>()).unsafeCast<ByteArray>())

    override suspend fun create(device: DeviceId): PasskeyCreated? {
        if (!available) return null
        val salt = randomBytes(PASSKEY_SALT_BYTES)
        return try {
            val options = js("({})")
            val publicKey = js("({})")
            publicKey.challenge = bytes(randomBytes(CHALLENGE_BYTES))
            publicKey.rp = js("({ name: 'Circles' })")
            val user = js("({})")
            user.id = bytes(ByteString.of(device.v.encodeToByteArray()))
            user.name = "Circles on this device"
            user.displayName = "Circles on this device"
            publicKey.user = user
            publicKey.pubKeyCredParams = arrayOf(js("({ type: 'public-key', alg: -7 })"), js("({ type: 'public-key', alg: -257 })"))
            publicKey.authenticatorSelection = js("({ residentKey: 'preferred', userVerification: 'required' })")
            publicKey.timeout = PASSKEY_TIMEOUT_MILLIS
            publicKey.extensions = js("({ prf: {} })")
            options.publicKey = publicKey
            val credentials = js("navigator.credentials")
            val credential = credentials.create(options).unsafeCast<Promise<dynamic>>().await() ?: return null
            // An authenticator that cannot make the secret says so here; asking later would only fail.
            val enabled = credential.getClientExtensionResults()?.prf?.enabled
            if (enabled == false) return null
            PasskeyCreated(toByteString(credential.rawId), salt)
        } catch (_: Throwable) {
            null
        }
    }

    override suspend fun secret(credential: ByteString, salt: ByteString): ByteString? {
        if (!available) return null
        return try {
            val options = js("({})")
            val publicKey = js("({})")
            publicKey.challenge = bytes(randomBytes(CHALLENGE_BYTES))
            val allowed = js("({ type: 'public-key' })")
            allowed.id = bytes(credential)
            publicKey.allowCredentials = arrayOf(allowed)
            publicKey.userVerification = "required"
            publicKey.timeout = PASSKEY_TIMEOUT_MILLIS
            val evaluation = js("({})")
            evaluation.first = bytes(salt)
            val prf = js("({})")
            prf.eval = evaluation
            val extensions = js("({})")
            extensions.prf = prf
            publicKey.extensions = extensions
            options.publicKey = publicKey
            val credentials = js("navigator.credentials")
            val assertion = credentials.get(options).unsafeCast<Promise<dynamic>>().await() ?: return null
            val first = assertion.getClientExtensionResults()?.prf?.results?.first ?: return null
            toByteString(first)
        } catch (_: Throwable) {
            null
        }
    }
}
