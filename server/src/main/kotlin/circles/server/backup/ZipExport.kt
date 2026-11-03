package circles.server.backup

import circles.export.BuiltExport
import circles.export.ExportSource
import circles.export.BLOB_ENTRY_PREFIX
import circles.export.EVENTS_ENTRY
import circles.export.MANIFEST_ENTRY
import circles.api.ApiJson
import circles.export.ExportManifest
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

private const val READ_CHUNK = 64 * 1024

/** Reads an export zip without ever extracting it to disk (ADR 0030). Entry sizes are checked as they are read. */
class ZipExportSource(path: Path) : ExportSource, AutoCloseable {
    private val zip = ZipFile(path.toFile())
    private val byName: Map<String, ZipEntry> = zip.entries().asSequence().groupBy { it.name }.mapValues { it.value.first() }

    override val entryNames: List<String> = zip.entries().asSequence().map { it.name }.toList()

    override fun sizeOf(name: String): Long? = byName[name]?.size?.takeIf { it >= 0 }

    /** Null if the entry is absent, declares more than [maxBytes], or turns out to hold more than it declared. */
    override fun read(name: String, maxBytes: Long): ByteArray? {
        val entry = byName[name] ?: return null
        if (entry.size < 0 || entry.size > maxBytes) return null
        val out = ByteArrayOutputStream(entry.size.toInt().coerceAtLeast(0))
        zip.getInputStream(entry).use { input ->
            val buffer = ByteArray(READ_CHUNK)
            var total = 0L
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                if (total > entry.size || total > maxBytes) return null
                out.write(buffer, 0, n)
            }
        }
        return out.toByteArray()
    }

    override fun close() = zip.close()
}

/** Writes an export zip: the manifest, the events, then each blob. Written to a temporary file and renamed into place. */
object ZipExportWriter {
    fun write(target: Path, built: BuiltExport, blobBytes: (String) -> ByteArray) {
        val directory = target.toAbsolutePath().parent
        val temp = Files.createTempFile(directory, ".export-", ".tmp")
        try {
            ZipOutputStream(Files.newOutputStream(temp)).use { zip ->
                zip.putEntry(MANIFEST_ENTRY, ApiJson.encodeToString(ExportManifest.serializer(), built.manifest).encodeToByteArray())
                zip.putEntry(EVENTS_ENTRY, built.eventsBytes)
                built.manifest.blobs.forEach { zip.putEntry(BLOB_ENTRY_PREFIX + it.hash, blobBytes(it.hash)) }
            }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun ZipOutputStream.putEntry(name: String, bytes: ByteArray) {
        putNextEntry(ZipEntry(name))
        write(bytes)
        closeEntry()
    }
}
