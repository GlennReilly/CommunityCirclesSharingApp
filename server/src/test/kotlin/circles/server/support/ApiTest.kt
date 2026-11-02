package circles.server.support

import circles.server.web.module
import io.ktor.server.testing.testApplication

/** What an HTTP test gets: the server (for state checks) and a client that talks to it in-process. */
class ApiScope(val server: TestServer, val api: ApiClient, val http: io.ktor.client.HttpClient)

/** Runs [block] against a fresh server on Ktor's test host (no socket). */
fun apiTest(
    allowCreate: Boolean = true,
    rateLimit: Boolean = false,
    trustProxy: Boolean = false,
    log: circles.server.logging.Log = circles.server.logging.NoLog,
    blobQuotaBytes: Long = 1024L * 1024,
    block: suspend ApiScope.() -> Unit,
) {
    TestServer(allowCreate, rateLimit, trustProxy, log, blobQuotaBytes).use { server ->
        testApplication {
            application { module(server.services) }
            ApiScope(server, ApiClient(InProcessTransport(client), server.crypto), client).block()
        }
    }
}
