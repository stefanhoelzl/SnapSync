package app.snapsync.eventcreation

import app.snapsync.ports.EventRename
import app.snapsync.ports.RenameOutcome

import io.ktor.client.HttpClient
import io.ktor.client.request.patch
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The [EventRename] over an injected Ktor [HttpClient] (the engine — Darwin on iOS — is supplied by the
 * composition root, so this stays platform-neutral and testable with `MockEngine`), the twin of
 * [HttpEventCreation] and seated beside it because they are the same event-registry seam seen from two
 * sides. It `PATCH`es `<host>/events/<eventId>` with a JSON body `{ "name": <trimmed name> }`, parses a
 * `200` event body, maps `400` to [RenameOutcome.InvalidName], and any other non-2xx / transport / parse
 * failure to [RenameOutcome.Transient].
 *
 * The device token rides on the injected client (the composition root installs it), exactly as it does
 * for the create call — there is no auth handling here.
 *
 * ⚠️ A `404` maps to [RenameOutcome.Transient] like every other non-`400` status, and that collapse is
 * deliberate rather than incidental — see [RenameOutcome.Transient] for why a lone `404` must never
 * acquire a distinct meaning on this path.
 *
 * The response's `name` is what the caller persists. It is **required** here: a `200` whose body cannot
 * be parsed, or which carries no name, is a malformed response mapped to [RenameOutcome.Transient]
 * rather than a success reporting a name this client invented.
 */
class HttpEventRename(
    private val client: HttpClient,
    host: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : EventRename {

    private val base = host.trimEnd('/')

    override suspend fun rename(eventId: String, name: String): RenameOutcome =
        runCatching {
            val response = client.patch("$base/events/$eventId") {
                contentType(ContentType.Application.Json)
                setBody(json.encodeToString(RenameRequest.serializer(), RenameRequest(name)))
            }
            when (response.status) {
                HttpStatusCode.OK ->
                    json.decodeFromString(RenamedDto.serializer(), response.bodyAsText())
                        .name
                        // A 200 with no name is malformed, not a nameless success.
                        ?.let { RenameOutcome.Renamed(it) }
                        ?: RenameOutcome.Transient
                HttpStatusCode.BadRequest -> RenameOutcome.InvalidName
                else -> RenameOutcome.Transient
            }
        }.getOrElse { RenameOutcome.Transient }

    @Serializable
    private class RenameRequest(val name: String)

    // The route echoes the whole event; only the name is read here. `ignoreUnknownKeys` carries the rest.
    @Serializable
    private class RenamedDto(val name: String? = null)
}
