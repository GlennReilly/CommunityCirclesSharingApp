package circles.crypto

class JvmCryptoTest : CryptoContract() {
    override suspend fun newCrypto(): Crypto = JvmCrypto()
}
