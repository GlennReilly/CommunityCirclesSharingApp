@file:JsModule("@noble/curves/ed25519.js")
@file:JsNonModule

package circles.crypto

import org.khronos.webgl.Uint8Array

/** The parts of `@noble/curves` we call (ADR 0035). */
internal external interface Ed25519Api {
    fun getPublicKey(secretKey: Uint8Array): Uint8Array

    fun sign(message: Uint8Array, secretKey: Uint8Array): Uint8Array

    fun verify(signature: Uint8Array, message: Uint8Array, publicKey: Uint8Array, options: dynamic): Boolean
}

internal external interface X25519Api {
    fun getPublicKey(secretKey: Uint8Array): Uint8Array

    fun getSharedSecret(secretKey: Uint8Array, publicKey: Uint8Array): Uint8Array
}

internal external val ed25519: Ed25519Api

internal external val x25519: X25519Api
