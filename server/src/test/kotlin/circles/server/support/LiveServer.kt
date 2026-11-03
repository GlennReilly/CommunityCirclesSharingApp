package circles.server.support

import circles.policy.CircleId
import circles.server.web.module
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

/** One response with its raw bytes and headers (names lower-cased). */
class RawResponse(val status: Int, val body: ByteArray, val headers: Map<String, String>)

/** A [TestServer] listening on a real local port, for tests that need binary bodies or streaming responses. */
class LiveServer(val server: TestServer) : AutoCloseable {
    private val engine = embeddedServer(Netty, port = 0, host = "127.0.0.1") { module(server.services) }.start(wait = false)
    private val http = HttpClient.newHttpClient()
    val baseUrl: String = "http://127.0.0.1:${runBlocking { engine.engine.resolvedConnectors().first().port }}"
    val api = ApiClient(SocketTransport(baseUrl), server.crypto)

    fun request(method: String, path: String, token: String? = null, body: ByteArray? = null, authorization: String? = null, importToken: String? = null): RawResponse {
        val builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
        token?.let { builder.header("Authorization", "Bearer $it") }
        authorization?.let { builder.header("Authorization", it) }
        importToken?.let { builder.header("X-Circles-Import-Token", it) }
        builder.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
        val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        return RawResponse(response.statusCode(), response.body(), response.headers().map().mapKeys { it.key.lowercase() }.mapValues { it.value.first() })
    }

    fun openStream(circle: CircleId, token: String?): SseReader = SseReader(http, "$baseUrl/api/v1/circles/${circle.v}/events/stream", token)

    override fun close() {
        engine.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
        server.close()
    }
}

/** Reads a server-sent event stream line by line on a background thread. */
class SseReader(http: HttpClient, url: String, token: String?) : AutoCloseable {
    private val lines = LinkedBlockingQueue<String>()
    private val headersArrived = CountDownLatch(1)

    @Volatile
    var status: Int = 0
        private set

    @Volatile
    var ended: Boolean = false
        private set

    @Volatile
    private var body: java.util.stream.Stream<String>? = null

    private val reader = thread(isDaemon = true) {
        try {
            val builder = HttpRequest.newBuilder(URI.create(url))
            token?.let { builder.header("Authorization", "Bearer $it") }
            val response = http.send(builder.build(), HttpResponse.BodyHandlers.ofLines())
            status = response.statusCode()
            body = response.body()
            headersArrived.countDown()
            response.body().use { it.forEach(lines::add) }
        } catch (_: Exception) {
            // The connection was closed or reset: the stream is over.
        } finally {
            headersArrived.countDown()
            ended = true
        }
    }

    /** Waits for the status line; 0 if nothing arrived in time. */
    fun awaitStatus(timeoutMillis: Long = 3_000): Int {
        headersArrived.await(timeoutMillis, TimeUnit.MILLISECONDS)
        return status
    }

    /** The next line, or null if none arrives within [timeoutMillis]. */
    fun nextLine(timeoutMillis: Long = 3_000): String? = lines.poll(timeoutMillis, TimeUnit.MILLISECONDS)

    /** The next `data:` line's sequence number, skipping other lines; null on timeout. */
    fun nextHint(timeoutMillis: Long = 3_000): Long? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (true) {
            val line = nextLine((deadline - System.currentTimeMillis()).coerceAtLeast(1)) ?: return null
            if (line.startsWith("data: ")) return Regex("\"serverSeq\":(\\d+)").find(line)?.groupValues?.get(1)?.toLong()
            if (System.currentTimeMillis() > deadline) return null
        }
    }

    /** Waits until the stream has ended; true if it did in time. */
    fun awaitEnd(timeoutMillis: Long = 3_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (!ended && System.currentTimeMillis() < deadline) Thread.sleep(20)
        return ended
    }

    /** Closing the body closes the connection, which is how a real client goes away. */
    override fun close() {
        body?.close()
        reader.interrupt()
    }
}
