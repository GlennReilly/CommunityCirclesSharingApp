package circles.event

import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.crypto.JsCipher
import circles.crypto.JsCrypto

class JsCircleFoldTest : CircleFoldContract() {
    override suspend fun newCrypto(): Crypto {
        return JsCrypto()
    }

    override suspend fun newCipher(): Cipher {
        return JsCipher()
    }
}
