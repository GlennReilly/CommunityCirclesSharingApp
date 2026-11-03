package circles.server.web

import io.ktor.http.CacheControl
import io.ktor.server.http.content.staticResources
import io.ktor.server.routing.Route

/** Classpath folder that holds the built web client (`index.html`, `circles.js`, `styles.css`); see `web/` and ADR 0037. */
const val WEB_RESOURCE_BASE = "web"
const val CONTENT_SECURITY_POLICY_HEADER = "Content-Security-Policy"
private const val WEB_INDEX = "$WEB_RESOURCE_BASE/index.html"

/**
 * The page may load only its own files and talk only to its own origin: no third-party calls (project rules).
 * Blob URLs and data URLs are allowed for images so that decrypted photos can be shown.
 */
const val WEB_CONTENT_SECURITY_POLICY =
    "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob: data:; connect-src 'self'; " +
        "object-src 'none'; base-uri 'none'; frame-ancestors 'none'; form-action 'self'"

/** True when the built client is on the classpath (the shadow jar includes it; a plain `serve` from sources may not). */
fun webAppPresent(loader: ClassLoader = Thread.currentThread().contextClassLoader): Boolean = loader.getResource(WEB_INDEX) != null

/**
 * Serves the client from the same origin as the API, at `/` (ADR 0037). Explicit routes (`/api`, `/healthz`, ...)
 * win over these. Files are revalidated on every load, so an upgraded server never serves an old script.
 */
fun Route.webApp() {
    if (!webAppPresent()) return
    staticResources("/", WEB_RESOURCE_BASE) {
        cacheControl { listOf(CacheControl.NoCache(null)) }
        modify { _, call ->
            call.response.headers.append(CONTENT_SECURITY_POLICY_HEADER, WEB_CONTENT_SECURITY_POLICY)
            call.response.headers.append("X-Content-Type-Options", "nosniff")
            call.response.headers.append("Referrer-Policy", "no-referrer")
        }
    }
}
