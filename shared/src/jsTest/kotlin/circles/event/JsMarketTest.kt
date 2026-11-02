package circles.event

import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.crypto.JsCipher
import circles.crypto.JsCrypto
import circles.crypto.awaitSodium

class JsMarketTest : MarketContract() {
    override suspend fun newCrypto(): Crypto {
        awaitSodium()
        return JsCrypto()
    }

    override suspend fun newCipher(): Cipher {
        awaitSodium()
        return JsCipher()
    }
}
