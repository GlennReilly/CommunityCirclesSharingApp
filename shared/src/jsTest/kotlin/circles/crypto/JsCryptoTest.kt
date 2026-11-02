package circles.crypto

class JsCryptoTest : CryptoContract() {
    override suspend fun newCrypto(): Crypto {
        awaitSodium()
        return JsCrypto()
    }
}

class JsCipherTest : CipherContract() {
    override suspend fun newCipher(): Cipher {
        awaitSodium()
        return JsCipher()
    }
}
