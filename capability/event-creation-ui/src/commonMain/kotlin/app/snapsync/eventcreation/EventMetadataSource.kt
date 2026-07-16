package app.snapsync.eventcreation

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Fetches an event's human-readable name by id — the scan-path name source (create already receives
 * the name from `POST /events`). Best-effort and non-throwing: a failure/offline/404 yields `null` so
 * joining never blocks on the cosmetic name (see `event-link` — *Event name is fetched, not
 * carried in the event link*).
 */
interface EventMetadataSource {
    suspend fun name(eventId: String): String?
}

/**
 * [EventMetadataSource] over an injected Ktor [HttpClient] and host (Darwin on iOS, `MockEngine` in
 * tests — the twin of [HttpEventCreationClient]). `GET <host>/events/<eventId>`; parses `name` from a
 * `200 { eventId, name, createdAt }`, maps any non-2xx / transport / parse failure to `null`.
 */
class HttpEventMetadataSource(
    private val client: HttpClient,
    host: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : EventMetadataSource {

    private val base = host.trimEnd('/')

    override suspend fun name(eventId: String): String? =
        runCatching {
            val response = client.get("$base/events/$eventId")
            if (response.status == HttpStatusCode.OK) {
                json.decodeFromString(MetaDto.serializer(), response.bodyAsText()).name
            } else {
                null
            }
        }.getOrNull()

    @Serializable
    private class MetaDto(
        val eventId: String? = null,
        val name: String? = null,
        val createdAt: String? = null,
        val startsAt: String? = null,
    )
}
