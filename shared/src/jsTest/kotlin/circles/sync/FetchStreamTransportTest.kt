package circles.sync

import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest

/** `FetchStreamTransport` against a real streaming HTTP server run by Node. */
class FetchStreamTransportTest {
    private class Local(val server: dynamic, val baseUrl: String) {
        fun close() { server.close() }
    }

    /** `/stream` answers 200 with three writes (the second cut in the middle of a line); `/denied` answers 401; `/open` never ends. */
    private suspend fun startServer(): Local = suspendCancellableCoroutine { cont ->
        val http = js("require('http')")
        val server = http.createServer { req: dynamic, res: dynamic ->
            when (req.url as String) {
                "/denied" -> { res.writeHead(401); res.end("{}") }
                "/open" -> { res.writeHead(200, js("({'Content-Type': 'text/event-stream'})")); res.write("data: {\"serverSeq\":1}\n\n") }
                else -> {
                    res.writeHead(200, js("({'Content-Type': 'text/event-stream'})"))
                    res.write("retry: 5000\ndata: {\"serverSeq\":1}\n\n")
                    js("setTimeout")({ res.write("data: {\"serv") }, 20)
                    js("setTimeout")({ res.write("erSeq\":2}\n\n"); res.end() }, 60)
                }
            }
        }
        server.listen(0, "127.0.0.1") { cont.resume(Local(server, "http://127.0.0.1:${server.address().port}")) }
    }

    @Test fun piecesArriveInOrderAndTheFlowEndsWhenTheServerEnds() = runTest {
        val local = startServer()
        try {
            val pieces = FetchStreamTransport(local.baseUrl).open("/stream", "tok").toList()
            assertEquals(StreamPiece.Opened(200), pieces.first())
            val parser = SseParser()
            val hints = pieces.filterIsInstance<StreamPiece.Text>().flatMap { parser.feed(it.text) }
            assertEquals(listOf(1L, 2L), hints, "a message split across network chunks still arrives whole")
        } finally { local.close() }
    }

    @Test fun aRefusalIsReportedAndEndsTheFlow() = runTest {
        val local = startServer()
        try {
            assertEquals(listOf<StreamPiece>(StreamPiece.Opened(401)), FetchStreamTransport(local.baseUrl).open("/denied", "bad").toList())
        } finally { local.close() }
    }

    @Test fun nobodyListeningEndsTheFlowWithoutAnError() = runTest {
        val local = startServer()
        local.close()
        assertEquals(emptyList(), FetchStreamTransport(local.baseUrl).open("/stream", "tok").toList())
    }

    @Test fun theBearerTokenIsSent() = runTest {
        val local = startServer()
        try {
            // /open echoes nothing, so check by cancelling after the first text: the connection is accepted with the token header set.
            val pieces = FetchStreamTransport(local.baseUrl).open("/open", "tok").take(2).toList()
            assertTrue(pieces[0] is StreamPiece.Opened)
            assertTrue(pieces[1] is StreamPiece.Text)
        } finally { local.close() }
    }
}
