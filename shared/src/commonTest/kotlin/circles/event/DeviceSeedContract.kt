package circles.event

import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.encoding.ByteString
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Device keys derived from one seed (ADR 0039), on every Crypto/Cipher pair. */
abstract class DeviceSeedContract {
    protected abstract suspend fun newCrypto(): Crypto
    protected abstract suspend fun newCipher(): Cipher

    private val seed = ByteString.of(ByteArray(DEVICE_SEED_BYTES) { it.toByte() })

    private fun hex(text: String) = ByteString.of(ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() })

    // From tools/vectors/device_seed_vectors.py (hashlib BLAKE2b, `cryptography` Ed25519 and X25519).
    @Test fun matchesTheIndependentPythonVectors() = runTest {
        val crypto = newCrypto()
        val cipher = newCipher()
        val sign = DeviceSeed.signingKeyPair(crypto, seed)
        val box = DeviceSeed.boxKeyPair(crypto, cipher, seed)
        assertEquals(hex("46999491635cc50a8f67852e0002d79d87688c4cec6df78aa3ac93966aa8994f"), sign.secretSeed)
        assertEquals(hex("621a21aa38e1da04ccacae7f3611b9958408888fcb5e13a508fde35e2381ee56"), sign.publicKey)
        assertEquals(hex("b0405f7c977214bda906a1f7d1a9879587acf0fa1dc2c327afe5e0985be9ff79"), box.secretKey)
        assertEquals(hex("31022c9b383df8157861819a588b63c4fd7e356832ae384e039fd5b54901247a"), box.publicKey)
    }

    @Test fun theSameSeedGivesTheSameIdentityKeys() = runTest {
        val crypto = newCrypto()
        val cipher = newCipher()
        val member = newMemberId(crypto)
        val device = newDeviceId(crypto)
        val a = DeviceIdentity.fromSeed(crypto, cipher, seed, member, device)
        val b = DeviceIdentity.fromSeed(crypto, cipher, seed, member, device)
        assertEquals(a.signing.publicKey, b.signing.publicKey)
        assertEquals(a.boxPub, b.boxPub)
        assertEquals(seed, a.seed)
    }

    @Test fun differentSeedsGiveDifferentKeys() = runTest {
        val crypto = newCrypto()
        val cipher = newCipher()
        val a = DeviceIdentity.generate(crypto, cipher)
        val b = DeviceIdentity.generate(crypto, cipher)
        assertNotEquals(a.seed, b.seed)
        assertNotEquals(a.signing.publicKey, b.signing.publicKey)
        assertNotEquals(a.boxPub, b.boxPub)
    }

    @Test fun theSigningAndBoxSecretsAreUnrelated() = runTest {
        val crypto = newCrypto()
        assertNotEquals(DeviceSeed.derive(crypto, seed, "sign"), DeviceSeed.derive(crypto, seed, "box"))
    }

    @Test fun aGeneratedIdentityKeepsItsSeedAndRestoresFromIt() = runTest {
        val crypto = newCrypto()
        val cipher = newCipher()
        val original = DeviceIdentity.generate(crypto, cipher)
        val restored = DeviceIdentity.fromSeed(crypto, cipher, original.seed!!, original.member, original.device)
        assertEquals(original.signing.publicKey, restored.signing.publicKey)
        assertEquals(original.boxPub, restored.boxPub)
        assertEquals(original.boxSecret, restored.boxSecret)
    }

    @Test fun aRestoredIdentitySignsAndOpensLikeTheOriginal() = runTest {
        val crypto = newCrypto()
        val cipher = newCipher()
        val original = DeviceIdentity.generate(crypto, cipher)
        val restored = DeviceIdentity.fromSeed(crypto, cipher, original.seed!!, original.member, original.device)
        val message = "hello".encodeToByteArray()
        assertTrue(crypto.verify(original.signing.publicKey, message, crypto.sign(restored.signing.secretSeed, message)))
        val sealed = cipher.sealBox(original.boxPub, message)
        val opened = cipher.openSealedBox(restored.boxKeys, sealed)
        assertTrue(opened is circles.crypto.DecryptResult.Success && opened.plaintext.contentEquals(message))
    }

    @Test fun theSeedMustBe32Bytes() = runTest {
        val crypto = newCrypto()
        assertFailsWith<IllegalArgumentException> { DeviceSeed.derive(crypto, ByteString.of(ByteArray(31)), "sign") }
        assertFailsWith<IllegalArgumentException> { crypto.signingKeyPairFromSeed(ByteString.of(ByteArray(31))) }
    }
}
