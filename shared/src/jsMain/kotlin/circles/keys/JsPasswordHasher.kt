package circles.keys

import circles.encoding.ByteString
import kotlinx.coroutines.await
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array

/** Argon2id with noble (ADR 0035); libsodium is the oracle in the tests. */
class JsPasswordHasher : PasswordHasher {
    override suspend fun argon2id(passphrase: String, salt: ByteString, params: Argon2Params, outputBytes: Int): ByteString {
        val normalised = passphrase.asDynamic().normalize("NFKC").unsafeCast<String>()
        val options = js("({})")
        options.t = params.iterations
        options.m = params.memoryKib
        options.p = params.parallelism
        options.dkLen = outputBytes
        val out = nobleArgon2idAsync(normalised.encodeToByteArray().toUint8(), salt.toByteArray().toUint8(), options).await()
        return ByteString.of(Int8Array(out.buffer, out.byteOffset, out.length).unsafeCast<ByteArray>().copyOf())
    }

    private fun ByteArray.toUint8(): Uint8Array {
        val out = Uint8Array(size)
        for (i in indices) out.asDynamic()[i] = this[i].toInt() and 0xFF
        return out
    }
}
