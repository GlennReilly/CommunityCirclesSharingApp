package circles.event

import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.crypto.JsCipher
import circles.crypto.JsCrypto

class JsItemKeysTest : ItemKeysContract() {
    override suspend fun newCrypto(): Crypto {
        return JsCrypto()
    }

    override suspend fun newCipher(): Cipher {
        return JsCipher()
    }
}
