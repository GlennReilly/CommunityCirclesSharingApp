@file:JsModule("@noble/ciphers/utils.js")
@file:JsNonModule

package circles.crypto

import org.khronos.webgl.Uint8Array

/** Bytes from the platform's secure random source; throws if there is none (ADR 0035). */
@JsName("randomBytes")
internal external fun nobleRandomBytes(count: Int): Uint8Array
