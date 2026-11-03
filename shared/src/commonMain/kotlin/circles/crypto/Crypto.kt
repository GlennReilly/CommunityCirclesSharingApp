package circles.crypto

import circles.encoding.ByteString

const val ED25519_KEY_BYTES = 32
const val ED25519_SIGNATURE_BYTES = 64
const val HASH_BYTES = 32

/** An Ed25519 key pair; [secretSeed] is the 32-byte seed of RFC 8032, never sent anywhere. */
class SigningKeyPair(val secretSeed: ByteString, val publicKey: ByteString)

/**
 * The primitives the event log needs (ADR 0010). No custom cryptography behind this interface: the JVM uses the
 * JDK's Ed25519 and BouncyCastle's BLAKE2b; the browser will use libsodium.
 */
interface Crypto {
    fun generateSigningKeyPair(): SigningKeyPair

    /** The key pair whose secret seed is [seed] (32 bytes); the same seed always gives the same pair. */
    fun signingKeyPairFromSeed(seed: ByteString): SigningKeyPair

    fun sign(secretSeed: ByteString, message: ByteArray): ByteString

    /** False for a wrong signature, a malformed key or a wrong length; it never throws for bad input. */
    fun verify(publicKey: ByteString, message: ByteArray, signature: ByteString): Boolean

    /** BLAKE2b-256 (32 bytes, unkeyed), the same as libsodium's `crypto_generichash` with a 32-byte output. */
    fun hash256(message: ByteArray): ByteString

    fun randomBytes(count: Int): ByteString
}
