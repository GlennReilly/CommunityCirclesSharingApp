package circles.event

import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.crypto.JsCipher
import circles.crypto.JsCrypto

class JsDeviceSeedTest : DeviceSeedContract() {
    override suspend fun newCrypto(): Crypto = JsCrypto()
    override suspend fun newCipher(): Cipher = JsCipher()
}
