package circles.event

import circles.crypto.Crypto
import circles.crypto.JvmCrypto

class EnvelopeSigningTest : EnvelopeSigningContract() {
    override suspend fun newCrypto(): Crypto = JvmCrypto()
}
