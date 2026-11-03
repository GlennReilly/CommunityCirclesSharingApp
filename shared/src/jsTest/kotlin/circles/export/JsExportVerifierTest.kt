package circles.export

import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.crypto.JsCipher
import circles.crypto.JsCrypto

class JsExportVerifierTest : ExportVerifierContract() {
    override suspend fun newCrypto(): Crypto {
        return JsCrypto()
    }

    override suspend fun newCipher(): Cipher {
        return JsCipher()
    }
}
