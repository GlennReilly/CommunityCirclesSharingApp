package circles.api

import circles.crypto.Crypto
import circles.crypto.JsCrypto

class JsAdminAuthTest : AdminAuthContract() {
    override suspend fun newCrypto(): Crypto {
        return JsCrypto()
    }
}
