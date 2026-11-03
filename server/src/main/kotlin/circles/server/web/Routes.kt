package circles.server.web

import circles.api.API_PREFIX
import circles.api.ImportSummary
import circles.api.MAX_BLOB_BYTES
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
import circles.server.backup.AdminExport
import circles.server.backup.AdminImport
import circles.server.backup.AdminService
import circles.server.domain.BlobPutResult
import circles.server.domain.BlobService
import circles.server.domain.EventHub
import circles.server.domain.SubscribeResult
import circles.server.logging.info
import io.ktor.http.ContentType
import io.ktor.server.request.contentLength
import io.ktor.server.request.path
import io.ktor.server.response.respondOutputStream
import java.nio.file.Files
import io.ktor.server.request.receiveStream
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.routing.put
import io.ktor.utils.io.writeStringUtf8
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
const val SERVER_VERSION = "0.5.0-M5"
private const val BEARER_PREFIX = "Bearer "
private const val LOGGED_FRAMES = 5
private const val FORWARDED_FOR = "X-Forwarded-For"
const val STREAM_KEEP_ALIVE_MILLIS = 25_000L
private const val STREAM_RETRY_MILLIS = 5_000
private const val BLOB_CACHE_CONTROL = "private, max-age=31536000, immutable"
private const val NO_PROXY_BUFFERING = "X-Accel-Buffering"
private const val MAX_REPORTED_PROBLEMS = 10
const val IMPORT_TOKEN_HEADER = "X-Circles-Import-Token"
private val InsufficientStorage = HttpStatusCode(507, "Insufficient Storage")

/** Everything the routes need. */
class Services(
    val sync: SyncService,
    val auth: AuthService,
    val store: Store,
    val log: Log = NoLog,
    val limits: RateLimits = Unlimited,
    /** Take the client address from the last `X-Forwarded-For` entry (ADR 0015). */
    val trustProxy: Boolean = false,
    val blobs: BlobService? = null,
    val hub: EventHub = EventHub(),
    /** How often an idle event stream sends a keep-alive and re-checks its token (ADR 0026). */
    val streamKeepAliveMillis: Long = STREAM_KEEP_ALIVE_MILLIS,
    /** Signed steward export and import (ADR 0033); null leaves the admin endpoints off. */
    val admin: AdminService? = null,
    val adminImportMaxBytes: Long = 64L * 1024 * 1024,
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
            adminRoutes(services)
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
            blobRoutes(services)
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
                get("/stream") { stream(call, services) }
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

/**
 * `GET /admin/export/{circleId}` and `POST /admin/import` (ADR 0033). Authorised only by a signed request; every kind of
 * authentication failure gets the same bare `401` so it reveals nothing about which check failed.
 */
private fun Route.adminRoutes(services: Services) {
    val admin = services.admin ?: return
    route("/admin") {
        get("/export/{id}") {
            if (call.refusedByLimit(services, Bucket.ADMIN, call.clientKey(services))) return@get
            val circle = call.circleOrNull() ?: return@get call.respondUnauthorized()
            val header = call.request.headers[HttpHeaders.Authorization]
            when (val result = withContext(Dispatchers.IO) { admin.export(header, call.request.path(), circle) }) {
                AdminExport.Unauthorized -> call.respondUnauthorized()
                AdminExport.Forbidden -> call.respondProblem(HttpStatusCode.Forbidden, "Only a steward of this circle may export it")
                AdminExport.NotFound -> call.respondProblem(HttpStatusCode.NotFound, "No such circle")
                is AdminExport.Ready -> {
                    try {
                        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"circle-export.zip\"")
                        call.respondOutputStream(ContentType.Application.Zip) { Files.copy(result.file, this) }
                    } finally {
                        Files.deleteIfExists(result.file)
                    }
                }
            }
        }
        post("/import") {
            if (call.refusedByLimit(services, Bucket.ADMIN, call.clientKey(services))) return@post
            val declared = call.request.contentLength()
            if (declared != null && declared > services.adminImportMaxBytes) return@post call.respondProblem(HttpStatusCode.PayloadTooLarge, "Import too large", "Use the command line for large circles.")
            val body = withContext(Dispatchers.IO) { call.receiveStream().use { it.readNBytes((services.adminImportMaxBytes + 1).toInt()) } }
            if (body.size > services.adminImportMaxBytes) return@post call.respondProblem(HttpStatusCode.PayloadTooLarge, "Import too large", "Use the command line for large circles.")
            val header = call.request.headers[HttpHeaders.Authorization]
            when (val result = withContext(Dispatchers.IO) { admin.import(header, call.request.path(), body, call.request.headers[IMPORT_TOKEN_HEADER]) }) {
                AdminImport.Unauthorized -> call.respondUnauthorized()
                is AdminImport.Forbidden -> call.respondProblem(HttpStatusCode.Forbidden, "Not allowed", result.reason)
                AdminImport.Busy -> call.respondProblem(HttpStatusCode.TooManyRequests, "Another import is running", "Try again shortly.")
                is AdminImport.Invalid -> call.respondProblem(HttpStatusCode.UnprocessableEntity, "The file cannot be imported", result.problems.take(MAX_REPORTED_PROBLEMS).joinToString("; "))
                is AdminImport.Done -> {
                    services.log.info("admin_import", "events" to result.summary.eventsAccepted, "blobs" to result.summary.blobsStored)
                    call.respondJson(ImportSummary.serializer(), result.summary)
                }
            }
        }
    }
}

