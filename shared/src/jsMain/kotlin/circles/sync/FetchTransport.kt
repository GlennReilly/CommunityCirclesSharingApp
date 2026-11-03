package circles.sync

import kotlinx.coroutines.await

private const val REQUEST_TIMEOUT_MILLIS = 30_000

/**
 * [HttpTransport] over the browser's `fetch`, same origin by default (ADR 0037). A network failure or a timeout is
 * `null`, which the engine reports as offline; any HTTP status, including 4xx and 5xx, is a reply. The timeout is a
 * real timer that aborts the request, so it also holds in tests that use virtual time.
 */
class FetchTransport(private val baseUrl: String = "") : HttpTransport {
    override suspend fun send(method: String, path: String, body: String?, token: String?): HttpReply? {
        val controller = js("new AbortController()")
        val headers = js("({})")
        if (body != null) headers["Content-Type"] = "application/json"
        if (token != null) headers["Authorization"] = "Bearer $token"
        val init = js("({})")
        init.method = method
        init.headers = headers
        init.signal = controller.signal
        init.cache = "no-store"
        init.credentials = "omit"
        if (body != null) init.body = body
        val timer = js("setTimeout")({ controller.abort() }, REQUEST_TIMEOUT_MILLIS)
        return try {
            val response = js("fetch")(baseUrl + path, init).unsafeCast<kotlin.js.Promise<dynamic>>().await()
            val text = (response.text() as kotlin.js.Promise<String>).await()
            val retryAfter = response.headers.get("Retry-After") as String?
            HttpReply(response.status as Int, text, retryAfter)
        } catch (_: Throwable) {
            null
        } finally {
            js("clearTimeout")(timer)
        }
    }
}
