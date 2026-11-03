@file:JsModule("@noble/ciphers/chacha.js")
@file:JsNonModule

package circles.crypto

import org.khronos.webgl.Uint8Array

/** `@noble/ciphers` XChaCha20-Poly1305: `xchacha20poly1305(key, nonce, aad)` returns an object with `encrypt` and `decrypt`. */
internal external interface Aead {
    fun encrypt(plaintext: Uint8Array): Uint8Array

    /** Throws if the tag does not verify. */
    fun decrypt(ciphertext: Uint8Array): Uint8Array
}

internal external fun xchacha20poly1305(key: Uint8Array, nonce: Uint8Array, additionalData: Uint8Array): Aead
