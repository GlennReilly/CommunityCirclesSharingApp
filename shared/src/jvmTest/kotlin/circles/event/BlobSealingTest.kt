package circles.event

import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.crypto.JvmCrypto
import circles.crypto.LibsodiumCipher

class BlobSealingTest : BlobSealingContract() {
    override suspend fun newCrypto(): Crypto = JvmCrypto()
    override suspend fun newCipher(): Cipher = LibsodiumCipher()
}
