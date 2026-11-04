package circles.sync

import kotlinx.coroutines.await
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.khronos.webgl.Uint8Array

private const val BLOB_TIMEOUT_MILLIS = 60_000

/** [BlobTransport] over `fetch`: raw bytes, no JSON, same origin (ADR 0026, 0037). A network failure or timeout is null. */
class FetchBlobTransport(private val baseUrl: String = "") : BlobTransport {
    private suspend fun call(method: String, path: String, token: String, body: ByteArray?): dynamic {
        val controller = js("new AbortController()")
        val headers = js("({})")
        headers["Authorization"] = "Bearer $token"
        if (body != null) headers["Content-Type"] = "application/octet-stream"
        val init = js("({})")
        init.method = method
        init.headers = headers
        init.signal = controller.signal
        init.credentials = "omit"
        if (body != null) {
            val raw = body.unsafeCast<Int8Array>()
            init.body = Uint8Array(raw.buffer, raw.byteOffset, raw.length)
        }
        val timer = js("setTimeout")({ controller.abort() }, BLOB_TIMEOUT_MILLIS)
        try {
            return js("fetch")(baseUrl + path, init).unsafeCast<kotlin.js.Promise<dynamic>>().await()
        } finally {
            js("clearTimeout")(timer)
        }
    }

    override suspend fun put(path: String, body: ByteArray, token: String): Int? =
        try {
            call("PUT", path, token, body).status as Int
        } catch (_: Throwable) {
            null
        }

    override suspend fun get(path: String, token: String): BlobReply? =
        try {
            val response = call("GET", path, token, null)
            val status = response.status as Int
            if (status != HTTP_OK) BlobReply(status, ByteArray(0))
            else BlobReply(status, Int8Array((response.arrayBuffer() as kotlin.js.Promise<ArrayBuffer>).await()).unsafeCast<ByteArray>())
        } catch (_: Throwable) {
            null
        }
}
