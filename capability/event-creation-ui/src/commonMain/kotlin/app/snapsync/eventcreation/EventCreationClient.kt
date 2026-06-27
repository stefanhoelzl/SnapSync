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

/** The outcome of a `POST /event` create call — a closed set the use-case maps to [CreationStatus]. */
sealed interface CreateOutcome {
    /** `201` — the server minted the event; [eventId] is the canonical UUID to provision. */
    data class Created(val eventId: String) : CreateOutcome

    /** `400` — the server rejected the name. */
    data object InvalidName : CreateOutcome

    /** Any other non-2xx, transport, or parse failure. */
    data object Transient : CreateOutcome
}

/** The network seam for minting an event. */
interface EventCreationClient {
    suspend fun create(name: String): CreateOutcome
}

/**
 * The [EventCreationClient] over an injected Ktor [HttpClient] (the engine — Darwin on iOS — is
 * supplied by the composition root, so this stays platform-neutral and testable with `MockEngine`),
 * the twin of `HttpEventFilesSource`. It `POST`s `<host>/event` (HTTPS, default ATS) with a JSON
 * body `{ "name": <trimmed name> }`, parses a `201 { eventId, name, createdAt }`, maps `400` to
 * [CreateOutcome.InvalidName], and any other non-2xx / transport / parse failure to
 * [CreateOutcome.Transient].
 */
class HttpEventCreationClient(
    private val client: HttpClient,
    host: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : EventCreationClient {

    private val base = host.trimEnd('/')

    override suspend fun create(name: String): CreateOutcome =
        runCatching {
            val response = client.post("$base/event") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(CreateRequest.serializer(), CreateRequest(name)))
            }
            when (response.status) {
                HttpStatusCode.Created ->
                    CreateOutcome.Created(
                        json.decodeFromString(CreatedDto.serializer(), response.bodyAsText()).eventId,
                    )
                HttpStatusCode.BadRequest -> CreateOutcome.InvalidName
                else -> CreateOutcome.Transient
            }
        }.getOrElse { CreateOutcome.Transient }

    @Serializable
    private class CreateRequest(val name: String)

    @Serializable
    private class CreatedDto(
        val eventId: String,
        val name: String? = null,
        val createdAt: String? = null,
    )
}
