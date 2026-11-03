@file:JsModule("@noble/hashes/blake2.js")
@file:JsNonModule

package circles.crypto

import org.khronos.webgl.Uint8Array

/** `@noble/hashes` BLAKE2b; [options] is `{ dkLen: n }` (ADR 0035). */
internal external fun blake2b(message: Uint8Array, options: dynamic): Uint8Array
