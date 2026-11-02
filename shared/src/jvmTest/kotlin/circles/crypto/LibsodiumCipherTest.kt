package circles.crypto

class LibsodiumCipherTest : CipherContract() {
    override suspend fun newCipher(): Cipher = LibsodiumCipher()
}
