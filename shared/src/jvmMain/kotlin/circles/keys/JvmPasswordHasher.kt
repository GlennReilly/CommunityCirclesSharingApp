package circles.keys

import circles.encoding.ByteString
import java.text.Normalizer
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

/** Argon2id with BouncyCastle (already on the JVM classpath for BLAKE2b, ADR 0010). */
class JvmPasswordHasher : PasswordHasher {
    override suspend fun argon2id(passphrase: String, salt: ByteString, params: Argon2Params, outputBytes: Int): ByteString {
        val settings = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withIterations(params.iterations)
            .withMemoryAsKB(params.memoryKib)
            .withParallelism(params.parallelism)
            .withSalt(salt.toByteArray())
            .build()
        val generator = Argon2BytesGenerator().also { it.init(settings) }
        val out = ByteArray(outputBytes)
        generator.generateBytes(Normalizer.normalize(passphrase, Normalizer.Form.NFKC).toByteArray(Charsets.UTF_8), out)
        return ByteString.of(out)
    }
}
