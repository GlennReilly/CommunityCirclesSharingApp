package circles.server.backup

import kotlinx.serialization.Serializable

/** The format name in every backup manifest (ADR 0031). A change to the format needs `circles-backup/2`. */
const val BACKUP_FORMAT = "circles-backup/1"
const val BACKUP_DB_ENTRY = "lendger.db"
const val BACKUP_BLOB_PREFIX = "blobs/"
const val BLOBS_DIR_NAME = "blobs"

@Serializable
data class BackupBlobRef(val circle: String, val hash: String, val size: Long)

/** `manifest.json` of a backup: hashes of everything, no timestamps, nothing secret. */
@Serializable
data class BackupManifest(
    val format: String,
    val schemaVersion: Int,
    val includesBlobs: Boolean,
    val dbHash: String,
    val dbSize: Long,
    val blobs: List<BackupBlobRef>,
)
