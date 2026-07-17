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
 * The outcome of fetching an event's details (capability `join-event`) — the app's ONE
 * `GET /events/:id` client, serving both the join gate and the best-effort name refresh (which reads
 * [Found.name] and treats every other outcome as "no name this time"). The gate MUST tell
 * a **missing** event (404 → block the join, an invalid/expired invite) apart from a **transient**
 * failure (network/5xx → offer Retry). [Found.name] is **required and non-null**: an event always has a
 * name (the backend enforces name-required on create, capability `event-creation`), so a `200` lacking a
 * name is a malformed/transient response mapped to [Failed], never a nameless [Found] — the event-album
 * feature (capability `event-album`) titles the album from this name, so the gate never yields a null one.
 */
sealed interface EventDetails {
    /**
     * [name] is the (required, non-null) event name; [startsAt] is the event's **start date** — the host's
     * statement of when the event began (capability `event-creation`).
     *
     * [startsAt] is **required and non-null**, like [name]. It is always present on a `200`: the backend
     * rejects a non-canonical one on create and synthesizes one from `createdAt` for markers written
     * before it existed, so the app never sees a null. A `200` lacking it is therefore malformed /
     * transient → [Failed], never a [Found] with an invented one — [startsAt] is a **floor** on this
     * membership's cutoff (capability `photo-selection-policy`), and a client that defaulted it would be
     * silently *lowering* that floor. Failing loudly and retrying is the only safe reading.
     */
    data class Found(val name: String, val startsAt: String) : EventDetails
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
