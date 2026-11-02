package circles.crypto

import circles.encoding.ByteString

const val AEAD_KEY_BYTES = 32
const val AEAD_NONCE_BYTES = 24
const val AEAD_TAG_BYTES = 16
const val BOX_KEY_BYTES = 32
const val SEALED_BOX_OVERHEAD_BYTES = 48

/** An X25519 key pair for sealed boxes; [secretKey] never leaves the device. */
class BoxKeyPair(val secretKey: ByteString, val publicKey: ByteString)

/** Outcome of decrypting; a wrong key, tampering or a malformed input is [Failure], never an exception. */
sealed interface DecryptResult {
    class Success(val plaintext: ByteArray) : DecryptResult
    data object Failure : DecryptResult
}

/**
 * Encryption primitives for clients (ADR 0018). Kept apart from [Crypto] so the server, which never decrypts,
 * carries no libsodium. Implementations: libsodium-wrappers in the browser, lazysodium in JVM tests.
 */
interface Cipher {
    /** XChaCha20-Poly1305 (IETF) with a fresh random nonce; the result is `nonce(24) || ciphertext || tag(16)`. */
    fun encrypt(key: ByteString, plaintext: ByteArray, associatedData: ByteArray = ByteArray(0)): ByteString

    /** Reverses [encrypt]; [Failure][DecryptResult.Failure] if the key, associated data or bytes are wrong. */
    fun decrypt(key: ByteString, sealed: ByteString, associatedData: ByteArray = ByteArray(0)): DecryptResult

    fun generateBoxKeyPair(): BoxKeyPair

    /** `crypto_box_seal` to [recipientPublicKey]; the result is the plaintext plus [SEALED_BOX_OVERHEAD_BYTES]. */
    fun sealBox(recipientPublicKey: ByteString, plaintext: ByteArray): ByteString

    fun openSealedBox(recipient: BoxKeyPair, sealed: ByteString): DecryptResult
}
