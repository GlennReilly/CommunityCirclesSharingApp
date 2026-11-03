package circles.keys

import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.encoding.ByteString
import circles.event.DeviceIdentity
import circles.event.DeviceSeed
import circles.event.newDeviceId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val PASSPHRASE = "correct horse battery staple"
private val FAST = Argon2Params(memoryKib = 1_024, iterations = 1, parallelism = 1)

/** Argon2id and the seed wrapping (ADR 0039), on every Crypto/Cipher/hasher combination. */
abstract class SeedWrappingContract {
    protected abstract suspend fun newCrypto(): Crypto
    protected abstract suspend fun newCipher(): Cipher
    protected abstract fun newHasher(): PasswordHasher

    private fun hex(text: String) = ByteString.of(ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
    private val salt = ByteString.of(ByteArray(16) { it.toByte() })

    // From libsodium (PyNaCl crypto_pwhash_alg, Argon2id 1.3); see tools/vectors/argon2_vectors.py.
    @Test fun matchesLibsodiumOnASmallCost() = runTest {
        val out = newHasher().argon2id(PASSPHRASE, salt, FAST, 32)
        assertEquals(hex("f0d1525842159cef972608e2f8bc1cb2c8230f81eea76f124789f6beb42403b2"), out)
    }

    @Test fun matchesLibsodiumAtTheDefaultCost() = runTest {
        val out = newHasher().argon2id(PASSPHRASE, salt, Argon2Params.DEFAULT, 32)
        assertEquals(hex("0d1a3c6523c8f06e4e0af9c515aa5b5448cfebd6838f2d52c3d8b6ef8ddc3c2e"), out)
    }

    @Test fun composedAndDecomposedLettersGiveTheSameKey() = runTest {
        val composed = "café au lait 123"
        val decomposed = "café au lait 123"
        val hasher = newHasher()
        val a = hasher.argon2id(composed, salt, FAST, 32)
        assertEquals(a, hasher.argon2id(decomposed, salt, FAST, 32))
        // From libsodium on the NFKC form.
        assertEquals(hex("71138b00bac8fad47e1120fb46c212d488831036cbfa1a8421d297d4f3bbe27b"), a)
    }

    private suspend fun fixture() = Triple(newCrypto(), newCipher(), newHasher())

    @Test fun aWrappedSeedOpensWithTheRightPassphrase() = runTest {
        val (crypto, cipher, hasher) = fixture()
        val identity = DeviceIdentity.generate(crypto, cipher)
        val wrapped = SeedWrapping.wrapWithPassphrase(crypto, cipher, hasher, identity.seed!!, identity.device, PASSPHRASE, FAST)
        val opened = assertIs<UnwrapResult.Opened>(SeedWrapping.unwrapWithPassphrase(cipher, hasher, wrapped, identity.device, PASSPHRASE))
        assertEquals(identity.seed, opened.seed)
        // The restored seed gives the same keys.
        assertEquals(identity.signing.publicKey, DeviceSeed.signingKeyPair(crypto, opened.seed).publicKey)
    }

    @Test fun aWrongPassphraseIsRefused() = runTest {
        val (crypto, cipher, hasher) = fixture()
        val seed = DeviceSeed.newSeed(crypto)
        val device = newDeviceId(crypto)
        val wrapped = SeedWrapping.wrapWithPassphrase(crypto, cipher, hasher, seed, device, PASSPHRASE, FAST)
        assertEquals(UnwrapResult.WrongPassphraseOrDamaged, SeedWrapping.unwrapWithPassphrase(cipher, hasher, wrapped, device, PASSPHRASE + "x"))
    }

    @Test fun theBlobIsBoundToItsDevice() = runTest {
        val (crypto, cipher, hasher) = fixture()
        val wrapped = SeedWrapping.wrapWithPassphrase(crypto, cipher, hasher, DeviceSeed.newSeed(crypto), newDeviceId(crypto), PASSPHRASE, FAST)
        assertEquals(UnwrapResult.WrongPassphraseOrDamaged, SeedWrapping.unwrapWithPassphrase(cipher, hasher, wrapped, newDeviceId(crypto), PASSPHRASE))
    }

    @Test fun changingTheStoredCostSettingsBreaksTheBlob() = runTest {
        val (crypto, cipher, hasher) = fixture()
        val device = newDeviceId(crypto)
        val wrapped = SeedWrapping.wrapWithPassphrase(crypto, cipher, hasher, DeviceSeed.newSeed(crypto), device, PASSPHRASE, FAST)
        val weaker = wrapped.copy(iterations = 1, memoryKib = 2_048)
        assertEquals(UnwrapResult.WrongPassphraseOrDamaged, SeedWrapping.unwrapWithPassphrase(cipher, hasher, weaker, device, PASSPHRASE))
    }

    @Test fun aDamagedBlobIsRefusedNotACrash() = runTest {
        val (crypto, cipher, hasher) = fixture()
        val device = newDeviceId(crypto)
        val wrapped = SeedWrapping.wrapWithPassphrase(crypto, cipher, hasher, DeviceSeed.newSeed(crypto), device, PASSPHRASE, FAST)
        for (bad in listOf(wrapped.copy(salt = "!!"), wrapped.copy(salt = "AAAA"), wrapped.copy(sealed = "!!"), wrapped.copy(sealed = wrapped.sealed.dropLast(4) + "AAAA"))) {
            assertEquals(UnwrapResult.WrongPassphraseOrDamaged, SeedWrapping.unwrapWithPassphrase(cipher, hasher, bad, device, PASSPHRASE))
        }
    }

    @Test fun unknownVersionsMethodsAndGreedySettingsAreUnsupportedAndCostNothing() = runTest {
        val (crypto, cipher, hasher) = fixture()
        val device = newDeviceId(crypto)
        val wrapped = SeedWrapping.wrapWithPassphrase(crypto, cipher, hasher, DeviceSeed.newSeed(crypto), device, PASSPHRASE, FAST)
        assertIs<UnwrapResult.Unsupported>(SeedWrapping.unwrapWithPassphrase(cipher, hasher, wrapped.copy(v = 2), device, PASSPHRASE))
        assertIs<UnwrapResult.Unsupported>(SeedWrapping.unwrapWithPassphrase(cipher, hasher, wrapped.copy(method = "passkey-prf"), device, PASSPHRASE))
        assertIs<UnwrapResult.Unsupported>(SeedWrapping.unwrapWithPassphrase(cipher, hasher, wrapped.copy(memoryKib = Argon2Params.MAX_MEMORY_KIB + 1), device, PASSPHRASE))
        assertIs<UnwrapResult.Unsupported>(SeedWrapping.unwrapWithPassphrase(cipher, hasher, wrapped.copy(iterations = 1_000), device, PASSPHRASE))
        assertIs<UnwrapResult.Unsupported>(SeedWrapping.unwrapWithPassphrase(cipher, hasher, wrapped.copy(parallelism = 4), device, PASSPHRASE))
    }

    @Test fun eachWrapUsesAFreshSalt() = runTest {
        val (crypto, cipher, hasher) = fixture()
        val seed = DeviceSeed.newSeed(crypto)
        val device = newDeviceId(crypto)
        val a = SeedWrapping.wrapWithPassphrase(crypto, cipher, hasher, seed, device, PASSPHRASE, FAST)
        val b = SeedWrapping.wrapWithPassphrase(crypto, cipher, hasher, seed, device, PASSPHRASE, FAST)
        assertNotEquals(a.salt, b.salt)
        assertNotEquals(a.sealed, b.sealed)
    }

    @Test fun nothingSecretIsInTheStoredText() = runTest {
        val (crypto, cipher, hasher) = fixture()
        val seed = DeviceSeed.newSeed(crypto)
        val wrapped = SeedWrapping.wrapWithPassphrase(crypto, cipher, hasher, seed, newDeviceId(crypto), PASSPHRASE, FAST)
        val text = wrapped.toString()
        assertTrue(seed.toBase64Url() !in text && seed.toHex() !in text && PASSPHRASE !in text)
    }

    @Test fun shortPassphrasesAreRefusedLongOnesAccepted() = runTest {
        val (crypto, cipher, hasher) = fixture()
        assertEquals(PassphraseCheck.TooShort(MIN_PASSPHRASE_CHARS), SeedWrapping.checkPassphrase("a".repeat(MIN_PASSPHRASE_CHARS - 1)))
        assertEquals(PassphraseCheck.Fine, SeedWrapping.checkPassphrase("a".repeat(MIN_PASSPHRASE_CHARS)))
        assertFailsWith<IllegalArgumentException> {
            SeedWrapping.wrapWithPassphrase(crypto, cipher, hasher, DeviceSeed.newSeed(crypto), newDeviceId(crypto), "short", FAST)
        }
    }

    @Test fun greedyOrTinySettingsCannotBeUsedToWrap() = runTest {
        val (crypto, cipher, hasher) = fixture()
        assertFailsWith<IllegalArgumentException> {
            SeedWrapping.wrapWithPassphrase(crypto, cipher, hasher, DeviceSeed.newSeed(crypto), newDeviceId(crypto), PASSPHRASE, Argon2Params(8, 1, 1))
        }
    }
}
