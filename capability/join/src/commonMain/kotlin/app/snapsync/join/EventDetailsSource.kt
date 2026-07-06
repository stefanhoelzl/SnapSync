package app.snapsync.join

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The outcome of fetching an event's details for the join gate (capability `join-event`). Unlike the
 * cosmetic `EventMetadataSource.name()` (which collapses every non-200 to `null`), the gate MUST tell
 * a **missing** event (404 → block the join, an invalid/expired invite) apart from a **transient**
 * failure (network/5xx → offer Retry). [Found.name] is nullable because the marker's name is cosmetic.
 */
sealed interface EventDetails {
    /**
     * [name] is the (cosmetic, nullable) event name; [createdAt] is the event's creation timestamp
     * (ISO-8601 UTC `…Z`, already the cutoff shape) used to seed the join screen's capture-date cutoff
     * default (capability `photo-date-cutoff`). [createdAt] is nullable defensively (a legacy marker).
     */
    data class Found(val name: String?, val createdAt: String?) : EventDetails
    data object NotFound : EventDetails
    data object Failed : EventDetails
}

/**
 * Fetches an event's details by id for the join confirmation gate. Non-throwing: a transport/parse
 * error maps to [EventDetails.Failed], never an exception (the gate reduces it to a retryable state).
 */
interface EventDetailsSource {
    suspend fun fetch(eventId: String): EventDetails
}

/**
 * [EventDetailsSource] over an injected Ktor [HttpClient] and host (Darwin on iOS, `MockEngine` in
 * tests). `GET <host>/events/<eventId>`: `200 { eventId, name, createdAt }` → [EventDetails.Found];
 * `404` → [EventDetails.NotFound]; any other status / transport / parse failure → [EventDetails.Failed].
 */
class HttpEventDetailsSource(
    private val client: HttpClient,
    host: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : EventDetailsSource {

    private val base = host.trimEnd('/')

    override suspend fun fetch(eventId: String): EventDetails =
        runCatching {
            val response = client.get("$base/events/$eventId")
            when (response.status) {
                HttpStatusCode.OK -> {
                    val meta = json.decodeFromString(MetaDto.serializer(), response.bodyAsText())
                    EventDetails.Found(name = meta.name, createdAt = meta.createdAt)
                }
                HttpStatusCode.NotFound -> EventDetails.NotFound
                else -> EventDetails.Failed
            }
        }.getOrDefault(EventDetails.Failed)

    @Serializable
    private class MetaDto(
        val eventId: String? = null,
        val name: String? = null,
        val createdAt: String? = null,
    )
}
