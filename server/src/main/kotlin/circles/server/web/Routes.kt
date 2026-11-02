package circles.server.web

import circles.api.API_PREFIX
import circles.api.ChallengeRequest
import circles.api.DEFAULT_PULL_LIMIT
import circles.api.PullResponse
import circles.api.PushRequest
import circles.api.PushResult
import circles.api.PushStatus
import circles.api.SessionRequest
import circles.api.SingleEventRequest
import circles.api.MAX_PUSH_EVENTS
import circles.api.ChallengeResponse
import circles.api.SessionResponse
import circles.api.VersionResponse
import circles.event.isValidRandomId
import circles.policy.CircleId
import circles.server.domain.AuthService
import circles.server.domain.Bucket
import circles.server.domain.Limit
import circles.server.domain.RateLimits
import circles.server.domain.Unlimited
import circles.server.logging.Log
import circles.server.logging.NoLog
import circles.server.logging.error
import circles.server.logging.warn
import circles.server.domain.DeviceRecord
import circles.server.domain.Entrance
import circles.server.domain.SessionResult
import circles.server.domain.Store
import circles.server.domain.SyncService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.plugins.origin
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer

/** Health check body. */
const val HEALTHY_BODY = "ok"
const val READY_BODY = "ready"
const val SERVER_NAME = "circles-server"
const val SERVER_VERSION = "0.1.0-M1"
private const val BEARER_PREFIX = "Bearer "
private const val LOGGED_FRAMES = 5
private const val FORWARDED_FOR = "X-Forwarded-For"

/** Everything the routes need. */
class Services(
    val sync: SyncService,
    val auth: AuthService,
    val store: Store,
    val log: Log = NoLog,
    val limits: RateLimits = Unlimited,
    /** Take the client address from the last `X-Forwarded-For` entry (ADR 0015). */
    val trustProxy: Boolean = false,
)

fun Application.module(services: Services) {
    install(RequestLogging) { log = services.log }
    install(StatusPages) {
        exception<CancellationException> { _, cause -> throw cause }
        exception<Throwable> { call, cause ->
            // Class and first frames only: the message may carry data (ADR 0016).
            services.log.error(
                "unhandled_error",
                "exception" to cause::class.qualifiedName,
                "frames" to cause.stackTrace.take(LOGGED_FRAMES).map { it.toString() },
            )
            call.respondProblem(HttpStatusCode.InternalServerError, "Internal error")
        }
    }
    routing {
        get("/healthz") { call.respondText(HEALTHY_BODY) }
        get("/readyz") {
            val ready = withContext(Dispatchers.IO) { services.store.isReady() }
            if (ready) call.respondText(READY_BODY) else call.respondProblem(HttpStatusCode.ServiceUnavailable, "Not ready")
        }
        get("/version") { call.respondJson(VersionResponse.serializer(), VersionResponse(SERVER_NAME, SERVER_VERSION)) }
        route(API_PREFIX) {
            authRoutes(services)
            circleRoutes(services)
        }
    }
}

private fun Route.authRoutes(services: Services) {
    val auth = services.auth
    post("/auth/challenge") {
        if (call.refusedByLimit(services, Bucket.AUTH, call.clientKey(services))) return@post
        when (val body = call.readJson(ChallengeRequest.serializer())) {
            is Body.Refused -> call.respondProblem(body.status, body.title, body.detail)
            is Body.Ok -> call.respondJson(ChallengeResponse.serializer(), withContext(Dispatchers.IO) { auth.challenge(body.value.deviceId) })
        }
    }
    post("/auth/session") {
        if (call.refusedByLimit(services, Bucket.AUTH, call.clientKey(services))) return@post
        when (val body = call.readJson(SessionRequest.serializer())) {
            is Body.Refused -> call.respondProblem(body.status, body.title, body.detail)
            is Body.Ok -> when (val result = withContext(Dispatchers.IO) { auth.session(body.value) }) {
                is SessionResult.Granted -> call.respondJson(SessionResponse.serializer(), result.session)
                SessionResult.Denied -> call.respondUnauthorized()
            }
        }
    }
}

