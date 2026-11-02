package circles.encoding

private const val MAJOR_UNSIGNED = 0
private const val MAJOR_NEGATIVE = 1
private const val MAJOR_BYTES = 2
private const val MAJOR_TEXT = 3
private const val MAJOR_ARRAY = 4
private const val MAJOR_MAP = 5
private const val MAJOR_SIMPLE = 7

private const val MAJOR_SHIFT = 5
private const val INLINE_LIMIT = 24L
private const val ONE_BYTE_LIMIT = 0x100L
private const val TWO_BYTE_LIMIT = 0x1_0000L
private const val FOUR_BYTE_LIMIT = 0x1_0000_0000L
private const val INFO_ONE_BYTE = 24
private const val INFO_TWO_BYTES = 25
private const val INFO_FOUR_BYTES = 26
private const val INFO_EIGHT_BYTES = 27
private const val SIMPLE_FALSE = 20
private const val SIMPLE_TRUE = 21
private const val SIMPLE_NULL = 22
private const val BYTE_MASK = 0xFF
private const val BITS_PER_BYTE = 8

/** Deterministic CBOR (RFC 8949 section 4.2): shortest integers and lengths, map keys sorted by encoded bytes. */
object CborEncoder {
    fun encode(value: CborValue): ByteArray {
        val out = ByteSink()
        write(value, out)
        return out.toByteArray()
    }

    private fun write(value: CborValue, out: ByteSink) {
        when (value) {
            is CborInt ->
                if (value.value >= 0) writeHead(MAJOR_UNSIGNED, value.value, out)
                else writeHead(MAJOR_NEGATIVE, value.value.inv(), out)
            is CborBytes -> {
                writeHead(MAJOR_BYTES, value.value.size.toLong(), out)
                out.write(value.value.toByteArray())
            }
            is CborText -> writeText(value.value, out)
            is CborArray -> {
                writeHead(MAJOR_ARRAY, value.items.size.toLong(), out)
                value.items.forEach { write(it, out) }
            }
            is CborMap -> writeMap(value, out)
            is CborBool -> out.write(byteArrayOf(simple(if (value.value) SIMPLE_TRUE else SIMPLE_FALSE)))
            CborNull -> out.write(byteArrayOf(simple(SIMPLE_NULL)))
        }
    }

    private fun writeText(text: String, out: ByteSink) {
        val utf8 = text.encodeToByteArray()
        writeHead(MAJOR_TEXT, utf8.size.toLong(), out)
        out.write(utf8)
    }

    private fun writeMap(map: CborMap, out: ByteSink) {
        writeHead(MAJOR_MAP, map.entries.size.toLong(), out)
        val encoded = map.entries.map { (key, value) ->
            val keySink = ByteSink()
            writeText(key, keySink)
            keySink.toByteArray() to value
        }
        encoded.sortedWith { a, b -> compareUnsigned(a.first, b.first) }.forEach { (keyBytes, value) ->
            out.write(keyBytes)
            write(value, out)
        }
    }

    private fun simple(info: Int): Byte = ((MAJOR_SIMPLE shl MAJOR_SHIFT) or info).toByte()

    /** [argument] is an unsigned quantity that always fits a non-negative Long here. */
    private fun writeHead(major: Int, argument: Long, out: ByteSink) {
        val base = major shl MAJOR_SHIFT
        when {
            argument < INLINE_LIMIT -> out.writeByte(base or argument.toInt())
            argument < ONE_BYTE_LIMIT -> {
                out.writeByte(base or INFO_ONE_BYTE)
                out.writeBigEndian(argument, 1)
            }
            argument < TWO_BYTE_LIMIT -> {
                out.writeByte(base or INFO_TWO_BYTES)
                out.writeBigEndian(argument, 2)
            }
            argument < FOUR_BYTE_LIMIT -> {
                out.writeByte(base or INFO_FOUR_BYTES)
                out.writeBigEndian(argument, 4)
            }
            else -> {
                out.writeByte(base or INFO_EIGHT_BYTES)
                out.writeBigEndian(argument, 8)
            }
        }
    }

    private fun ByteSink.writeBigEndian(value: Long, byteCount: Int) {
        for (i in byteCount - 1 downTo 0) writeByte(((value shr (i * BITS_PER_BYTE)).toInt()) and BYTE_MASK)
    }
}

/** Lexicographic comparison treating bytes as unsigned; a proper prefix sorts first. */
internal fun compareUnsigned(a: ByteArray, b: ByteArray): Int {
    val shared = minOf(a.size, b.size)
    for (i in 0 until shared) {
        val diff = (a[i].toInt() and BYTE_MASK) - (b[i].toInt() and BYTE_MASK)
        if (diff != 0) return diff
    }
    return a.size - b.size
}

/** Minimal growable byte buffer for common code. */
internal class ByteSink {
    private var buffer = ByteArray(INITIAL_CAPACITY)
    private var length = 0

    fun writeByte(value: Int) {
        ensure(1)
        buffer[length++] = value.toByte()
    }

    fun write(bytes: ByteArray) {
        ensure(bytes.size)
        bytes.copyInto(buffer, length)
        length += bytes.size
    }

    fun toByteArray(): ByteArray = buffer.copyOf(length)

    private fun ensure(extra: Int) {
        if (length + extra <= buffer.size) return
        buffer = buffer.copyOf(maxOf(buffer.size * 2, length + extra))
    }

    private companion object {
        const val INITIAL_CAPACITY = 64
    }
}
