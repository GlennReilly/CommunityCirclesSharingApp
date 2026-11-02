package circles.event

import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.crypto.JvmCrypto
import circles.crypto.LibsodiumCipher

class ItemKeysTest : ItemKeysContract() {
    override suspend fun newCrypto(): Crypto = JvmCrypto()
    override suspend fun newCipher(): Cipher = LibsodiumCipher()
}
