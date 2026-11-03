package circles.api

import circles.crypto.Crypto
import circles.crypto.JvmCrypto

class AdminAuthTest : AdminAuthContract() {
    override suspend fun newCrypto(): Crypto = JvmCrypto()
}
