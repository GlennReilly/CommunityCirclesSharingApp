package circles.encoding

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val ROUNDS = 20_000
private const val MAX_LENGTH = 40
private const val SEED = 20261102
private const val BYTE_RANGE = 256
private const val SHIFT_LEFT_A = 13
private const val SHIFT_RIGHT = 17
private const val SHIFT_LEFT_B = 5

/** Small deterministic xorshift generator (32-bit, so it is fast in JS too) so a failure reproduces on both targets. */
private class Lcg(seed: Int) {
    private var state = if (seed == 0) 1 else seed

    fun next(bound: Int): Int {
        state = state xor (state shl SHIFT_LEFT_A)
        state = state xor (state ushr SHIFT_RIGHT)
        state = state xor (state shl SHIFT_LEFT_B)
        return (state ushr 1) % bound
    }
}

class CborFuzzTest {
    @Test
    fun randomBytesNeverCrashTheDecoderAndAcceptedInputIsAlreadyCanonical() {
        val random = Lcg(SEED)
        var accepted = 0
        repeat(ROUNDS) {
            val bytes = ByteArray(random.next(MAX_LENGTH)) { random.next(BYTE_RANGE).toByte() }
            when (val result = CborDecoder.decode(bytes)) {
                is Parsed.Ok -> {
                    accepted++
                    assertContentEquals(bytes, CborEncoder.encode(result.value), "accepted input must be the canonical encoding")
                }
                is Parsed.Invalid -> assertTrue(result.reason.isNotEmpty())
            }
        }
        assertTrue(accepted > 0, "the generator should hit some valid inputs")
    }

    @Test
    fun mutatingAValidEncodingEitherFailsOrStaysCanonical() {
        val random = Lcg(SEED + 1)
        val original = CborEncoder.encode(
            CborMap.of(
                "a" to CborArray(listOf(CborInt(1), CborText("x"), CborNull)),
                "bb" to CborBytes(ByteString.of(byteArrayOf(1, 2, 3))),
                "c" to CborMap.of("d" to CborBool(true), "e" to CborInt(-300)),
            ),
        )
        repeat(ROUNDS) {
            val mutated = original.copyOf()
            mutated[random.next(mutated.size)] = random.next(BYTE_RANGE).toByte()
            val result = CborDecoder.decode(mutated)
            if (result is Parsed.Ok) assertContentEquals(mutated, CborEncoder.encode(result.value))
        }
    }

    @Test
    fun randomValuesRoundTrip() {
        val random = Lcg(SEED + 2)
        fun value(depth: Int): CborValue =
            when (random.next(if (depth >= 3) 5 else 7)) {
                0 -> CborInt((random.next(BYTE_RANGE) - BYTE_RANGE / 2).toLong() * (1L shl random.next(40)))
                1 -> CborBytes(ByteString.of(ByteArray(random.next(30)) { random.next(BYTE_RANGE).toByte() }))
                2 -> CborText("t".repeat(random.next(30)) + "é水")
                3 -> CborBool(random.next(2) == 0)
                4 -> CborNull
                5 -> CborArray(List(random.next(4)) { value(depth + 1) })
                else -> CborMap((0 until random.next(4)).associate { "k${random.next(1000)}" to value(depth + 1) })
            }
        repeat(2_000) {
            val original = value(0)
            assertEquals(Parsed.Ok(original), CborDecoder.decode(CborEncoder.encode(original)))
        }
    }
}
