package circles.sync

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.await
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * [StreamTransport] over `fetch` and a stream reader (ADR 0026: `EventSource` cannot send an `Authorization` header).
 * Any failure to connect, or a dropped connection, simply ends the flow. Cancelling the collector aborts the request.
 */
class FetchStreamTransport(private val baseUrl: String = "") : StreamTransport {
    override fun open(path: String, token: String): Flow<StreamPiece> = flow {
        val controller = js("new AbortController()")
        try {
            val headers = js("({})")
            headers["Authorization"] = "Bearer $token"
            val init = js("({})")
            init.method = "GET"
            init.headers = headers
            init.signal = controller.signal
            init.cache = "no-store"
            init.credentials = "omit"
            val response = try {
                js("fetch")(baseUrl + path, init).unsafeCast<kotlin.js.Promise<dynamic>>().await()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                return@flow
            }
            val status = response.status as Int
            emit(StreamPiece.Opened(status))
            if (status != STATUS_OK) return@flow
            val reader = response.body.getReader()
            val decoder = js("new TextDecoder()")
            val decodeOptions = js("({stream: true})")
            while (true) {
                val chunk = try {
                    reader.read().unsafeCast<kotlin.js.Promise<dynamic>>().await()
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    break
                }
                if (chunk.done as Boolean) break
                emit(StreamPiece.Text(decoder.decode(chunk.value, decodeOptions) as String))
            }
        } finally {
            controller.abort()
        }
    }

    private companion object {
        const val STATUS_OK = 200
    }
}
