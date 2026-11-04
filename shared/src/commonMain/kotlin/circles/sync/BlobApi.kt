package circles.sync

import circles.api.API_PREFIX
import circles.policy.CircleId

const val HTTP_INSUFFICIENT_STORAGE = 507

/** What came back for a blob download; the body is raw bytes. */
class BlobReply(val status: Int, val bytes: ByteArray)

/** The network for blobs: raw bytes in, raw bytes out. The browser uses `fetch`, tests an in-process server. Null means no connection. */
interface BlobTransport {
    suspend fun put(path: String, body: ByteArray, token: String): Int?

    suspend fun get(path: String, token: String): BlobReply?
}

sealed interface BlobPut {
    data object Stored : BlobPut

    data object Offline : BlobPut

    /** The circle's storage is full. */
    data object Full : BlobPut

    data class Refused(val status: Int) : BlobPut
}

/** Uploads and downloads sealed blobs (ADR 0026) with the device's session token, logging in again once on a 401. */
class HttpBlobApi(private val transport: BlobTransport, private val tokens: TokenSource) {
    private fun path(circle: CircleId, hash: String) = "$API_PREFIX/circles/${circle.v}/blobs/$hash"

    suspend fun put(circle: CircleId, hash: String, bytes: ByteArray): BlobPut {
        repeat(2) { attempt ->
            val token = (tokens.token() as? ApiOutcome.Ok)?.value ?: return BlobPut.Offline
            val status = transport.put(path(circle, hash), bytes, token) ?: return BlobPut.Offline
            when {
                status == HTTP_OK || status == HTTP_CREATED -> return BlobPut.Stored
                status == HTTP_UNAUTHORIZED && attempt == 0 -> tokens.rejectToken()
                status == HTTP_INSUFFICIENT_STORAGE -> return BlobPut.Full
                else -> return BlobPut.Refused(status)
            }
        }
        return BlobPut.Refused(HTTP_UNAUTHORIZED)
    }

    /** The sealed bytes, or null when offline, absent or refused. The caller checks the hash and opens them. */
    suspend fun get(circle: CircleId, hash: String): ByteArray? {
        repeat(2) { attempt ->
            val token = (tokens.token() as? ApiOutcome.Ok)?.value ?: return null
            val reply = transport.get(path(circle, hash), token) ?: return null
            when {
                reply.status == HTTP_OK -> return reply.bytes
                reply.status == HTTP_UNAUTHORIZED && attempt == 0 -> tokens.rejectToken()
                else -> return null
            }
        }
        return null
    }
}
