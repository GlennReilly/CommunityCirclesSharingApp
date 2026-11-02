package circles.encoding

private const val MAJOR_SHIFT = 5
private const val INFO_MASK = 0x1F
private const val BYTE_MASK = 0xFF
private const val BITS_PER_BYTE = 8
private const val INLINE_LIMIT = 24
private const val INFO_ONE_BYTE = 24
private const val INFO_TWO_BYTES = 25
private const val INFO_FOUR_BYTES = 26
private const val INFO_EIGHT_BYTES = 27
private const val INFO_INDEFINITE = 31
private const val ONE_BYTE_MIN = 24L
private const val TWO_BYTE_MIN = 0x100L
private const val FOUR_BYTE_MIN = 0x1_0000L
private const val EIGHT_BYTE_MIN = 0x1_0000_0000L
private const val SIMPLE_FALSE = 20
private const val SIMPLE_TRUE = 21
private const val SIMPLE_NULL = 22
private const val SIGN_BIT_BYTE = 0x80

/** Deepest nesting of arrays and maps the decoder accepts. */
const val CBOR_MAX_DEPTH = 16

/**
 * Strict decoder for the subset in [CborValue]. It refuses anything [CborEncoder] would not produce:
 * non-shortest integers or lengths, unsorted or duplicate keys, indefinite lengths, tags, floats, invalid UTF-8,
 * trailing bytes and excessive nesting. So an event has exactly one valid byte string (ADR 0012).
 */
object CborDecoder {
    fun decode(bytes: ByteArray): Parsed<CborValue> =
        try {
            val reader = Reader(bytes)
            val value = reader.readValue(0)
            if (reader.remaining() != 0) throw CborFormatException("trailing bytes after the value")
            Parsed.Ok(value)
        } catch (e: CborFormatException) {
            Parsed.Invalid(e.message ?: "malformed CBOR")
        }
}

private class CborFormatException(message: String) : Exception(message)

private class Reader(private val bytes: ByteArray) {
    private var position = 0

    fun remaining(): Int = bytes.size - position

    fun readValue(depth: Int): CborValue {
        val initial = readByte()
        val major = initial shr MAJOR_SHIFT
        val info = initial and INFO_MASK
        return when (major) {
            MAJOR_UNSIGNED -> CborInt(readArgument(info))
            MAJOR_NEGATIVE -> CborInt(readArgument(info).inv())
            MAJOR_BYTES -> CborBytes(ByteString.of(readSlice(readLength(info))))
            MAJOR_TEXT -> CborText(readText(readLength(info)))
            MAJOR_ARRAY -> readArray(readLength(info), depth)
            MAJOR_MAP -> readMap(readLength(info), depth)
            MAJOR_TAG -> throw CborFormatException("tags are not supported")
            else -> readSimple(info)
        }
    }

    private fun readArray(count: Int, depth: Int): CborValue {
        checkDepth(depth)
        if (count > remaining()) throw CborFormatException("array length exceeds the input")
        return CborArray(List(count) { readValue(depth + 1) })
    }

    private fun readMap(count: Int, depth: Int): CborValue {
        checkDepth(depth)
        if (count > remaining()) throw CborFormatException("map length exceeds the input")
        val entries = LinkedHashMap<String, CborValue>()
        var previousKey: ByteArray? = null
        repeat(count) {
            val keyStart = position
            val keyInitial = readByte()
            if (keyInitial shr MAJOR_SHIFT != MAJOR_TEXT) throw CborFormatException("map keys must be text")
            val key = readText(readLength(keyInitial and INFO_MASK))
            val keyBytes = bytes.copyOfRange(keyStart, position)
            val before = previousKey
            if (before != null && compareUnsigned(before, keyBytes) >= 0) {
                throw CborFormatException("map keys are not strictly increasing")
            }
            previousKey = keyBytes
            entries[key] = readValue(depth + 1)
        }
        return CborMap(entries)
    }

    private fun readSimple(info: Int): CborValue =
        when (info) {
            SIMPLE_FALSE -> CborBool(false)
            SIMPLE_TRUE -> CborBool(true)
            SIMPLE_NULL -> CborNull
            else -> throw CborFormatException("unsupported simple value or float")
        }

    private fun checkDepth(depth: Int) {
        if (depth >= CBOR_MAX_DEPTH) throw CborFormatException("nesting is too deep")
    }

    private fun readText(length: Int): String {
        val raw = readSlice(length)
        return try {
            raw.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            throw CborFormatException("invalid UTF-8 in text")
        }
    }

    private fun readLength(info: Int): Int {
        val length = readArgument(info)
        if (length > remaining()) throw CborFormatException("length exceeds the input")
        return length.toInt()
    }

    /** Reads the argument in shortest form; the result is always non-negative. */
    private fun readArgument(info: Int): Long =
        when {
            info < INLINE_LIMIT -> info.toLong()
            info == INFO_ONE_BYTE -> readBigEndian(1).also { requireShortest(it >= ONE_BYTE_MIN) }
            info == INFO_TWO_BYTES -> readBigEndian(2).also { requireShortest(it >= TWO_BYTE_MIN) }
            info == INFO_FOUR_BYTES -> readBigEndian(4).also { requireShortest(it >= FOUR_BYTE_MIN) }
            info == INFO_EIGHT_BYTES -> readEight()
            info == INFO_INDEFINITE -> throw CborFormatException("indefinite lengths are not supported")
            else -> throw CborFormatException("reserved additional information")
        }

    private fun requireShortest(shortest: Boolean) {
        if (!shortest) throw CborFormatException("integer or length is not in shortest form")
    }

    private fun readEight(): Long {
        if (remaining() < 8) throw CborFormatException("truncated input")
        if ((bytes[position].toInt() and BYTE_MASK) >= SIGN_BIT_BYTE) throw CborFormatException("integer is too large")
        return readBigEndian(8).also { requireShortest(it >= EIGHT_BYTE_MIN) }
    }

    private fun readBigEndian(byteCount: Int): Long {
        if (remaining() < byteCount) throw CborFormatException("truncated input")
        var value = 0L
        repeat(byteCount) { value = (value shl BITS_PER_BYTE) or (bytes[position++].toLong() and BYTE_MASK.toLong()) }
        return value
    }

    private fun readSlice(length: Int): ByteArray {
        if (length > remaining()) throw CborFormatException("truncated input")
        val slice = bytes.copyOfRange(position, position + length)
        position += length
        return slice
    }

    private fun readByte(): Int {
        if (remaining() < 1) throw CborFormatException("truncated input")
        return bytes[position++].toInt() and BYTE_MASK
    }

    private companion object {
        const val MAJOR_UNSIGNED = 0
        const val MAJOR_NEGATIVE = 1
        const val MAJOR_BYTES = 2
        const val MAJOR_TEXT = 3
        const val MAJOR_ARRAY = 4
        const val MAJOR_MAP = 5
        const val MAJOR_TAG = 6
    }
}
