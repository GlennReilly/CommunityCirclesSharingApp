package circles.export

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** The JDK's own zip reader accepts what [ZipStore] writes, which is what the server's importer uses. */
class ZipStoreJavaTest {
    @Test
    fun javaUtilZipReadsTheEntriesBack() {
        val entries = linkedMapOf("a.txt" to "hello".encodeToByteArray(), "dir/b.bin" to ByteArray(70_000) { (it % 251).toByte() })
        val zip = assertNotNull(ZipStore.write(entries))
        val back = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(zip)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                back[entry.name] = input.readBytes()
            }
        }
        assertEquals(entries.keys.toList(), back.keys.toList())
        for ((name, data) in entries) assertContentEquals(data, back.getValue(name), name)
        val file = java.nio.file.Files.createTempFile("zipstore", ".zip")
        try {
            java.nio.file.Files.write(file, zip)
            java.util.zip.ZipFile(file.toFile()).use { z ->
                assertEquals(entries.keys.toList(), z.entries().asSequence().map { it.name }.toList())
                for ((name, data) in entries) assertEquals(data.size.toLong(), z.getEntry(name).size)
            }
        } finally {
            java.nio.file.Files.deleteIfExists(file)
        }
    }
}
