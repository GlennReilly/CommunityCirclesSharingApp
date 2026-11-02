package circles.crypto

import org.khronos.webgl.Uint8Array
import kotlin.js.Promise

/** The parts of `libsodium-wrappers` we call (ADR 0018). Names follow the library, hence the underscores. */
@JsModule("libsodium-wrappers")
@JsNonModule
@Suppress("FunctionName", "PropertyName", "ClassName")
internal external object Sodium {
    val ready: Promise<Any?>

    class KeyPair {
        val publicKey: Uint8Array
        val privateKey: Uint8Array
    }

    fun crypto_sign_seed_keypair(seed: Uint8Array): KeyPair
    fun crypto_sign_detached(message: Uint8Array, privateKey: Uint8Array): Uint8Array
    fun crypto_sign_verify_detached(signature: Uint8Array, message: Uint8Array, publicKey: Uint8Array): Boolean
    fun crypto_generichash(hashLength: Int, message: Uint8Array): Uint8Array
    fun randombytes_buf(length: Int): Uint8Array

    fun crypto_aead_xchacha20poly1305_ietf_encrypt(
        message: Uint8Array, additionalData: Uint8Array, secretNonce: Uint8Array?, publicNonce: Uint8Array, key: Uint8Array,
    ): Uint8Array

    fun crypto_aead_xchacha20poly1305_ietf_decrypt(
        secretNonce: Uint8Array?, ciphertext: Uint8Array, additionalData: Uint8Array, publicNonce: Uint8Array, key: Uint8Array,
    ): Uint8Array

    fun crypto_box_keypair(): KeyPair
    fun crypto_box_seal(message: Uint8Array, publicKey: Uint8Array): Uint8Array
    fun crypto_box_seal_open(ciphertext: Uint8Array, publicKey: Uint8Array, secretKey: Uint8Array): Uint8Array
}
