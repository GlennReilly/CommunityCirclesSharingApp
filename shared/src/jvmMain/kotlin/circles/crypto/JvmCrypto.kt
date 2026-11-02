package circles.crypto

import circles.encoding.ByteString
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import org.bouncycastle.crypto.digests.Blake2bDigest

private const val ED25519 = "Ed25519"
private const val BITS_PER_BYTE = 8

/** Fixed DER prefixes that wrap a raw 32-byte Ed25519 key for the JDK's key factory (RFC 8410). */
private val PKCS8_PREFIX = "302e020100300506032b657004220420".hexBytes()
private val X509_PREFIX = "302a300506032b6570032100".hexBytes()

private fun String.hexBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** JVM implementation of [Crypto] with JDK Ed25519 and BouncyCastle BLAKE2b (ADR 0010). */
class JvmCrypto(private val random: SecureRandom = SecureRandom()) : Crypto {
    override fun generateSigningKeyPair(): SigningKeyPair {
        val pair = KeyPairGenerator.getInstance(ED25519).generateKeyPair()
        val seed = pair.private.encoded.copyOfRange(PKCS8_PREFIX.size, pair.private.encoded.size)
        val publicKey = pair.public.encoded.copyOfRange(X509_PREFIX.size, pair.public.encoded.size)
        return SigningKeyPair(ByteString.of(seed), ByteString.of(publicKey))
    }

    override fun sign(secretSeed: ByteString, message: ByteArray): ByteString {
        require(secretSeed.size == ED25519_KEY_BYTES) { "Ed25519 seed must be $ED25519_KEY_BYTES bytes" }
        val key = KeyFactory.getInstance(ED25519).generatePrivate(PKCS8EncodedKeySpec(PKCS8_PREFIX + secretSeed.toByteArray()))
        val signer = Signature.getInstance(ED25519)
        signer.initSign(key)
        signer.update(message)
        return ByteString.of(signer.sign())
    }

    override fun verify(publicKey: ByteString, message: ByteArray, signature: ByteString): Boolean {
        if (publicKey.size != ED25519_KEY_BYTES || signature.size != ED25519_SIGNATURE_BYTES) return false
        return try {
            val key = KeyFactory.getInstance(ED25519).generatePublic(X509EncodedKeySpec(X509_PREFIX + publicKey.toByteArray()))
            val verifier = Signature.getInstance(ED25519)
            verifier.initVerify(key)
            verifier.update(message)
            verifier.verify(signature.toByteArray())
        } catch (_: GeneralSecurityException) {
            false
        }
    }

    override fun hash256(message: ByteArray): ByteString {
        val digest = Blake2bDigest(HASH_BYTES * BITS_PER_BYTE)
        digest.update(message, 0, message.size)
        val out = ByteArray(HASH_BYTES)
        digest.doFinal(out, 0)
        return ByteString.of(out)
    }

    override fun randomBytes(count: Int): ByteString = ByteString.of(ByteArray(count).also { random.nextBytes(it) })
}
