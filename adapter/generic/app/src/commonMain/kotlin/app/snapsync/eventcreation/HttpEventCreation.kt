package app.snapsync.eventcreation

import app.snapsync.model.runCatchingCancellable
import app.snapsync.model.CreateOutcome
import app.snapsync.ports.EventCreation

import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The [EventCreation] over an injected Ktor [HttpClient] (the engine — Darwin on iOS — is
 * supplied by the composition root, so this stays platform-neutral and testable with `MockEngine`),
 * the twin of `HttpDeviceFilesSource`. It `POST`s `<host>/events` (HTTPS, default ATS) with a JSON
 * body `{ "name": <trimmed name>, "startsAt": <canonical start> }`, parses a
 * `201 { eventId, name, createdAt, startsAt }`, maps a `400` refusing the date range (its body names
 * `startsAt` or `endsAt`) to [CreateOutcome.InvalidWindow] and any other `400` to
 * [CreateOutcome.InvalidName], and any other non-2xx / transport / parse failure to
 * [CreateOutcome.Transient].
 *
 * The body is the only place the edge says WHICH field it refused, so it is read — but only to pick
 * between two refusals: a `400` whose body names neither date stays a refused name, as every `400` was
 * before the range could be refused on its own.
 *
 * `startsAt` is sent **verbatim**: the caller's contract is that it is already the canonical cutoff shape
 * (capability `photo-sharing`), and the backend rejects anything else with a `400`. Reformatting or
 * re-deriving it here would introduce a second origin for a value whose whole point is having exactly one.
 */
class HttpEventCreation(
    private val client: HttpClient,
    host: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : EventCreation {

    private val base = host.trimEnd('/')

    override suspend fun create(name: String, startsAt: String, endsAt: String?): CreateOutcome =
        runCatchingCancellable {
            val response = client.post("$base/events") {
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString(CreateRequest.serializer(), CreateRequest(name, startsAt, endsAt)),
                )
            }
            when (response.status) {
                HttpStatusCode.Created ->
                    json.decodeFromString(CreatedDto.serializer(), response.bodyAsText())
                        .let { CreateOutcome.Created(eventId = it.eventId, name = it.name) }
                HttpStatusCode.BadRequest -> refusal(response.bodyAsText())
                else -> CreateOutcome.Transient
            }
        }.getOrElse { CreateOutcome.Transient }

    private fun refusal(body: String): CreateOutcome =
        if (WINDOW_FIELDS.any { it in body }) CreateOutcome.InvalidWindow else CreateOutcome.InvalidName

    // `endsAt` is sent verbatim like `startsAt`, and omitted entirely when null (encodeDefaults is off, so
    // a null default is not serialized) — an absent `endsAt` is the backend's legacy `+30d` fallback signal.
    @Serializable
    private class CreateRequest(val name: String, val startsAt: String, val endsAt: String? = null)

    private companion object {
        /** The fields a date-range refusal names (`invalid startsAt` / `invalid endsAt`). */
        val WINDOW_FIELDS = listOf("startsAt", "endsAt")
    }

    @Serializable
    private class CreatedDto(
        val eventId: String,
        val name: String? = null,
        val createdAt: String? = null,
        val startsAt: String? = null,
        val endsAt: String? = null,
    )
}
