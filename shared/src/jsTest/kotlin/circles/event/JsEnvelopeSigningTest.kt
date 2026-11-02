package circles.event

import circles.crypto.Crypto
import circles.crypto.JsCrypto
import circles.crypto.awaitSodium

class JsEnvelopeSigningTest : EnvelopeSigningContract() {
    override suspend fun newCrypto(): Crypto {
        awaitSodium()
        return JsCrypto()
    }
}
