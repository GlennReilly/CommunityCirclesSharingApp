package circles.event

import circles.crypto.BoxKeyPair
import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.crypto.SigningKeyPair
import circles.encoding.ByteString

const val DEVICE_SEED_BYTES = 32
const val DEVICE_SEED_DOMAIN = "circles/device-seed/v1/"
private const val LABEL_SIGN = "sign"
private const val LABEL_BOX = "box"

/**
 * A device's two secrets are derived from one 32-byte seed (ADR 0039), so that the seed alone, wrapped on the device
 * and optionally written down as a recovery code, restores the identity. Each secret is the BLAKE2b-256 hash of a
 * labelled domain string followed by the seed; the labels keep the two secrets unrelated.
 */
object DeviceSeed {
    fun newSeed(crypto: Crypto): ByteString = crypto.randomBytes(DEVICE_SEED_BYTES)

    fun signingKeyPair(crypto: Crypto, seed: ByteString): SigningKeyPair = crypto.signingKeyPairFromSeed(derive(crypto, seed, LABEL_SIGN))

    fun boxKeyPair(crypto: Crypto, cipher: Cipher, seed: ByteString): BoxKeyPair = cipher.boxKeyPairFromSecret(derive(crypto, seed, LABEL_BOX))

    fun derive(crypto: Crypto, seed: ByteString, label: String): ByteString {
        require(seed.size == DEVICE_SEED_BYTES) { "a device seed is $DEVICE_SEED_BYTES bytes" }
        return crypto.hash256((DEVICE_SEED_DOMAIN + label).encodeToByteArray() + seed.toByteArray())
    }
}
