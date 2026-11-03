@file:JsModule("@noble/hashes/argon2.js")
@file:JsNonModule

package circles.keys

import org.khronos.webgl.Uint8Array
import kotlin.js.Promise

/** `@noble/hashes` Argon2id in slices (it yields to the event loop every few milliseconds); [options] is `{ t, m, p, dkLen }`. */
@JsName("argon2idAsync")
internal external fun nobleArgon2idAsync(password: Uint8Array, salt: Uint8Array, options: dynamic): Promise<Uint8Array>
