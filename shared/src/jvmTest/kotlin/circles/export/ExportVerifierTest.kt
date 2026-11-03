package circles.export

import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.crypto.JvmCrypto
import circles.crypto.LibsodiumCipher

class ExportVerifierTest : ExportVerifierContract() {
    override suspend fun newCrypto(): Crypto = JvmCrypto()
    override suspend fun newCipher(): Cipher = LibsodiumCipher()
}
