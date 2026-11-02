package circles.server.domain

import circles.api.MAX_BLOB_BYTES
import circles.crypto.Crypto
import circles.event.isLowerHex
import circles.policy.CircleId
import java.time.Clock

const val BLOB_HASH_HEX_LENGTH = 64
const val DEFAULT_BLOB_QUOTA_MIB = 256L
const val BYTES_PER_MIB = 1024L * 1024

/** Where blob bytes live. Implementations must write atomically: a blob is visible whole or not at all. */
interface BlobFiles {
    fun write(circle: CircleId, hash: String, bytes: ByteArray)

    fun read(circle: CircleId, hash: String): ByteArray?
}

sealed interface BlobPutResult {
    data object Created : BlobPutResult

    data object AlreadyThere : BlobPutResult

    data object BadHash : BlobPutResult

    data object TooLarge : BlobPutResult

    data object HashMismatch : BlobPutResult

    data object QuotaExceeded : BlobPutResult
}

/** Stores and serves encrypted blobs by the BLAKE2b-256 hash of their bytes (ADR 0026). It never sees plaintext. */
class BlobService(
    private val store: Store,
    private val files: BlobFiles,
    private val crypto: Crypto,
    private val clock: Clock,
    private val quotaBytes: Long,
) {
    fun put(circle: CircleId, hash: String, body: ByteArray): BlobPutResult {
        if (!isLowerHex(hash, BLOB_HASH_HEX_LENGTH)) return BlobPutResult.BadHash
        if (body.size > MAX_BLOB_BYTES) return BlobPutResult.TooLarge
        if (crypto.hash256(body).toHex() != hash) return BlobPutResult.HashMismatch
        return store.transaction { tx ->
            when {
                tx.blobSize(circle, hash) != null -> BlobPutResult.AlreadyThere
                tx.blobBytesUsed(circle) + body.size > quotaBytes -> BlobPutResult.QuotaExceeded
                else -> {
                    // Bytes first, then the row: a crash in between leaves an unreferenced file, never a row without bytes.
                    files.write(circle, hash, body)
                    tx.insertBlob(circle, hash, body.size.toLong(), clock.millis())
                    BlobPutResult.Created
                }
            }
        }
    }

    /** The blob, or null if the hash is malformed or nothing is stored under it for this circle. */
    fun get(circle: CircleId, hash: String): ByteArray? {
        if (!isLowerHex(hash, BLOB_HASH_HEX_LENGTH)) return null
        if (store.transaction { it.blobSize(circle, hash) } == null) return null
        return files.read(circle, hash)
    }
}
