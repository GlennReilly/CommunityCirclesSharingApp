package circles.recovery

private const val FIELD_BITS = 8
private const val BYTE_MASK = 0xFF
private const val HIGH_BIT_SHIFT = 7
private const val REDUCTION_LOW_BYTE = 0x1B // x^8 = x^4 + x^3 + x + 1, the AES polynomial 0x11B without its x^8 term
private const val INVERSE_EXPONENT = 254 // a^254 = a^-1 because a^255 = 1 for every nonzero a

/** Arithmetic in GF(2^8) with the AES polynomial (ADR 0020). Addition and subtraction are both xor. */
internal object Gf256 {
    /** Shift-and-xor multiplication with a fixed number of steps and no secret-dependent branch or table index. */
    fun multiply(a: Int, b: Int): Int {
        var result = 0
        var x = a and BYTE_MASK
        var y = b and BYTE_MASK
        repeat(FIELD_BITS) {
            result = result xor (x and -(y and 1))
            val carry = (x shr HIGH_BIT_SHIFT) and 1
            x = ((x shl 1) xor (REDUCTION_LOW_BYTE and -carry)) and BYTE_MASK
            y = y shr 1
        }
        return result
    }

    /** The inverse of a nonzero [a]; zero maps to zero (callers never divide by zero because share indexes differ). */
    fun inverse(a: Int): Int {
        var result = 1
        var base = a and BYTE_MASK
        var exponent = INVERSE_EXPONENT
        while (exponent > 0) {
            if (exponent and 1 == 1) result = multiply(result, base)
            base = multiply(base, base)
            exponent = exponent shr 1
        }
        return result
    }

    fun divide(a: Int, b: Int): Int = multiply(a, inverse(b))
}
