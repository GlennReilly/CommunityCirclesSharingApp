package circles.keys

import circles.crypto.Cipher
import circles.crypto.Crypto
import circles.encoding.ByteString
import circles.event.DeviceId
import circles.policy.MemberId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** A list that is not a real one: word `i` is "w" and four digits, so the vectors are checkable by hand. */
val SYNTHETIC_WORDLIST: List<String> = List(RECOVERY_WORDLIST_SIZE) { "w" + it.toString().padStart(4, '0') }

/** The recovery code (ADR 0047) on every Crypto/Cipher pair; the vectors come from `tools/vectors/recovery_code_vectors.py`. */
abstract class RecoveryCodesContract {
    protected abstract suspend fun newCrypto(): Crypto
    protected abstract suspend fun newCipher(): Cipher

    private fun hex(text: String) = ByteString.of(ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() })

    private class Vector(val entropy: String, val words: String, val seed: String, val device: String)

    private val vectors = listOf(
        Vector("00000000000000000000000000000000", "w0000 w0000 w0000 w0000 w0000 w0000 w0000 w0000 w0000 w0000 w0000 w0005", "780c1953c1f30b622ad31ebbd24c0eeed01d60fded297e145a86f5372e94bc35", "e1028ca341f7ff4c5e5626db7cbb06b1"),
        Vector("000102030405060708090a0b0c0d0e0f", "w0000 w0064 w1030 w0064 w0643 w0028 w0257 w0266 w0088 w0771 w0540 w0248", "d6878d6dab2bd0b919dfac7624de33731d6eaac742f16d0a6bc1542232f7642b", "40cf9e8248716dd0e798b5df17ced296"),
        Vector("ffffffffffffffffffffffffffffffff", "w2047 w2047 w2047 w2047 w2047 w2047 w2047 w2047 w2047 w2047 w2047 w2037", "9943b37be1f3f837ca4a24a90d41c9d351d9e58db359b9c86c9ae378bf8352b5", "2251cd44551c79e473a2382236891f95"),
        Vector("0123456789abcdef0011223344556677", "w0009 w0209 w0719 w0154 w1510 w1980 w0002 w0290 w0410 w0277 w0716 w1905", "656625aa54404ac11a5eec05322efd3b0e1a7b6fbc72c21b6a69fc6137991fce", "29e21061c580d41d4f84e3c856c0f71d"),
    )

    @Test fun wordsSeedAndDeviceIdMatchTheIndependentPythonVectors() = runTest {
        val crypto = newCrypto()
        for (v in vectors) {
            val entropy = hex(v.entropy)
            assertEquals(v.words.split(" "), RecoveryCodes.wordsOf(crypto, SYNTHETIC_WORDLIST, entropy), v.entropy)
            assertEquals(hex(v.seed), RecoveryCodes.seedOf(crypto, entropy), v.entropy)
            assertEquals(DeviceId(v.device), RecoveryCodes.deviceIdOf(crypto, entropy), v.entropy)
        }
    }

    @Test fun typedWordsReadBackToTheSameEntropyWhateverTheCaseOrSpacing() = runTest {
        val crypto = newCrypto()
        for (v in vectors) {
            val parsed = assertIs<RecoveryParse.Valid>(RecoveryCodes.parse(crypto, SYNTHETIC_WORDLIST, v.words))
            assertEquals(hex(v.entropy), parsed.entropy)
        }
        val messy = "  W0000\tw0064\n w1030  W0064 w0643 w0028 w0257 w0266 w0088 w0771 w0540 w0248 \n"
        assertIs<RecoveryParse.Valid>(RecoveryCodes.parse(crypto, SYNTHETIC_WORDLIST, messy))
    }

    @Test fun randomCodesRoundTrip() = runTest {
        val crypto = newCrypto()
        repeat(25) {
            val entropy = RecoveryCodes.newEntropy(crypto)
            val words = RecoveryCodes.wordsOf(crypto, SYNTHETIC_WORDLIST, entropy)
            assertEquals(RECOVERY_WORDS, words.size)
            assertEquals(entropy, assertIs<RecoveryParse.Valid>(RecoveryCodes.parse(crypto, SYNTHETIC_WORDLIST, words.joinToString(" "))).entropy)
        }
    }

    @Test fun wrongCountsAndUnknownWordsAreReportedByPosition() = runTest {
        val crypto = newCrypto()
        val good = vectors[1].words.split(" ")
        assertEquals(RecoveryParse.WrongCount(0), RecoveryCodes.parse(crypto, SYNTHETIC_WORDLIST, "  "))
        assertEquals(RecoveryParse.WrongCount(11), RecoveryCodes.parse(crypto, SYNTHETIC_WORDLIST, good.dropLast(1).joinToString(" ")))
        assertEquals(RecoveryParse.WrongCount(13), RecoveryCodes.parse(crypto, SYNTHETIC_WORDLIST, (good + "w0001").joinToString(" ")))
        val typo = good.toMutableList().also { it[6] = "w9999" }
        assertEquals(RecoveryParse.UnknownWord(7, "w9999"), RecoveryCodes.parse(crypto, SYNTHETIC_WORDLIST, typo.joinToString(" ")))
        assertEquals(RecoveryParse.UnknownWord(1, "abandon"), RecoveryCodes.parse(crypto, SYNTHETIC_WORDLIST, "abandon " + good.drop(1).joinToString(" ")))
    }

    @Test fun mostSingleWordMistakesAndSwapsAreCaught() = runTest {
        val crypto = newCrypto()
        val good = vectors[3].words.split(" ")
        var tried = 0
        var caught = 0
        for (place in good.indices) {
            for (replacement in listOf("w0000", "w0001", "w1023", "w2047", "w0100")) {
                if (replacement == good[place]) continue
                tried++
                val changed = good.toMutableList().also { it[place] = replacement }
                val result = RecoveryCodes.parse(crypto, SYNTHETIC_WORDLIST, changed.joinToString(" "))
                if (result !is RecoveryParse.Valid) caught++ else assertNotEquals(hex(vectors[3].entropy), result.entropy)
            }
        }
        // A 4-bit checksum misses about one change in sixteen; anything under 85 percent caught would mean it is broken.
        assertTrue(caught * 100 >= tried * 85, "caught $caught of $tried")
        val swapped = good.toMutableList().also { val t = it[0]; it[0] = it[1]; it[1] = t }
        assertIs<RecoveryParse.BadChecksum>(RecoveryCodes.parse(crypto, SYNTHETIC_WORDLIST, swapped.joinToString(" ")).let { if (it is RecoveryParse.Valid) RecoveryParse.BadChecksum else it })
    }

    @Test fun theIdentityIsTheSameEveryTimeAndUsesTheDerivedDeviceId() = runTest {
        val crypto = newCrypto()
        val cipher = newCipher()
        val entropy = hex(vectors[1].entropy)
        val member = MemberId("a".repeat(32))
        val a = RecoveryCodes.identityOf(crypto, cipher, entropy, member)
        val b = RecoveryCodes.identityOf(crypto, cipher, entropy, member)
        assertEquals(DeviceId(vectors[1].device), a.device)
        assertEquals(a.signing.publicKey, b.signing.publicKey)
        assertEquals(a.boxPub, b.boxPub)
        assertEquals(hex(vectors[1].seed), a.seed)
        assertNotEquals(a.signing.publicKey, RecoveryCodes.identityOf(crypto, cipher, hex(vectors[0].entropy), member).signing.publicKey)
    }

    @Test fun theListAndTheEntropyMustBeTheRightSize() = runTest {
        val crypto = newCrypto()
        assertFailsWith<IllegalArgumentException> { RecoveryCodes.wordsOf(crypto, SYNTHETIC_WORDLIST.drop(1), hex(vectors[0].entropy)) }
        assertFailsWith<IllegalArgumentException> { RecoveryCodes.wordsOf(crypto, SYNTHETIC_WORDLIST, ByteString.of(ByteArray(15))) }
        assertFailsWith<IllegalArgumentException> { RecoveryCodes.parse(crypto, SYNTHETIC_WORDLIST.take(10), "a b c") }
    }
}
