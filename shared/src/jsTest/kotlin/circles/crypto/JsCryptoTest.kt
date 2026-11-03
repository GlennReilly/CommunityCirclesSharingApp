package circles.crypto

class JsCryptoTest : CryptoContract() {
    override suspend fun newCrypto(): Crypto {
        return JsCrypto()
    }
}

class JsCipherTest : CipherContract() {
    override suspend fun newCipher(): Cipher {
        return JsCipher()
    }
}
