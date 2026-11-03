package circles.crypto

import circles.encoding.ByteString
import com.goterl.lazysodium.LazySodiumJava
import com.goterl.lazysodium.SodiumJava

/** [Cipher] over libsodium through lazysodium. Test scope only (ADR 0018). */
class LibsodiumCipher : Cipher {
    private val sodium = LazySodiumJava(SodiumJava())

    override fun encrypt(key: ByteString, plaintext: ByteArray, associatedData: ByteArray): ByteString {
        require(key.size == AEAD_KEY_BYTES) { "key must be $AEAD_KEY_BYTES bytes" }
        val nonce = sodium.randomBytesBuf(AEAD_NONCE_BYTES)
        val out = ByteArray(plaintext.size + AEAD_TAG_BYTES)
        val ok = sodium.cryptoAeadXChaCha20Poly1305IetfEncrypt(
            out, longArrayOf(0), plaintext, plaintext.size.toLong(), associatedData, associatedData.size.toLong(),
            null, nonce, key.toByteArray(),
        )
        check(ok) { "encryption failed" }
        return ByteString.of(nonce + out)
    }

    override fun decrypt(key: ByteString, sealed: ByteString, associatedData: ByteArray): DecryptResult {
        if (key.size != AEAD_KEY_BYTES || sealed.size < AEAD_NONCE_BYTES + AEAD_TAG_BYTES) return DecryptResult.Failure
        val bytes = sealed.toByteArray()
        val nonce = bytes.copyOfRange(0, AEAD_NONCE_BYTES)
        val body = bytes.copyOfRange(AEAD_NONCE_BYTES, bytes.size)
        val out = ByteArray(body.size - AEAD_TAG_BYTES)
        val ok = sodium.cryptoAeadXChaCha20Poly1305IetfDecrypt(
            out, longArrayOf(0), null, body, body.size.toLong(), associatedData, associatedData.size.toLong(),
            nonce, key.toByteArray(),
        )
        return if (ok) DecryptResult.Success(out) else DecryptResult.Failure
    }

    override fun boxKeyPairFromSecret(secret: ByteString): BoxKeyPair {
        require(secret.size == BOX_KEY_BYTES) { "secret key must be $BOX_KEY_BYTES bytes" }
        val pk = ByteArray(BOX_KEY_BYTES)
        check(sodium.cryptoScalarMultBase(pk, secret.toByteArray())) { "public key derivation failed" }
        return BoxKeyPair(secret, ByteString.of(pk))
    }

    override fun generateBoxKeyPair(): BoxKeyPair {
        val pk = ByteArray(BOX_KEY_BYTES)
        val sk = ByteArray(BOX_KEY_BYTES)
        check(sodium.cryptoBoxKeypair(pk, sk)) { "key generation failed" }
        return BoxKeyPair(ByteString.of(sk), ByteString.of(pk))
    }

    override fun sealBox(recipientPublicKey: ByteString, plaintext: ByteArray): ByteString {
        require(recipientPublicKey.size == BOX_KEY_BYTES) { "public key must be $BOX_KEY_BYTES bytes" }
        val out = ByteArray(plaintext.size + SEALED_BOX_OVERHEAD_BYTES)
        check(sodium.cryptoBoxSeal(out, plaintext, plaintext.size.toLong(), recipientPublicKey.toByteArray())) {
            "sealing failed"
        }
        return ByteString.of(out)
    }

    override fun openSealedBox(recipient: BoxKeyPair, sealed: ByteString): DecryptResult {
        if (sealed.size < SEALED_BOX_OVERHEAD_BYTES) return DecryptResult.Failure
        val out = ByteArray(sealed.size - SEALED_BOX_OVERHEAD_BYTES)
        val ok = sodium.cryptoBoxSealOpen(
            out, sealed.toByteArray(), sealed.size.toLong(),
            recipient.publicKey.toByteArray(), recipient.secretKey.toByteArray(),
        )
        return if (ok) DecryptResult.Success(out) else DecryptResult.Failure
    }
}
