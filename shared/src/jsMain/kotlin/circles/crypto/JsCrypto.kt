package circles.crypto

import circles.encoding.ByteString
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint32Array
import org.khronos.webgl.Uint8Array

private fun ByteArray.toUint8Array(): Uint8Array {
    val view = unsafeCast<Int8Array>()
    return Uint8Array(view.buffer, view.byteOffset, view.length)
}

private fun ByteString.toUint8Array(): Uint8Array = toByteArray().toUint8Array()

private fun Uint8Array.toByteString(): ByteString = ByteString.of(Int8Array(buffer, byteOffset, length).unsafeCast<ByteArray>())

private fun Uint8Array.toByteArray(): ByteArray = Int8Array(buffer, byteOffset, length).unsafeCast<ByteArray>().copyOf()

private const val X25519_KEY_BYTES = 32
private const val MAX_RANDOM_CALL_BYTES = 65_536
private const val BLAKE2B_NONCE_BYTES = 24
private const val SALSA_KEY_WORDS = 8
private const val SALSA_NONCE_WORDS = 4

/** `{ dkLen: n }` for noble's BLAKE2b. */
private fun outputLength(bytes: Int): dynamic = js("({ dkLen: bytes })")

/** Strict RFC 8032 verification: non-canonical encodings are refused, as libsodium and the JDK do (ADR 0035). */
private val STRICT_VERIFY: dynamic = js("({ zip215: false })")

/** "expand 32-byte k", the Salsa20 constant, as four little-endian words. */
private val SIGMA = Uint32Array(arrayOf(0x61707865, 0x3320646e, 0x79622d32, 0x6b206574))

/** Browser and Node [Crypto] over the noble libraries (ADR 0035). */
class JsCrypto : Crypto {
    override fun generateSigningKeyPair(): SigningKeyPair {
        val seed = nobleRandomBytes(ED25519_KEY_BYTES)
        return SigningKeyPair(seed.toByteString(), ed25519.getPublicKey(seed).toByteString())
    }

    override fun signingKeyPairFromSeed(seed: ByteString): SigningKeyPair {
        require(seed.size == ED25519_KEY_BYTES) { "Ed25519 seed must be $ED25519_KEY_BYTES bytes" }
        return SigningKeyPair(seed, ed25519.getPublicKey(seed.toUint8Array()).toByteString())
    }

    override fun sign(secretSeed: ByteString, message: ByteArray): ByteString {
        require(secretSeed.size == ED25519_KEY_BYTES) { "Ed25519 seed must be $ED25519_KEY_BYTES bytes" }
        return ed25519.sign(message.toUint8Array(), secretSeed.toUint8Array()).toByteString()
    }

    override fun verify(publicKey: ByteString, message: ByteArray, signature: ByteString): Boolean {
        if (publicKey.size != ED25519_KEY_BYTES || signature.size != ED25519_SIGNATURE_BYTES) return false
        return try {
            ed25519.verify(signature.toUint8Array(), message.toUint8Array(), publicKey.toUint8Array(), STRICT_VERIFY)
        } catch (_: Throwable) {
            false
        }
    }

    override fun hash256(message: ByteArray): ByteString = blake2b(message.toUint8Array(), outputLength(HASH_BYTES)).toByteString()

    override fun randomBytes(count: Int): ByteString {
        require(count >= 0) { "count must not be negative" }
        if (count == 0) return ByteString.EMPTY
        // The platform's secure random source gives at most 65,536 bytes per call, so a larger request is filled in pieces.
        val out = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val piece = minOf(count - filled, MAX_RANDOM_CALL_BYTES)
            nobleRandomBytes(piece).toByteArray().copyInto(out, filled)
            filled += piece
        }
        return ByteString.of(out)
    }
}

/** Browser and Node [Cipher] over the noble libraries; `crypto_box_seal` is composed as libsodium defines it (ADR 0035). */
class JsCipher : Cipher {
    override fun encrypt(key: ByteString, plaintext: ByteArray, associatedData: ByteArray): ByteString {
        require(key.size == AEAD_KEY_BYTES) { "key must be $AEAD_KEY_BYTES bytes" }
        val nonce = nobleRandomBytes(AEAD_NONCE_BYTES)
        val body = xchacha20poly1305(key.toUint8Array(), nonce, associatedData.toUint8Array()).encrypt(plaintext.toUint8Array())
        return ByteString.of(nonce.toByteArray() + body.toByteArray())
    }

