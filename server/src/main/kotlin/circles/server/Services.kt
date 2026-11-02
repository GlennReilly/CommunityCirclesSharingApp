package circles.server

import circles.crypto.Crypto
import circles.server.data.FileBlobFiles
import circles.server.data.SqliteStore
import circles.server.domain.BYTES_PER_MIB
import circles.server.domain.BlobService
import circles.server.domain.DEFAULT_BLOB_QUOTA_MIB
import circles.server.domain.EventHub
import circles.server.web.STREAM_KEEP_ALIVE_MILLIS
import java.nio.file.Path
import circles.server.domain.RateLimiter
import circles.server.domain.Unlimited
import circles.server.logging.Log
import circles.server.logging.NoLog
import circles.server.domain.AuthService
import circles.server.domain.EventRules
import circles.server.domain.SyncService
import circles.server.web.Services
import java.sql.Connection
import java.time.Clock

/** Wires the layers together over one open database connection. */
fun buildServices(
    connection: Connection,
    crypto: Crypto,
    clock: Clock,
    allowCreate: Boolean,
    log: Log = NoLog,
    rateLimit: Boolean = true,
    trustProxy: Boolean = false,
    blobsDir: Path,
    blobQuotaBytes: Long = DEFAULT_BLOB_QUOTA_MIB * BYTES_PER_MIB,
    streamKeepAliveMillis: Long = STREAM_KEEP_ALIVE_MILLIS,
): Services {
    val store = SqliteStore(connection)
    val hub = EventHub()
    return Services(
        sync = SyncService(store, crypto, clock, EventRules(crypto), allowCreate, log, hub),
        auth = AuthService(store, crypto, clock),
        store = store,
        log = log,
        limits = if (rateLimit) RateLimiter(clock) else Unlimited,
        trustProxy = trustProxy,
        blobs = BlobService(store, FileBlobFiles(blobsDir), crypto, clock, blobQuotaBytes),
        hub = hub,
        streamKeepAliveMillis = streamKeepAliveMillis,
    )
}
