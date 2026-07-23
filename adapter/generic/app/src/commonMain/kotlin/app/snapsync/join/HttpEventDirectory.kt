package app.snapsync.join

import app.snapsync.ports.EventDetails
import app.snapsync.ports.EventDirectory

import app.snapsync.model.instantToCutoff
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlin.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * [EventDirectory] over an injected Ktor [HttpClient] and host (Darwin on iOS, `MockEngine` in
 * tests). `GET <host>/events/<eventId>`: `200 { eventId, name, createdAt, startsAt, endsAt, deletesAt }`
 * with a **non-null name, `startsAt`, `endsAt`, AND `deletesAt`** → [EventDetails.Found]; a `200` missing
 * **any** → [EventDetails.Failed] (malformed/transient, retryable); `404` → [EventDetails.NotFound]; any
 * other status / transport / parse failure → [EventDetails.Failed].
 *
 * The `404` ↔ `Failed` split is load-bearing beyond the join gate: it is the ONLY place "the event is
 * definitively gone" is separated from "I could not tell", and a membership is destroyed (capability
 * `leave-event`) on the former. Every ambiguous outcome must keep landing on [EventDetails.Failed].
 */
class HttpEventDirectory(
    private val client: HttpClient,
    host: String,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : EventDirectory {

    private val base = host.trimEnd('/')

    override suspend fun fetch(eventId: String): EventDetails =
        runCatching {
            val response = client.get("$base/events/$eventId")
            when (response.status) {
                HttpStatusCode.OK -> {
                    val meta = json.decodeFromString(MetaDto.serializer(), response.bodyAsText())
                    // All four are required: a 200 missing any is malformed → retryable Failed. Never a
                    // nameless Found (the event-album title needs a name), never a Found with an invented
                    // floor (that would silently LOWER it), and never one with an invented deadline (that
                    // would decide whether a membership is destroyed).
                    val name = meta.name
                    val startsAt = meta.startsAt?.let(::canonicalOrNull)
                    val endsAt = meta.endsAt?.let(::canonicalOrNull)
                    val deletesAt = meta.deletesAt?.let(::canonicalOrNull)
                    if (name != null && startsAt != null && endsAt != null && deletesAt != null) {
                        EventDetails.Found(
                            name = name,
                            startsAt = startsAt,
                            endsAt = endsAt,
                            deletesAt = deletesAt,
                        )
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
        val endsAt: String? = null,
        val deletesAt: String? = null,
    )
}
