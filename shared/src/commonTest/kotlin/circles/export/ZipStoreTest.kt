package circles.export

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The store-only zip writer behind the browser's circle export (ADR 0030), on both targets. */
class ZipStoreTest {
    @Test
    fun crc32MatchesTheStandardCheckValues() {
        assertEquals(0xCBF43926L, Crc32.of("123456789".encodeToByteArray()))
        assertEquals(0L, Crc32.of(ByteArray(0)))
        assertEquals(0x352441C2L, Crc32.of("abc".encodeToByteArray()))
    }

    /** A minimal reader of what the writer produces: the end record, the central directory, then each local entry. */
    private fun read(zip: ByteArray): Map<String, ByteArray> {
        fun u16(at: Int) = (zip[at].toInt() and 0xFF) or ((zip[at + 1].toInt() and 0xFF) shl 8)
        fun u32(at: Int) = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        val end = zip.size - 22
        assertEquals(0x06054b50L, u32(end))
        val count = u16(end + 10)
        var at = u32(end + 16).toInt()
        val out = linkedMapOf<String, ByteArray>()
        repeat(count) {
            assertEquals(0x02014b50L, u32(at))
            val crc = u32(at + 16)
            val size = u32(at + 24).toInt()
            val nameLength = u16(at + 28)
            val offset = u32(at + 42).toInt()
            val name = zip.copyOfRange(at + 46, at + 46 + nameLength).decodeToString()
            assertEquals(0x04034b50L, u32(offset))
            assertEquals(nameLength, u16(offset + 26))
            val dataAt = offset + 30 + nameLength
            val data = zip.copyOfRange(dataAt, dataAt + size)
            assertEquals(crc, Crc32.of(data), "crc of $name")
            out[name] = data
            at += 46 + nameLength
        }
        return out
    }

    @Test
    fun entriesComeBackByteForByteInOrderIncludingEmptyAndNonAsciiNames() {
        val entries = linkedMapOf(
            "manifest.json" to """{"format":"circles-export/1"}""".encodeToByteArray(),
            "events.ndjson" to ByteArray(10_000) { (it * 7).toByte() },
            "blobs/empty" to ByteArray(0),
            "blobs/café" to byteArrayOf(0, -1, 127, -128),
        )
        val back = read(assertNotNull(ZipStore.write(entries)))
        assertEquals(entries.keys.toList(), back.keys.toList())
        for ((name, data) in entries) assertContentEquals(data, back.getValue(name), name)
    }

    @Test
    fun anEmptyZipIsJustTheEndRecord() {
        assertEquals(22, assertNotNull(ZipStore.write(emptyMap())).size)
    }
}
