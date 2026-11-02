package circles.crypto

import circles.encoding.ByteString
import kotlinx.coroutines.await
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array

private fun ByteArray.toUint8Array(): Uint8Array {
    val view = unsafeCast<Int8Array>()
    return Uint8Array(view.buffer, view.byteOffset, view.length)
}

private fun ByteString.toUint8Array(): Uint8Array = toByteArray().toUint8Array()

private fun Uint8Array.toByteString(): ByteString =
    ByteString.of(Int8Array(buffer, byteOffset, length).unsafeCast<ByteArray>())


/** Waits for libsodium's WebAssembly to load; call once before using [JsCrypto] or [JsCipher]. */
suspend fun awaitSodium() {
    Sodium.ready.await()
}

/** Browser and Node [Crypto] over libsodium-wrappers (ADR 0018). Call [awaitSodium] first. */
class JsCrypto : Crypto {
    override fun generateSigningKeyPair(): SigningKeyPair {
        val seed = Sodium.randombytes_buf(ED25519_KEY_BYTES)
        return SigningKeyPair(seed.toByteString(), Sodium.crypto_sign_seed_keypair(seed).publicKey.toByteString())
    }

    override fun sign(secretSeed: ByteString, message: ByteArray): ByteString {
        require(secretSeed.size == ED25519_KEY_BYTES) { "Ed25519 seed must be $ED25519_KEY_BYTES bytes" }
        val secretKey = Sodium.crypto_sign_seed_keypair(secretSeed.toUint8Array()).privateKey
        return Sodium.crypto_sign_detached(message.toUint8Array(), secretKey).toByteString()
    }

    override fun verify(publicKey: ByteString, message: ByteArray, signature: ByteString): Boolean {
        if (publicKey.size != ED25519_KEY_BYTES || signature.size != ED25519_SIGNATURE_BYTES) return false
        return try {
            Sodium.crypto_sign_verify_detached(signature.toUint8Array(), message.toUint8Array(), publicKey.toUint8Array())
        } catch (_: Throwable) {
            false
        }
    }

    override fun hash256(message: ByteArray): ByteString =
        Sodium.crypto_generichash(HASH_BYTES, message.toUint8Array()).toByteString()

    override fun randomBytes(count: Int): ByteString =
        if (count == 0) ByteString.EMPTY else Sodium.randombytes_buf(count).toByteString()
}

/** Browser and Node [Cipher] over libsodium-wrappers (ADR 0018). Call [awaitSodium] first. */
class JsCipher : Cipher {
    override fun encrypt(key: ByteString, plaintext: ByteArray, associatedData: ByteArray): ByteString {
        require(key.size == AEAD_KEY_BYTES) { "key must be $AEAD_KEY_BYTES bytes" }
        val nonce = Sodium.randombytes_buf(AEAD_NONCE_BYTES)
        val body = Sodium.crypto_aead_xchacha20poly1305_ietf_encrypt(
            plaintext.toUint8Array(), associatedData.toUint8Array(), null, nonce, key.toUint8Array(),
        )
        return ByteString.of(nonce.toByteString().toByteArray() + body.toByteString().toByteArray())
    }

    override fun decrypt(key: ByteString, sealed: ByteString, associatedData: ByteArray): DecryptResult {
        if (key.size != AEAD_KEY_BYTES || sealed.size < AEAD_NONCE_BYTES + AEAD_TAG_BYTES) return DecryptResult.Failure
        val bytes = sealed.toByteArray()
        return try {
            val plain = Sodium.crypto_aead_xchacha20poly1305_ietf_decrypt(
                null, bytes.copyOfRange(AEAD_NONCE_BYTES, bytes.size).toUint8Array(), associatedData.toUint8Array(),
                bytes.copyOfRange(0, AEAD_NONCE_BYTES).toUint8Array(), key.toUint8Array(),
            )
            DecryptResult.Success(plain.toByteString().toByteArray())
        } catch (_: Throwable) {
            DecryptResult.Failure
        }
    }

    override fun generateBoxKeyPair(): BoxKeyPair {
        val pair = Sodium.crypto_box_keypair()
        return BoxKeyPair(pair.privateKey.toByteString(), pair.publicKey.toByteString())
    }

    override fun sealBox(recipientPublicKey: ByteString, plaintext: ByteArray): ByteString {
        require(recipientPublicKey.size == BOX_KEY_BYTES) { "public key must be $BOX_KEY_BYTES bytes" }
        return Sodium.crypto_box_seal(plaintext.toUint8Array(), recipientPublicKey.toUint8Array()).toByteString()
    }

    override fun openSealedBox(recipient: BoxKeyPair, sealed: ByteString): DecryptResult {
        if (sealed.size < SEALED_BOX_OVERHEAD_BYTES) return DecryptResult.Failure
        return try {
            val plain = Sodium.crypto_box_seal_open(
                sealed.toUint8Array(), recipient.publicKey.toUint8Array(), recipient.secretKey.toUint8Array(),
            )
            DecryptResult.Success(plain.toByteString().toByteArray())
        } catch (_: Throwable) {
            DecryptResult.Failure
        }
    }
}