    override fun decrypt(key: ByteString, sealed: ByteString, associatedData: ByteArray): DecryptResult {
        if (key.size != AEAD_KEY_BYTES || sealed.size < AEAD_NONCE_BYTES + AEAD_TAG_BYTES) return DecryptResult.Failure
        val bytes = sealed.toByteArray()
        return try {
            val plain = xchacha20poly1305(
                key.toUint8Array(),
                bytes.copyOfRange(0, AEAD_NONCE_BYTES).toUint8Array(),
                associatedData.toUint8Array(),
            ).decrypt(bytes.copyOfRange(AEAD_NONCE_BYTES, bytes.size).toUint8Array())
            DecryptResult.Success(plain.toByteArray())
        } catch (_: Throwable) {
            DecryptResult.Failure
        }
    }

    override fun generateBoxKeyPair(): BoxKeyPair {
        val secret = nobleRandomBytes(X25519_KEY_BYTES)
        return BoxKeyPair(secret.toByteString(), x25519.getPublicKey(secret).toByteString())
    }

    override fun boxKeyPairFromSecret(secret: ByteString): BoxKeyPair {
        require(secret.size == X25519_KEY_BYTES) { "secret key must be $X25519_KEY_BYTES bytes" }
        return BoxKeyPair(secret, x25519.getPublicKey(secret.toUint8Array()).toByteString())
    }

    override fun sealBox(recipientPublicKey: ByteString, plaintext: ByteArray): ByteString {
        require(recipientPublicKey.size == BOX_KEY_BYTES) { "public key must be $BOX_KEY_BYTES bytes" }
        val ephemeralSecret = nobleRandomBytes(X25519_KEY_BYTES)
        val ephemeralPublic = x25519.getPublicKey(ephemeralSecret)
        val recipient = recipientPublicKey.toUint8Array()
        val sealed = xsalsa20poly1305(boxKey(x25519.getSharedSecret(ephemeralSecret, recipient)), sealNonce(ephemeralPublic, recipient))
            .encrypt(plaintext.toUint8Array())
        return ByteString.of(ephemeralPublic.toByteArray() + sealed.toByteArray())
    }

    override fun openSealedBox(recipient: BoxKeyPair, sealed: ByteString): DecryptResult {
        if (sealed.size < SEALED_BOX_OVERHEAD_BYTES) return DecryptResult.Failure
        val bytes = sealed.toByteArray()
        return try {
            val ephemeralPublic = bytes.copyOfRange(0, X25519_KEY_BYTES).toUint8Array()
            val shared = x25519.getSharedSecret(recipient.secretKey.toUint8Array(), ephemeralPublic)
            val plain = xsalsa20poly1305(boxKey(shared), sealNonce(ephemeralPublic, recipient.publicKey.toUint8Array()))
                .decrypt(bytes.copyOfRange(X25519_KEY_BYTES, bytes.size).toUint8Array())
            DecryptResult.Success(plain.toByteArray())
        } catch (_: Throwable) {
            DecryptResult.Failure
        }
    }

    /** libsodium's nonce for a sealed box: BLAKE2b with a 24-byte output of the ephemeral key followed by the recipient's. */
    private fun sealNonce(ephemeralPublic: Uint8Array, recipientPublic: Uint8Array): Uint8Array =
        blake2b((ephemeralPublic.toByteArray() + recipientPublic.toByteArray()).toUint8Array(), outputLength(BLAKE2B_NONCE_BYTES))

    /** `crypto_box_beforenm`: the shared secret through HSalsa20 with a zero nonce gives the 32-byte box key. */
    private fun boxKey(sharedSecret: Uint8Array): Uint8Array {
        val aligned = Uint8Array(sharedSecret) // a fresh copy, so the buffer starts at offset 0 and can be read as words
        val out = Uint32Array(SALSA_KEY_WORDS)
        hsalsa(SIGMA, Uint32Array(aligned.buffer, 0, SALSA_KEY_WORDS), Uint32Array(SALSA_NONCE_WORDS), out)
        return Uint8Array(out.buffer)
    }
}