private fun Route.circleRoutes(services: Services) {
    val sync = services.sync
    route("/circles") {
        post {
            if (!sync.isCreationAllowed) return@post call.respondProblem(HttpStatusCode.Forbidden, "Circle creation is disabled")
            if (call.refusedByLimit(services, Bucket.CREATE, call.clientKey(services))) return@post
            single(call, sync, Entrance.Create)
        }
        route("/{id}") {
            post("/join") {
                if (call.refusedByLimit(services, Bucket.JOIN, call.clientKey(services))) return@post
                val circle = call.circleOrNull() ?: return@post call.respondProblem(HttpStatusCode.NotFound, "No such circle")
                single(call, sync, Entrance.Join(circle))
            }
            route("/events") {
                post {
                    val (circle, device) = call.authorised(services.auth) ?: return@post
                    if (call.refusedByLimit(services, Bucket.PUSH, device.device.v)) return@post
                    when (val body = call.readJson(PushRequest.serializer())) {
                        is Body.Refused -> call.respondProblem(body.status, body.title, body.detail)
                        is Body.Ok -> {
                            if (body.value.events.size > MAX_PUSH_EVENTS) {
                                return@post call.respondProblem(HttpStatusCode.BadRequest, "Too many events", "At most $MAX_PUSH_EVENTS events per push.")
                            }
                            val results = withContext(Dispatchers.IO) { sync.submit(body.value.events, Entrance.Push(circle)) }
                            call.respondJson(ListSerializer(PushResult.serializer()), results)
                        }
                    }
                }
                get {
                    val (circle, _) = call.authorised(services.auth) ?: return@get
                    val after = call.parameters["after"]?.let { it.toLongOrNull()?.takeIf { n -> n >= 0 } }
                        ?: if (call.parameters["after"] == null) 0L else return@get call.respondProblem(HttpStatusCode.BadRequest, "Bad 'after'")
                    val limit = call.parameters["limit"]?.let { it.toIntOrNull()?.takeIf { n -> n >= 1 } }
                        ?: if (call.parameters["limit"] == null) DEFAULT_PULL_LIMIT else return@get call.respondProblem(HttpStatusCode.BadRequest, "Bad 'limit'")
                    val page = withContext(Dispatchers.IO) { sync.pull(circle, after, limit) }
                    call.respondJson(PullResponse.serializer(), page)
                }
            }
        }
    }
}

/** The two public endpoints: one event in, one result out. */
private suspend fun single(call: ApplicationCall, sync: SyncService, entrance: Entrance) {
    when (val body = call.readJson(SingleEventRequest.serializer())) {
        is Body.Refused -> call.respondProblem(body.status, body.title, body.detail)
        is Body.Ok -> {
            val result = withContext(Dispatchers.IO) { sync.submit(listOf(body.value.event), entrance).single() }
            val status = when (result.status) {
                PushStatus.ACCEPTED -> HttpStatusCode.Created
                PushStatus.DUPLICATE -> HttpStatusCode.OK
                PushStatus.REJECTED -> HttpStatusCode.UnprocessableEntity
            }
            call.respondJson(PushResult.serializer(), result, status)
        }
    }
}

private fun ApplicationCall.circleOrNull(): CircleId? = parameters["id"]?.takeIf { isValidRandomId(it) }?.let { CircleId(it) }

/** Checks the bearer token and that its device belongs to the circle in the path; answers 401, 403 or 404 otherwise. */
private suspend fun ApplicationCall.authorised(auth: AuthService): Pair<CircleId, DeviceRecord>? {
    val circle = circleOrNull()
    if (circle == null) {
        respondProblem(HttpStatusCode.NotFound, "No such circle")
        return null
    }
    val token = request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith(BEARER_PREFIX) }?.removePrefix(BEARER_PREFIX)
    val device = token?.let { withContext(Dispatchers.IO) { auth.authenticate(it) } }
    if (device == null) {
        respondUnauthorized()
        return null
    }
    if (device.circle != circle) {
        respondProblem(HttpStatusCode.Forbidden, "This device is not in that circle")
        return null
    }
    return circle to device
}

private suspend fun ApplicationCall.respondUnauthorized() {
    response.header(HttpHeaders.WWWAuthenticate, "Bearer")
    respondProblem(HttpStatusCode.Unauthorized, "Authentication required")
}

/** The key for per-address limits: the socket peer, or with a trusted proxy the address that proxy saw (the last entry). */
private fun ApplicationCall.clientKey(services: Services): String {
    if (services.trustProxy) {
        request.headers[FORWARDED_FOR]?.split(',')?.lastOrNull()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    }
    return request.origin.remoteAddress
}

/** Counts the request against [bucket]; when it is over the limit, answers 429 and returns true. */
private suspend fun ApplicationCall.refusedByLimit(services: Services, bucket: Bucket, key: String): Boolean =
    when (val result = services.limits.check(bucket, key)) {
        Limit.Allowed -> false
        is Limit.Limited -> {
            services.log.warn("rate_limited", "bucket" to bucket.label, "retry_after_seconds" to result.retryAfterSeconds)
            response.header(HttpHeaders.RetryAfter, result.retryAfterSeconds.toString())
            respondProblem(HttpStatusCode.TooManyRequests, "Too many requests", "Try again in ${result.retryAfterSeconds} seconds.")
            true
        }
    }
