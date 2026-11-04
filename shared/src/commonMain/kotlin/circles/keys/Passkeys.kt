package circles.keys

import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.crypto.DecryptResult
import circles.encoding.ByteString
import circles.event.DeviceId

private const val PASSKEY_KEY_DOMAIN = "circles/passkey-wrap/v1"
private const val PASSKEY_AAD_DOMAIN = "circles/passkey/v1"
const val PASSKEY_SALT_BYTES = 32
private const val PRF_OUTPUT_BYTES = 32

/** A passkey made for this device: its credential id and the salt to ask its secret-making function with. */
class PasskeyCreated(val credential: ByteString, val salt: ByteString)

/**
 * The browser's passkeys (WebAuthn with the PRF extension, ADR 0063), reduced to what the vault needs. An authenticator gives
 * a secret only after the person has proved themselves (fingerprint, face, PIN), so nothing is typed. Null from a call means
 * the person cancelled or this browser or authenticator cannot do it.
 */
interface PasskeyProvider {
    /** Whether this browser offers passkeys at all (it may still refuse the PRF extension later). */
    val available: Boolean

    suspend fun create(device: DeviceId): PasskeyCreated?

    /** The 32-byte secret the authenticator derives from [salt] for [credential]. */
    suspend fun secret(credential: ByteString, salt: ByteString): ByteString?
}

/** Seals the device seed under a passkey's secret (ADR 0063), and opens it again. */
object PasskeyWrapping {
    private fun key(crypto: Crypto, secret: ByteString): ByteString =
        crypto.hash256(PASSKEY_KEY_DOMAIN.encodeToByteArray() + secret.toByteArray())

    private fun aad(device: DeviceId, credential: ByteString): ByteArray = (PASSKEY_AAD_DOMAIN + device.v + credential.toBase64Url()).encodeToByteArray()

    fun seal(crypto: Crypto, cipher: Cipher, secret: ByteString, seed: ByteString, device: DeviceId, created: PasskeyCreated): PasskeyWrap? {
        if (secret.size != PRF_OUTPUT_BYTES) return null
        val sealed = cipher.encrypt(key(crypto, secret), seed.toByteArray(), aad(device, created.credential))
        return PasskeyWrap(created.credential.toBase64Url(), created.salt.toBase64Url(), sealed.toBase64Url())
    }

    /** The seed, or null if the secret is wrong, the record belongs to another device, or the bytes were altered. */
    fun open(crypto: Crypto, cipher: Cipher, secret: ByteString, wrap: PasskeyWrap, device: DeviceId): ByteString? {
        val credential = ByteString.fromBase64Url(wrap.credential) ?: return null
        val sealed = ByteString.fromBase64Url(wrap.sealed) ?: return null
        if (secret.size != PRF_OUTPUT_BYTES) return null
        return (cipher.decrypt(key(crypto, secret), sealed, aad(device, credential)) as? DecryptResult.Success)?.plaintext?.let { ByteString.of(it) }
    }
}

sealed interface PasskeyResult {
    data object Done : PasskeyResult

    /** The browser has no passkeys, or the person cancelled, or the authenticator cannot do the secret-making function. */
    data object Unavailable : PasskeyResult

    /** The vault is locked; unlock with the passphrase first. */
    data object NotUnlocked : PasskeyResult
}
