package circles.server.support

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import java.net.URI
import java.net.http.HttpClient as JdkHttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Talks to a server started with `testApplication`, without opening a socket. */
class InProcessTransport(private val client: HttpClient) : Transport {
    override suspend fun send(method: String, path: String, body: String?, token: String?, headers: Map<String, String>): Reply {
        val response = client.request(path) {
            headers.forEach { (name, value) -> header(name, value) }
            this.method = HttpMethod.parse(method)
            token?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            if (body != null) {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
        }
        return Reply(response.status.value, response.bodyAsText(), response.headers[HttpHeaders.ContentType], response.headers[HttpHeaders.RetryAfter])
    }
}

/** Talks to a real server over a socket with the JDK HTTP client. */
class SocketTransport(private val baseUrl: String) : Transport {
    private val client = JdkHttpClient.newHttpClient()

    override suspend fun send(method: String, path: String, body: String?, token: String?, headers: Map<String, String>): Reply =
        withContext(Dispatchers.IO) {
            val builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
            headers.forEach { (name, value) -> builder.header(name, value) }
            token?.let { builder.header("Authorization", "Bearer $it") }
            if (body != null) builder.header("Content-Type", "application/json")
            builder.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
            val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
            Reply(response.statusCode(), response.body(), response.headers().firstValue("Content-Type").orElse(null), response.headers().firstValue("Retry-After").orElse(null))
        }
}
