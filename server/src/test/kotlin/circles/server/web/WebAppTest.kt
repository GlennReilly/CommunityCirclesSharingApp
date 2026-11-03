package circles.server.web

import circles.api.API_PREFIX
import circles.server.support.TestServer
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Uses the stub client in `src/test/resources/web`; the real bundle is checked by running the shadow jar. */
class WebAppTest {
    private fun webTest(block: suspend io.ktor.client.HttpClient.() -> Unit) = TestServer().use { server ->
        testApplication {
            application { module(server.services) }
            client.block()
        }
    }

    @Test
    fun theClientIsServedAtTheRoot() = webTest {
        val page = get("/")
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue("circles.js" in page.bodyAsText())
        assertTrue(page.headers[HttpHeaders.ContentType].orEmpty().startsWith("text/html"))
    }

    @Test
    fun scriptsAreServedWithAJavaScriptType() = webTest {
        val script = get("/circles.js")
        assertEquals(HttpStatusCode.OK, script.status)
        assertTrue(script.headers[HttpHeaders.ContentType].orEmpty().contains("javascript"))
    }

    @Test
    fun everyFileCarriesTheSecurityHeaders() = webTest {
        for (path in listOf("/", "/circles.js")) {
            val reply = get(path)
            assertEquals(WEB_CONTENT_SECURITY_POLICY, reply.headers[CONTENT_SECURITY_POLICY_HEADER], path)
            assertEquals("nosniff", reply.headers["X-Content-Type-Options"], path)
            assertEquals("no-referrer", reply.headers["Referrer-Policy"], path)
            assertTrue(reply.headers[HttpHeaders.CacheControl].orEmpty().contains("no-cache"), path)
        }
    }

    @Test
    fun thePolicyAllowsOnlyOwnOriginAndNoThirdParties() {
        assertTrue("default-src 'self'" in WEB_CONTENT_SECURITY_POLICY)
        assertTrue("frame-ancestors 'none'" in WEB_CONTENT_SECURITY_POLICY)
        assertTrue("http" !in WEB_CONTENT_SECURITY_POLICY)
        assertTrue("unsafe" !in WEB_CONTENT_SECURITY_POLICY)
    }

    @Test
    fun unknownPathsAreNotFound() = webTest {
        assertEquals(HttpStatusCode.NotFound, get("/nope.js").status)
    }

    @Test
    fun explicitRoutesStillWinOverTheClient() = webTest {
        assertEquals("ok", get("/healthz").bodyAsText())
        val version = get("/version")
        assertEquals(HttpStatusCode.OK, version.status)
        assertTrue(assertNotNull(version.headers[HttpHeaders.ContentType]).contains("json"))
        assertEquals(HttpStatusCode.Unauthorized, get("$API_PREFIX/admin/export/x").status)
    }

    @Test
    fun pathTraversalIsRefusedNotServedAndNotAnError() = webTest {
        val reply = get("/css/../../etc/passwd")
        // The test client may normalise the path itself; either way nothing outside the bundle is returned.
        assertTrue(reply.status == HttpStatusCode.BadRequest || reply.status == HttpStatusCode.NotFound)
        assertTrue("root:" !in reply.bodyAsText())
    }

    @Test
    fun theClientIsNotServedWhenTheBundleIsMissing() {
        val empty = java.net.URLClassLoader(emptyArray(), null)
        assertTrue(!webAppPresent(empty))
        assertTrue(webAppPresent())
    }
}
