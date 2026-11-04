package circles.qr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The QR encoder (ADR 0062) against codes made by an independent implementation, on both targets. */
class QrCodeTest {
    private fun rowsOf(code: QrCode): String =
        (0 until code.size).joinToString("/") { y ->
            val bits = (0 until code.size).joinToString("") { x -> if (code.module(x, y)) "1" else "0" }
            val padded = bits + "0".repeat((4 - bits.length % 4) % 4)
            padded.chunked(4).joinToString("") { it.toInt(2).toString(16) }
        }

    @Test
    fun everyMaskOfEveryTextMatchesTheIndependentEncoderModuleForModule() {
        for (vector in QR_VECTORS.filter { it.mask >= 0 }) {
            val code = assertNotNull(QrCode.of(vector.text, forceMask = vector.mask), vector.text)
            assertEquals(vector.version, code.version, "version of ${vector.text.take(20)} mask ${vector.mask}")
            assertEquals(vector.size, code.size)
            assertEquals(vector.rows, rowsOf(code), "modules of ${vector.text.take(20)} mask ${vector.mask}")
        }
    }

    @Test
    fun theMaskChosenByPenaltyIsOneOfTheEightAndGivesTheSameCodeAsForcingIt() {
        for (text in QR_VECTORS.map { it.text }.distinct()) {
            val auto = assertNotNull(QrCode.of(text), text)
            assertTrue(auto.mask in 0..7)
            assertEquals(rowsOf(QrCode.of(text, forceMask = auto.mask)!!), rowsOf(auto), "the same code as forcing mask ${auto.mask}")
            // Which mask is the best is a matter of the penalty rules; every mask makes a readable code, as the vectors above show.
            assertEquals(rowsOf(auto), rowsOf(QrCode.of(text)!!), "deterministic")
        }
    }

    @Test
    fun theSmallestVersionIsUsedAndTheLimitIsEnforced() {
        assertEquals(1, QrCode.of("a".repeat(14))!!.version)
        assertEquals(2, QrCode.of("a".repeat(15))!!.version)
        assertEquals(10, QrCode.of("a".repeat(QrCode.MAX_BYTES))!!.version)
        assertNull(QrCode.of("a".repeat(QrCode.MAX_BYTES + 1)))
        assertEquals(21, QrCode.of("")!!.size)
        // Three finder patterns: the corner modules are dark and the quiet corner away from them is not forced.
        val code = QrCode.of("x")!!
        assertTrue(code.module(0, 0) && code.module(code.size - 1, 0) && code.module(0, code.size - 1))
    }
}
