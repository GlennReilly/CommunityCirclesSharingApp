package circles.api

import circles.crypto.Crypto
import circles.crypto.JsCrypto
import circles.crypto.awaitSodium

class JsAdminAuthTest : AdminAuthContract() {
    override suspend fun newCrypto(): Crypto {
        awaitSodium()
        return JsCrypto()
    }
}
