package app.snapsync.join

import app.snapsync.config.instantToCutoff
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [EventDetailsSource] over an injected Ktor [HttpClient] and host (Darwin on iOS, `MockEngine` in
 * tests). `GET <host>/events/<eventId>`: `200 { eventId, name, createdAt, startsAt }` with a **non-null
 * name AND a non-null `startsAt`** → [EventDetails.Found]; a `200` missing **either** →
 * [EventDetails.Failed] (malformed/transient, retryable); `404` → [EventDetails.NotFound]; any other
 * status / transport / parse failure → [EventDetails.Failed].
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
                    // Both are required: a 200 missing either is malformed → retryable Failed. Never a
                    // nameless Found (the event-album title needs a name), and never a Found with an
                    // invented floor (that would silently LOWER it).
                    val name = meta.name
                    val startsAt = meta.startsAt?.let(::canonicalOrNull)
                    if (name != null && startsAt != null) {
                        EventDetails.Found(name = name, startsAt = startsAt)
                    } else {
                        EventDetails.Failed
                    }
                }
                HttpStatusCode.NotFound -> EventDetails.NotFound
                else -> EventDetails.Failed
            }
        }.getOrDefault(EventDetails.Failed)

    /**
     * Normalize the fetched `startsAt` into the canonical cutoff shape, or `null` when it does not parse.
     *
     * This is the boundary that makes [EventDetails.Found.startsAt] canonical **by construction**, and it
     * is not ceremony. The backend guarantees the shape for events created *after* start dates existed —
     * but for a **legacy** marker it synthesizes `startsAt` from `createdAt`, which `toISOString()` mints
     * with MILLISECONDS. An off-shape floor is quietly poisonous downstream: the clamp is a *lexicographic*
     * `maxOf`, so `…T00:00:00.182Z` sorts before `…T00:00:00Z`; and were such a value to win the clamp it
     * would be persisted as the cutoff, which the iOS walk parses with a bare `NSISO8601DateFormatter`
     * that REJECTS a fractional second — silently costing the bounded PhotoKit fetch.
     *
     * `instantToCutoff` truncates toward the earlier instant (dropping the fraction), the inclusive
     * direction — so a photo taken within the cutoff's own second is admitted rather than lost.
     */
    private fun canonicalOrNull(raw: String): String? =
        runCatching { instantToCutoff(Instant.parse(raw)) }.getOrNull()

    @Serializable
    private class MetaDto(
        val eventId: String? = null,
        val name: String? = null,
        val createdAt: String? = null,
        val startsAt: String? = null,
    )
}
