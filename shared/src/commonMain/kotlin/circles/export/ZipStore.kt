package circles.export

private const val CRC_TABLE_SIZE = 256
private const val CRC_POLYNOMIAL = 0xEDB88320L
private const val CRC_INIT = 0xFFFFFFFFL
private const val BYTE_MASK = 0xFF
private const val BITS_PER_BYTE = 8

/** CRC-32 as zip uses it (the reflected 0xEDB88320 polynomial), in common code so every target writes the same bytes. */
object Crc32 {
    private val table = LongArray(CRC_TABLE_SIZE) { index ->
        var value = index.toLong()
        repeat(BITS_PER_BYTE) { value = if (value and 1L != 0L) (value ushr 1) xor CRC_POLYNOMIAL else value ushr 1 }
        value
    }

    fun of(bytes: ByteArray): Long {
        var crc = CRC_INIT
        for (b in bytes) crc = table[((crc xor b.toLong()) and BYTE_MASK.toLong()).toInt()] xor (crc ushr BITS_PER_BYTE)
        return crc xor CRC_INIT
    }
}

private const val LOCAL_HEADER_SIGNATURE = 0x04034b50L
private const val CENTRAL_HEADER_SIGNATURE = 0x02014b50L
private const val END_SIGNATURE = 0x06054b50L
private const val ZIP_VERSION = 20
private const val UTF8_NAMES_FLAG = 0x0800
private const val METHOD_STORED = 0
private const val DOS_TIME_ZERO = 0
private const val DOS_DATE_1980_01_01 = 0x0021
private const val MAX_ZIP32_ENTRIES = 0xFFFF
private const val MAX_ZIP32_BYTES = 0xFFFFFFFFL

/**
 * A zip writer that stores entries without compressing them (ADR 0030 allows any zip library). Hand-written because the
 * browser has no zip library and an export is mostly ciphertext, which does not compress. Null if the result would need
 * the 64-bit zip extensions (more than 65,535 entries or 4 GiB), which the circle export never does.
 */
object ZipStore {
    fun write(entries: Map<String, ByteArray>): ByteArray? {
        if (entries.size > MAX_ZIP32_ENTRIES) return null
        val out = Bytes()
        val central = Bytes()
        for ((name, data) in entries) {
            val nameBytes = name.encodeToByteArray()
            val crc = Crc32.of(data)
            val offset = out.size
            if (offset > MAX_ZIP32_BYTES || data.size > MAX_ZIP32_BYTES) return null
            out.u32(LOCAL_HEADER_SIGNATURE); out.u16(ZIP_VERSION); out.u16(UTF8_NAMES_FLAG); out.u16(METHOD_STORED)
            out.u16(DOS_TIME_ZERO); out.u16(DOS_DATE_1980_01_01); out.u32(crc); out.u32(data.size.toLong()); out.u32(data.size.toLong())
            out.u16(nameBytes.size); out.u16(0); out.raw(nameBytes); out.raw(data)
            central.u32(CENTRAL_HEADER_SIGNATURE); central.u16(ZIP_VERSION); central.u16(ZIP_VERSION); central.u16(UTF8_NAMES_FLAG); central.u16(METHOD_STORED)
            central.u16(DOS_TIME_ZERO); central.u16(DOS_DATE_1980_01_01); central.u32(crc); central.u32(data.size.toLong()); central.u32(data.size.toLong())
            central.u16(nameBytes.size); central.u16(0); central.u16(0); central.u16(0); central.u16(0); central.u32(0); central.u32(offset)
            central.raw(nameBytes)
        }
        val centralOffset = out.size
        out.raw(central.toByteArray())
        out.u32(END_SIGNATURE); out.u16(0); out.u16(0); out.u16(entries.size); out.u16(entries.size)
        out.u32(central.size); out.u32(centralOffset); out.u16(0)
        return if (out.size > MAX_ZIP32_BYTES) null else out.toByteArray()
    }

    /** A growing byte buffer with little-endian writers. */
    private class Bytes {
        private var buffer = ByteArray(INITIAL_CAPACITY)
        var size = 0L
            private set

        fun toByteArray(): ByteArray = buffer.copyOf(size.toInt())

        private fun ensure(extra: Int) {
            if (size + extra > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, (size + extra).toInt()))
        }

        fun raw(bytes: ByteArray) {
            ensure(bytes.size)
            bytes.copyInto(buffer, size.toInt())
            size += bytes.size
        }

        fun u16(value: Int) = raw(ByteArray(2) { (value ushr (BITS_PER_BYTE * it)).toByte() })

        fun u32(value: Long) = raw(ByteArray(4) { (value ushr (BITS_PER_BYTE * it)).toByte() })
    }

    private const val INITIAL_CAPACITY = 4096
}
