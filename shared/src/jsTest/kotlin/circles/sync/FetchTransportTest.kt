package circles.sync

import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest

/** `FetchTransport` against a real local HTTP server run by Node. */
class FetchTransportTest {
    private class Local(val server: dynamic, val baseUrl: String) {
        fun close() { server.close() }
    }

    /** Echoes method, path, authorization and body as JSON; `/busy` answers 429 with Retry-After. */
    private suspend fun startServer(): Local = suspendCancellableCoroutine { cont ->
        val http = js("require('http')")
        val server = http.createServer { req: dynamic, res: dynamic ->
            val chunks = js("[]")
            req.on("data") { chunk: dynamic -> chunks.push(chunk) }
            req.on("end") {
                val body = js("Buffer").concat(chunks).toString("utf8") as String
                if ((req.url as String) == "/busy") {
                    res.writeHead(429, js("({'Retry-After': '12'})"))
                    res.end("{}")
                } else {
                    val echo = js("({})")
                    echo.method = req.method
                    echo.url = req.url
                    echo.authorization = req.headers["authorization"]
                    echo.contentType = req.headers["content-type"]
                    echo.body = body
                    res.writeHead(200, js("({'Content-Type': 'application/json'})"))
                    res.end(js("JSON").stringify(echo))
                }
            }
        }
        server.listen(0, "127.0.0.1") {
            cont.resume(Local(server, "http://127.0.0.1:${server.address().port}"))
        }
    }

    @Test fun aReplyCarriesStatusBodyAndWhatWasSent() = runTest {
        val local = startServer()
        try {
            val reply = assertNotNull(FetchTransport(local.baseUrl).send("POST", "/x?a=1", """{"k":1}""", "tok"))
            assertEquals(200, reply.status)
            val echo = js("JSON").parse(reply.body)
            assertEquals("POST", echo.method)
            assertEquals("/x?a=1", echo.url)
            assertEquals("Bearer tok", echo.authorization)
            assertEquals("application/json", echo.contentType)
            assertEquals("""{"k":1}""", echo.body)
        } finally { local.close() }
    }

    @Test fun noTokenAndNoBodySendNeitherHeader() = runTest {
        val local = startServer()
        try {
            val reply = assertNotNull(FetchTransport(local.baseUrl).send("GET", "/y", null, null))
            val echo = js("JSON").parse(reply.body)
            assertEquals("GET", echo.method)
            assertEquals(undefined, echo.authorization)
            assertEquals(undefined, echo.contentType)
        } finally { local.close() }
    }

    @Test fun errorStatusesAreRepliesAndRetryAfterIsRead() = runTest {
        val local = startServer()
        try {
            val reply = assertNotNull(FetchTransport(local.baseUrl).send("GET", "/busy", null, null))
            assertEquals(429, reply.status)
            assertEquals("12", reply.retryAfter)
        } finally { local.close() }
    }

    @Test fun aServerThatIsNotThereIsNull() = runTest {
        val local = startServer()
        local.close()
        assertNull(FetchTransport(local.baseUrl).send("GET", "/z", null, null))
    }
}
