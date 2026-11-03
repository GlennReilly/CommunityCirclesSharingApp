package circles.event

import circles.crypto.Crypto
import circles.crypto.JsCrypto

class JsEnvelopeSigningTest : EnvelopeSigningContract() {
    override suspend fun newCrypto(): Crypto {
        return JsCrypto()
    }
}
