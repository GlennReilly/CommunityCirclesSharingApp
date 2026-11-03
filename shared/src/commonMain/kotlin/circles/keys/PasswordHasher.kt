package circles.keys

import circles.encoding.ByteString

/** Argon2id cost settings. They are stored with every wrapped seed so they can be raised later without breaking old blobs. */
data class Argon2Params(val memoryKib: Int, val iterations: Int, val parallelism: Int) {
    companion object {
        /**
         * Measured 1.6 s in Node on a 2012 i7-3770 (noble, pure JavaScript; libsodium takes 0.2 s); to be re-tuned on the
         * pilot's slowest phone (ADR 0039). Memory is 64 MiB.
         */
        val DEFAULT = Argon2Params(memoryKib = 65_536, iterations = 3, parallelism = 1)

        /** Limits a stored blob may ask for, so a tampered blob cannot make a device allocate gigabytes or spin for minutes. */
        const val MAX_MEMORY_KIB = 262_144
        const val MAX_ITERATIONS = 10
        const val MIN_MEMORY_KIB = 1_024
    }

    fun withinLimits(): Boolean =
        memoryKib in MIN_MEMORY_KIB..MAX_MEMORY_KIB && iterations in 1..MAX_ITERATIONS && parallelism == 1
}

/**
 * Argon2id for passphrases (RFC 9106). The passphrase is Unicode-normalised (NFKC) first, so the same words typed on a
 * different keyboard or system give the same key. Suspending, because on the web it runs in slices to stay responsive.
 */
interface PasswordHasher {
    suspend fun argon2id(passphrase: String, salt: ByteString, params: Argon2Params, outputBytes: Int): ByteString
}
