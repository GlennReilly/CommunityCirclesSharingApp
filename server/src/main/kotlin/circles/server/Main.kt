package circles.server

import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

private const val DEFAULT_BIND = "0.0.0.0"
private const val DEFAULT_PORT = 8080
private const val ENV_BIND = "CIRCLES_BIND"
private const val ENV_PORT = "CIRCLES_PORT"

/** Health check body; the real /healthz, /readyz and /version come with M1. */
const val HEALTHY_BODY = "ok"

/** M0 stub: only a health endpoint. The event log, auth and sync arrive in M1 (see the spec). */
fun Application.module() {
    routing {
        get("/healthz") { call.respondText(HEALTHY_BODY) }
    }
}

fun main() {
    val bind = System.getenv(ENV_BIND) ?: DEFAULT_BIND
    val port = System.getenv(ENV_PORT)?.toIntOrNull() ?: DEFAULT_PORT
    embeddedServer(Netty, port = port, host = bind, module = Application::module).start(wait = true)
}
