package circles.encoding

import kotlin.io.encoding.Base64

private const val HEX_DIGITS = "0123456789abcdef"
private const val HEX_RADIX = 16
private const val NIBBLE_BITS = 4
private const val BYTE_MASK = 0xFF
private const val NIBBLE_MASK = 0x0F
private const val HEX_CHARS_PER_BYTE = 2
private val base64Url = Base64.UrlSafe.withPadding(Base64.PaddingOption.ABSENT)

/** Immutable byte sequence with content equality, so envelopes and keys can be data classes. */
class ByteString private constructor(private val bytes: ByteArray) {
    val size: Int get() = bytes.size

    fun isEmpty(): Boolean = bytes.isEmpty()

    operator fun get(index: Int): Byte = bytes[index]

    /** A copy; callers cannot change this value through it. */
    fun toByteArray(): ByteArray = bytes.copyOf()

    /** Lowercase hex. */
    fun toHex(): String {
        val out = StringBuilder(bytes.size * HEX_CHARS_PER_BYTE)
        for (b in bytes) {
            val v = b.toInt() and BYTE_MASK
            out.append(HEX_DIGITS[v shr NIBBLE_BITS]).append(HEX_DIGITS[v and NIBBLE_MASK])
        }
        return out.toString()
    }

    /** Base64url without padding. */
    fun toBase64Url(): String = base64Url.encode(bytes)

    override fun equals(other: Any?): Boolean = other is ByteString && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = "ByteString(${bytes.size} bytes)"

    companion object {
        val EMPTY: ByteString = ByteString(ByteArray(0))

        fun of(bytes: ByteArray): ByteString = if (bytes.isEmpty()) EMPTY else ByteString(bytes.copyOf())

        /** Strict lowercase hex; null when the text is not valid. */
        fun fromHex(text: String): ByteString? {
            if (text.length % HEX_CHARS_PER_BYTE != 0) return null
            val out = ByteArray(text.length / HEX_CHARS_PER_BYTE)
            for (i in out.indices) {
                val hi = HEX_DIGITS.indexOf(text[i * HEX_CHARS_PER_BYTE])
                val lo = HEX_DIGITS.indexOf(text[i * HEX_CHARS_PER_BYTE + 1])
                if (hi < 0 || lo < 0) return null
                out[i] = ((hi * HEX_RADIX) + lo).toByte()
            }
            return of(out)
        }

        /** Base64url without padding; null when the text is not valid. */
        fun fromBase64Url(text: String): ByteString? =
            try {
                of(base64Url.decode(text))
            } catch (_: IllegalArgumentException) {
                null
            }

        fun ofUtf8(text: String): ByteString = of(text.encodeToByteArray())
    }
}
