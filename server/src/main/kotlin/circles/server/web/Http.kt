package circles.server.web

import circles.api.ApiJson
import circles.api.Problem
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException

/** Largest request body the server reads (a full push of 200 maximum-size events fits). */
const val MAX_BODY_BYTES = 16L * 1024 * 1024

val ProblemJson: ContentType = ContentType("application", "problem+json")

suspend fun ApplicationCall.respondProblem(status: HttpStatusCode, title: String, detail: String? = null) {
    respondText(
        ApiJson.encodeToString(Problem.serializer(), Problem(title = title, status = status.value, detail = detail)),
        ProblemJson,
        status,
    )
}

suspend fun <T> ApplicationCall.respondJson(serializer: KSerializer<T>, value: T, status: HttpStatusCode = HttpStatusCode.OK) {
    respondText(ApiJson.encodeToString(serializer, value), ContentType.Application.Json, status)
}

/** A request body, or the problem to answer with. */
sealed interface Body<out T> {
    data class Ok<out T>(val value: T) : Body<T>

    data class Refused(val status: HttpStatusCode, val title: String, val detail: String?) : Body<Nothing>
}

suspend fun <T> ApplicationCall.readJson(serializer: KSerializer<T>): Body<T> {
    val declared = request.contentLength()
    if (declared != null && declared > MAX_BODY_BYTES) return Body.Refused(HttpStatusCode.PayloadTooLarge, "Request too large", null)
    val text = receiveText()
    if (text.length > MAX_BODY_BYTES) return Body.Refused(HttpStatusCode.PayloadTooLarge, "Request too large", null)
    return try {
        Body.Ok(ApiJson.decodeFromString(serializer, text))
    } catch (e: SerializationException) {
        Body.Refused(HttpStatusCode.BadRequest, "Malformed request", "The body is not valid JSON for this endpoint.")
    } catch (e: IllegalArgumentException) {
        Body.Refused(HttpStatusCode.BadRequest, "Malformed request", "The body is not valid JSON for this endpoint.")
    }
}
