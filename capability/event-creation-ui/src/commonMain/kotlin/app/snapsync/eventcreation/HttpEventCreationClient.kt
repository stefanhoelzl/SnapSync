package app.snapsync.eventcreation

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
 * The [EventCreationClient] over an injected Ktor [HttpClient] (the engine — Darwin on iOS — is
 * supplied by the composition root, so this stays platform-neutral and testable with `MockEngine`),
 * the twin of `HttpDeviceFilesSource`. It `POST`s `<host>/events` (HTTPS, default ATS) with a JSON
 * body `{ "name": <trimmed name>, "startsAt": <canonical start> }`, parses a
 * `201 { eventId, name, createdAt, startsAt }`, maps `400` to [CreateOutcome.InvalidName], and any other
 * non-2xx / transport / parse failure to [CreateOutcome.Transient].
 *
 * `startsAt` is sent **verbatim**: the caller's contract is that it is already the canonical cutoff shape
 * (capability `photo-selection-policy`), and the backend rejects anything else with a `400`. Reformatting or
 * re-deriving it here would introduce a second origin for a value whose whole point is having exactly one.
 */
class HttpEventCreationClient(
    private val client: HttpClient,
    host: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : EventCreationClient {

    private val base = host.trimEnd('/')

    override suspend fun create(name: String, startsAt: String): CreateOutcome =
        runCatching {
            val response = client.post("$base/events") {
                contentType(ContentType.Application.Json)
                setBody(
                    json.encodeToString(CreateRequest.serializer(), CreateRequest(name, startsAt)),
                )
            }
            when (response.status) {
                HttpStatusCode.Created ->
                    json.decodeFromString(CreatedDto.serializer(), response.bodyAsText())
                        .let { CreateOutcome.Created(eventId = it.eventId, name = it.name) }
                HttpStatusCode.BadRequest -> CreateOutcome.InvalidName
                else -> CreateOutcome.Transient
            }
        }.getOrElse { CreateOutcome.Transient }

    @Serializable
    private class CreateRequest(val name: String, val startsAt: String)

    @Serializable
    private class CreatedDto(
        val eventId: String,
        val name: String? = null,
        val createdAt: String? = null,
        val startsAt: String? = null,
    )
}
