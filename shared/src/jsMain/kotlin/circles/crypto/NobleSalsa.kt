@file:JsModule("@noble/ciphers/salsa.js")
@file:JsNonModule

package circles.crypto

import org.khronos.webgl.Uint32Array
import org.khronos.webgl.Uint8Array

/** `@noble/ciphers` Salsa20 family, for the sealed box (ADR 0035). */
internal external fun hsalsa(sigma: Uint32Array, key: Uint32Array, input: Uint32Array, out: Uint32Array)

internal external fun xsalsa20poly1305(key: Uint8Array, nonce: Uint8Array): Aead