/** `PUT` and `GET /circles/{id}/blobs/{hash}` (ADR 0026): ciphertext in, the same ciphertext out. */
private fun Route.blobRoutes(services: Services) {
    val blobs = services.blobs ?: return
    route("/blobs/{hash}") {
        put {
            val (circle, device) = call.authorised(services.auth) ?: return@put
            if (call.refusedByLimit(services, Bucket.BLOB, device.device.v)) return@put
            val declared = call.request.contentLength()
            if (declared != null && declared > MAX_BLOB_BYTES) return@put call.respondProblem(HttpStatusCode.PayloadTooLarge, "Blob too large")
            val body = withContext(Dispatchers.IO) { call.receiveStream().use { it.readNBytes(MAX_BLOB_BYTES + 1) } }
            val hash = call.parameters["hash"].orEmpty()
            when (withContext(Dispatchers.IO) { blobs.put(circle, hash, body) }) {
                BlobPutResult.Created -> call.respondText("", status = HttpStatusCode.Created)
                BlobPutResult.AlreadyThere -> call.respondText("", status = HttpStatusCode.OK)
                BlobPutResult.BadHash -> call.respondProblem(HttpStatusCode.BadRequest, "Bad hash", "The hash is 64 lowercase hex characters.")
                BlobPutResult.TooLarge -> call.respondProblem(HttpStatusCode.PayloadTooLarge, "Blob too large")
                BlobPutResult.HashMismatch -> call.respondProblem(HttpStatusCode.UnprocessableEntity, "Hash mismatch", "The hash is not the BLAKE2b-256 of the body.")
                BlobPutResult.QuotaExceeded -> call.respondProblem(InsufficientStorage, "Circle storage is full")
            }
        }
        get {
            val (circle, _) = call.authorised(services.auth) ?: return@get
            val hash = call.parameters["hash"].orEmpty()
            val bytes = withContext(Dispatchers.IO) { blobs.get(circle, hash) } ?: return@get call.respondProblem(HttpStatusCode.NotFound, "No such blob")
            call.response.header(HttpHeaders.CacheControl, BLOB_CACHE_CONTROL)
            call.response.header(HttpHeaders.ETag, "\"$hash\"")
            call.respondBytes(bytes, ContentType.Application.OctetStream)
        }
    }
}

/**
 * `GET /circles/{id}/events/stream` (ADR 0026): one `data: {"serverSeq":N}` hint per newer event, a greeting with the
 * current number, and a keep-alive that also re-checks the token. Authentication happens before the stream opens.
 */
private suspend fun stream(call: ApplicationCall, services: Services) {
    val (circle, device) = call.authorised(services.auth) ?: return
    if (call.refusedByLimit(services, Bucket.STREAM, device.device.v)) return
    val token = call.bearerToken() ?: return call.respondUnauthorized()
    val subscription = when (val result = services.hub.subscribe(circle, device.device)) {
        SubscribeResult.DeviceLimit -> return call.respondProblem(HttpStatusCode.TooManyRequests, "Too many streams for this device")
        SubscribeResult.ServerLimit -> return call.respondProblem(HttpStatusCode.ServiceUnavailable, "The server has no room for another stream")
        is SubscribeResult.Subscribed -> result.subscription
    }
    subscription.use { open ->
        val greeting = withContext(Dispatchers.IO) { services.sync.latestSeq(circle) }
        services.log.info("stream_opened", "open" to services.hub.openStreams)
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.response.header(NO_PROXY_BUFFERING, "no")
        call.respondBytesWriter(contentType = ContentType.Text.EventStream) {
            writeStringUtf8("retry: $STREAM_RETRY_MILLIS\n\n")
            writeStringUtf8(hintMessage(greeting))
            flush()
            while (true) {
                val next = open.next(services.streamKeepAliveMillis)
                if (next != null) {
                    writeStringUtf8(hintMessage(next))
                } else {
                    // Idle: end the stream if the session expired or the device was revoked, else keep the connection warm.
                    if (withContext(Dispatchers.IO) { services.auth.authenticate(token) } == null) break
                    writeStringUtf8(": keep-alive\n\n")
                }
                flush()
            }
        }
        services.log.info("stream_closed", "open" to (services.hub.openStreams - 1))
    }
}

private fun hintMessage(serverSeq: Long): String = "data: {\"serverSeq\":$serverSeq}\n\n"

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

private fun ApplicationCall.bearerToken(): String? =
    request.headers[HttpHeaders.Authorization]?.takeIf { it.startsWith(BEARER_PREFIX) }?.removePrefix(BEARER_PREFIX)

/** Checks the bearer token and that its device belongs to the circle in the path; answers 401, 403 or 404 otherwise. */
private suspend fun ApplicationCall.authorised(auth: AuthService): Pair<CircleId, DeviceRecord>? {
    val circle = circleOrNull()
    if (circle == null) {
        respondProblem(HttpStatusCode.NotFound, "No such circle")
        return null
    }
    val token = bearerToken()
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
