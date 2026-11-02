package circles.server.data

import circles.policy.CircleId
import circles.server.domain.BlobFiles
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption

private const val SHARD_CHARS = 2

/** Blobs as files under `<root>/<circleId>/<first two hash characters>/<hash>`, written by temp file and rename. */
class FileBlobFiles(private val root: Path) : BlobFiles {
    override fun write(circle: CircleId, hash: String, bytes: ByteArray) {
        val target = path(circle, hash)
        Files.createDirectories(target.parent)
        val temp = Files.createTempFile(target.parent, ".upload-", ".tmp")
        try {
            Files.write(temp, bytes)
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    override fun read(circle: CircleId, hash: String): ByteArray? =
        try {
            Files.readAllBytes(path(circle, hash))
        } catch (_: NoSuchFileException) {
            null
        }

    /** Both ids are validated hex by the caller, so neither can contain a path separator or `..`. */
    private fun path(circle: CircleId, hash: String): Path = root.resolve(circle.v).resolve(hash.take(SHARD_CHARS)).resolve(hash)
}
