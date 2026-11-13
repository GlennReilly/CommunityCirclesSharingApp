package circles.keys

import circles.crypto.AEAD_KEY_BYTES
import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.crypto.DecryptResult
import circles.encoding.ByteString
import circles.event.DEVICE_SEED_BYTES
import circles.event.DeviceId
import kotlinx.serialization.Serializable

/** The shortest passphrase accepted, in characters. Length is the only rule: no required digits or symbols (ADR 0039). */
const val MIN_PASSPHRASE_CHARS = 12
const val SEED_WRAP_VERSION = 1
const val SEED_WRAP_METHOD_PASSPHRASE = "passphrase-argon2id"
const val SEED_WRAP_SALT_BYTES = 16
private const val SEED_WRAP_DOMAIN = "circles/seed-wrap/v1"

/** A device seed encrypted under a key made from a passphrase. Holds nothing secret in the clear; safe to store. */
@Serializable
data class WrappedSeed(
    val v: Int,
    val method: String,
    val memoryKib: Int,
    val iterations: Int,
    val parallelism: Int,
    val salt: String,
    val sealed: String,
)

sealed interface PassphraseCheck {
    data object Fine : PassphraseCheck

    data class TooShort(val minimum: Int) : PassphraseCheck
}

sealed interface UnwrapResult {
    class Opened(val seed: ByteString) : UnwrapResult

    /** The passphrase is wrong, or the blob was changed: the two cannot be told apart, by design. */
    data object WrongPassphraseOrDamaged : UnwrapResult

    /** A blob from a newer version, another method, or with settings outside the allowed limits. */
    data class Unsupported(val reason: String) : UnwrapResult
}

/**
 * Protects the device seed at rest (ADR 0039): XChaCha20-Poly1305 under an Argon2id key. The associated data binds the
 * blob to its device and to its own cost settings, so neither can be swapped for another without the unwrap failing.
 */
object SeedWrapping {
    fun checkPassphrase(passphrase: String): PassphraseCheck =
        if (passphrase.length >= MIN_PASSPHRASE_CHARS) PassphraseCheck.Fine else PassphraseCheck.TooShort(MIN_PASSPHRASE_CHARS)

    suspend fun wrapWithPassphrase(
        crypto: Crypto,
        cipher: Cipher,
        hasher: PasswordHasher,
        seed: ByteString,
        device: DeviceId,
        passphrase: String,
        params: Argon2Params = Argon2Params.DEFAULT,
    ): WrappedSeed {
        require(seed.size == DEVICE_SEED_BYTES) { "a device seed is $DEVICE_SEED_BYTES bytes" }
        require(params.withinLimits()) { "Argon2 settings are outside the allowed limits" }
        require(checkPassphrase(passphrase) == PassphraseCheck.Fine) { "the passphrase is too short" }
        val salt = crypto.randomBytes(SEED_WRAP_SALT_BYTES)
        val key = hasher.argon2id(passphrase, salt, params, AEAD_KEY_BYTES)
        val sealed = cipher.encrypt(key, seed.toByteArray(), associatedData(device, SEED_WRAP_METHOD_PASSPHRASE, params))
        return WrappedSeed(SEED_WRAP_VERSION, SEED_WRAP_METHOD_PASSPHRASE, params.memoryKib, params.iterations, params.parallelism, salt.toBase64Url(), sealed.toBase64Url())
    }

    suspend fun unwrapWithPassphrase(cipher: Cipher, hasher: PasswordHasher, wrapped: WrappedSeed, device: DeviceId, passphrase: String): UnwrapResult {
        if (wrapped.v != SEED_WRAP_VERSION) return UnwrapResult.Unsupported("The saved keys use format version ${wrapped.v}, which this version of Lendger does not read.")
        if (wrapped.method != SEED_WRAP_METHOD_PASSPHRASE) return UnwrapResult.Unsupported("The saved keys use the unlock method ${wrapped.method}, which this version of Lendger does not read.")
        val params = Argon2Params(wrapped.memoryKib, wrapped.iterations, wrapped.parallelism)
        if (!params.withinLimits()) return UnwrapResult.Unsupported("The saved keys ask for more memory or time than Lendger allows.")
        val salt = ByteString.fromBase64Url(wrapped.salt)?.takeIf { it.size == SEED_WRAP_SALT_BYTES } ?: return UnwrapResult.WrongPassphraseOrDamaged
        val sealed = ByteString.fromBase64Url(wrapped.sealed) ?: return UnwrapResult.WrongPassphraseOrDamaged
        val key = hasher.argon2id(passphrase, salt, params, AEAD_KEY_BYTES)
        return when (val opened = cipher.decrypt(key, sealed, associatedData(device, wrapped.method, params))) {
            is DecryptResult.Success -> opened.plaintext.takeIf { it.size == DEVICE_SEED_BYTES }?.let { UnwrapResult.Opened(ByteString.of(it)) }
                ?: UnwrapResult.WrongPassphraseOrDamaged
            DecryptResult.Failure -> UnwrapResult.WrongPassphraseOrDamaged
        }
    }

    private fun associatedData(device: DeviceId, method: String, params: Argon2Params): ByteArray =
        listOf(SEED_WRAP_DOMAIN, device.v, method, params.memoryKib, params.iterations, params.parallelism).joinToString("\n").encodeToByteArray()
}
