package circles.crypto

import circles.encoding.ByteString

/**
 * A [Cipher] for code that must never decrypt, such as the server verifying an export (ADR 0030): it opens nothing
 * (every decrypt and open is a [DecryptResult.Failure]) and refuses to encrypt or make keys.
 */
object RefusingCipher : Cipher {
    override fun encrypt(key: ByteString, plaintext: ByteArray, associatedData: ByteArray): ByteString = refuse()

    override fun decrypt(key: ByteString, sealed: ByteString, associatedData: ByteArray): DecryptResult = DecryptResult.Failure

    override fun generateBoxKeyPair(): BoxKeyPair = refuse()

    override fun boxKeyPairFromSecret(secret: ByteString): BoxKeyPair = refuse()

    override fun sealBox(recipientPublicKey: ByteString, plaintext: ByteArray): ByteString = refuse()

    override fun openSealedBox(recipient: BoxKeyPair, sealed: ByteString): DecryptResult = DecryptResult.Failure

    private fun refuse(): Nothing = throw IllegalStateException("this code must not encrypt")
}
