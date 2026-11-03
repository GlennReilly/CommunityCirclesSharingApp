package circles.keys

import circles.crypto.JvmCrypto
import circles.encoding.ByteString
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The real word list that the page loads (`wordlist.txt`) and the vectors made with it by `tools/vectors/recovery_code_vectors.py`. */
class RecoveryWordlistTest {
    private val file = File("../web/src/jsMain/resources/wordlist.txt")
    private val words: List<String> = file.readText().split("\n").filter { it.isNotEmpty() }

    @Test fun theListHasThePropertiesTheCodeRelies() {
        assertEquals(RECOVERY_WORDLIST_SIZE, words.size)
        assertEquals(RECOVERY_WORDLIST_SIZE, words.toSet().size, "no repeats")
        assertTrue(words.all { word -> word.length in 3..8 && word.all { it in 'a'..'z' } }, "short lowercase letters only")
        assertEquals(RECOVERY_WORDLIST_SIZE, words.map { it.take(4) }.toSet().size, "every word is told apart by its first four letters")
        assertEquals(words.sorted(), words, "alphabetical, as published")
        assertEquals("abandon", words.first())
        assertEquals("zoo", words.last())
    }

    @Test fun realWordsMatchThePythonVectors() {
        val crypto = JvmCrypto()
        fun hex(text: String) = ByteString.of(ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() })
        val expected = mapOf(
            "00000000000000000000000000000000" to "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon absent",
            "000102030405060708090a0b0c0d0e0f" to "abandon amount liar amount expire adjust cage candy arch gather drum business",
            "ffffffffffffffffffffffffffffffff" to "zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong",
            "0123456789abcdef0011223344556677" to "abuse boss fly battle rubber wasp able cattle crew cargo flower until",
        )
        for ((entropy, text) in expected) {
            assertEquals(text.split(" "), RecoveryCodes.wordsOf(crypto, words, hex(entropy)), entropy)
            assertEquals(hex(entropy), (RecoveryCodes.parse(crypto, words, text) as RecoveryParse.Valid).entropy)
        }
    }

    @Test fun theLicenceNoteTravelsWithTheList() {
        assertTrue(File("../web/src/jsMain/resources/wordlist.LICENSE.txt").readText().contains("MIT"))
    }
}
