package app.snapsync.world

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Ktor `MockEngine` mini-edge (capability `harness-world-model`): a routing `HttpClient` that
 * answers the app-side metadata calls off the [store], so the REAL common-Ktor seams
 * (`HttpDeviceFilesSource`, `HttpEventUnionSource`, `HttpEventCreation`, `HttpEventJoin` and
 * `HttpManifestPublisher`) run unmodified against it. It generalizes the repo's existing
 * single-response `MockEngine` test pattern (`HttpEventUnionSourceTest`) into a **route table**
 * dispatching on method + path:
 *
 * ```
 * GET  /files/devices/<id>          -> 200 [ per-device listing ]         (offline -> 502)
 * PUT  /devices/<id>                -> 201 ; store the device config doc (push-token registration)
 * GET  /events/<id>/files           -> 200 [ union ] | 404 (unregistered) (offline -> 502)
 * POST /events                      -> 201 { eventId, name, createdAt } + register marker
 * PATCH /events/<id>                -> 200 { … } ; rename (name only) | 400 | 404 (unregistered)
 * PUT  /events/<id>/devices/<id>    -> 200 ; deposit the manifest into the store
 * DELETE /events/<id>/devices/<id>  -> 200 ; leave (rename-only: mark departed) | 404 (unregistered event)
 * (unmatched)                       -> 404
 * ```
 *
 * The same returned [HttpClient] is injected into all four real seams, mirroring how the extension
 * composition root shares one `darwinHttpClient()`.
 */
fun miniEdgeClient(store: BackendStore): HttpClient {
    val json = Json { ignoreUnknownKeys = true }
    var eventCounter = 0L

    fun jsonHeaders() = headersOf(HttpHeaders.ContentType, "application/json")

    return HttpClient(
        MockEngine { request ->
            // encodedPath like "/api/v2/files/devices/<id>" -> ["files","devices","<id>"]. The leading
            // `/api/vN` is split off first, exactly as the backend's own `splitVersion` does, so both
            // versions are served side by side.
            //
            // An UNVERSIONED path is served by NOTHING, because that is what the real backend does: it
            // mounts `/api/v1` and `/api/v2`, and a device path carrying no prefix matches neither. This
            // briefly defaulted to v1, as scaffolding while the seams still spoke it — a default that
            // outlived the transition would let a seam built with a prefix-less base pass every world
            // test and 404 against the real backend, which is precisely the divergence a mini-edge is
            // for catching.
            val raw = request.url.encodedPath.split('/').filter { it.isNotEmpty() }
            val versioned = raw.size >= 2 && raw[0] == "api" && VERSION_SEGMENT.matches(raw[1])
            if (!versioned) return@MockEngine respond("not found", HttpStatusCode.NotFound)
            val version = raw[1].removePrefix("v").toInt()
            val segments = raw.drop(2)
            val method = request.method
            val body = (request.body as? TextContent)?.text.orEmpty()

            // The version gate (capability `min-app-version`), OFF unless armed. It precedes every route
            // — including the ungated ones — because a build too old to be served cannot be helped by
            // reaching one, which is the ordering the real gate takes for the same reason.
            refusedForVersion(store, version, request.headers[APP_VERSION_HEADER])
                ?.let { return@MockEngine respond(it, HttpStatusCode.UpgradeRequired, jsonHeaders()) }

            if (version == 2) {
                v2Route(store, json, method, segments, body)?.let { return@MockEngine it }
            }

            when {
                // GET /files/devices/<id>  — v1: object names plus a synthetic url
                method == HttpMethod.Get && segments.size == 3 &&
                    segments[0] == "files" && segments[1] == "devices" -> {
                    if (store.offline) return@MockEngine respond("offline", HttpStatusCode.BadGateway)
                    val listing = store.deviceListing(segments[2])
                    respond(
                        json.encodeToString(ListSerializer(FileEntryDto.serializer()), listing),
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

                // PUT /devices/<id>  (push-token registration)
                method == HttpMethod.Put && segments.size == 2 &&
                    segments[0] == "devices" -> {
                    store.putDeviceConfig(segments[1], body)
                    respond("", HttpStatusCode.Created, jsonHeaders())
                }

                // GET /events/<id>/files
                method == HttpMethod.Get && segments.size == 3 &&
                    segments[0] == "events" && segments[2] == "files" -> {
                    if (store.offline) return@MockEngine respond("offline", HttpStatusCode.BadGateway)
                    val union = store.union(segments[1])
                        ?: return@MockEngine respond("event not found", HttpStatusCode.NotFound)
                    respond(
                        json.encodeToString(ListSerializer(UnionAssetDto.serializer()), union),
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

                // POST /events
                method == HttpMethod.Post && segments.size == 1 && segments[0] == "events" -> {
                    when (val parsed = parseCreateEvent(json, body)) {
                        is CreateEvent.Invalid ->
                            respond(parsed.reason, HttpStatusCode.BadRequest)
                        is CreateEvent.Ok -> {
                            val eventId = mintEventId(eventCounter++)
                            store.registerEvent(eventId, parsed.name, parsed.startsAt, parsed.endsAt)
                            respond(
                                json.encodeToString(
                                    CreatedEventDto.serializer(),
                                    CreatedEventDto(
                                        eventId,
                                        parsed.name,
                                        CREATED_AT,
                                        parsed.startsAt,
                                        parsed.endsAt,
                                        deletesAt = deleteBy(CREATED_AT, parsed.startsAt),
                                    ),
                                ),
                                HttpStatusCode.Created,
                                jsonHeaders(),
                            )
                        }
                    }
                }

                // GET /events/<id>  (details / existence — the join gate's fetch)
                method == HttpMethod.Get && segments.size == 2 && segments[0] == "events" -> {
                    if (store.offline) return@MockEngine respond("offline", HttpStatusCode.BadGateway)
                    if (!store.isRegistered(segments[1])) {
                        return@MockEngine respond("event not found", HttpStatusCode.NotFound)
                    }
                    respond(
                        json.encodeToString(CreatedEventDto.serializer(), eventDetails(store, segments[1])),
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

                // PATCH /events/<id>  (rename, capability `event-rename`) — the ONE route that rewrites
                // a registered event, and it rewrites `name` alone.
                method == HttpMethod.Patch && segments.size == 2 && segments[0] == "events" ->
                    renameEvent(store, json, segments[1], body, ::jsonHeaders)
                // PUT /events/<id>/devices/<id>  — v1: manifest write AND join enrollment. Frozen: its
                // publish really does mark the membership active, and that stays true here.
                method == HttpMethod.Put && segments.size == 4 &&
                    segments[0] == "events" && segments[2] == "devices" -> {
                    if (store.offline) return@MockEngine respond("offline", HttpStatusCode.BadGateway)
                    store.putManifestJson(eventId = segments[1], deviceId = segments[3], json = body)
                    respond("", HttpStatusCode.OK, jsonHeaders())
                }

                // DELETE /events/<id>/devices/<id>  (leave — rename-only mark-departed, gated on the marker)
                method == HttpMethod.Delete && segments.size == 4 &&
                    segments[0] == "events" && segments[2] == "devices" -> {
                    if (!store.isRegistered(segments[1])) {
                        return@MockEngine respond("event not found", HttpStatusCode.NotFound)
                    }
                    store.leave(eventId = segments[1], deviceId = segments[3])
                    respond("", HttpStatusCode.OK, jsonHeaders())
                }

                else -> respond("not found", HttpStatusCode.NotFound)
            }
        },
    )
}

/**
 * The routes only `/api/v2` serves, or null when this request is not one of them.
 *
 * Extracted so the dispatcher stays under the harness tier's complexity ceiling — a budget that may only
 * fall (`complexity-budgets`), so a route table that grows gets split rather than a number that gets
 * raised. It also puts the version's whole route table in one readable place.
 */
private fun MockRequestHandleScope.v2Route(
    store: BackendStore,
    json: Json,
    method: HttpMethod,
    segments: List<String>,
    body: String,
): HttpResponseData? {
    val headers = headersOf(HttpHeaders.ContentType, "application/json")
    val offline = { respond("offline", HttpStatusCode.BadGateway) }
    return when {
        // GET /files/devices/<id> — identity terms, and no minted url.
        method == HttpMethod.Get && segments.size == 3 &&
            segments[0] == "files" && segments[1] == "devices" ->
            if (store.offline) offline() else respond(
                json.encodeToString(
                    ListSerializer(DeviceResourceDto.serializer()),
                    store.deviceListingV2(segments[2]),
                ),
                HttpStatusCode.OK,
                headers,
            )

        // PUT /events/<id>/devices/<id>/manifest — CONTRIBUTION ONLY. Replaces the asset set, enrolls
        // nobody, and does not reactivate a departed member. A publish from a non-member is refused
        // rather than silently creating one: modelling it as a create is the divergence that would let a
        // device pass here and fail against the real backend.
        method == HttpMethod.Put && segments.size == 5 && segments[0] == "events" &&
            segments[2] == "devices" && segments[4] == "manifest" -> when {
            store.offline -> offline()
            !store.isRegistered(segments[1]) -> respond("event not found", HttpStatusCode.NotFound)
            !store.putManifestV2Json(segments[1], segments[3], body) ->
                respond("not a member", HttpStatusCode.Conflict)
            else -> respond("", HttpStatusCode.OK, headers)
        }

        // PUT /events/<id>/devices/<id> — the JOIN, carrying no body.
        method == HttpMethod.Put && segments.size == 4 &&
            segments[0] == "events" && segments[2] == "devices" ->
            if (store.offline) offline() else when (store.join(segments[1], segments[3])) {
                JoinOutcome.NO_SUCH_EVENT -> respond("event not found", HttpStatusCode.NotFound)
                JoinOutcome.FULL -> respond("event full", HttpStatusCode.Conflict)
                JoinOutcome.ENROLLED -> respond("", HttpStatusCode.OK, headers)
            }

        else -> null
    }
}

/**
 * The `426` body when the version gate is armed and this request cannot be served, or null to proceed.
 *
 * Off unless [BackendStore.minAppVersion] is set: a gate that refused by default would fail every seam
 * that does not yet declare a version, which is all of them until the client half ships.
 */
private fun refusedForVersion(store: BackendStore, version: Int, declared: String?): String? {
    val minimum = store.minAppVersion ?: return null
    if (version != 2) return null
    if (declared != null && compareAppVersions(declared, minimum) >= 0) return null
    return """{"error":"app too old","minAppVersion":"$minimum"}"""
}

/**
 * `PATCH /events/<id>` — the rename (capability `event-rename`). Faithful to the real route: the same
 * name rule as create, a `404` for an unregistered event, and every other field left exactly as it was
 * (`registerEvent` overwrites only its non-null arguments, so passing just the name is a verbatim
 * rewrite). Existence is checked AFTER validation, matching the real route's order — so a bad name
 * against a missing event is a `400` in both places.
 */
private fun MockRequestHandleScope.renameEvent(
    store: BackendStore,
    json: Json,
    eventId: String,
    body: String,
    headers: () -> Headers,
): HttpResponseData {
    if (store.offline) return respond("offline", HttpStatusCode.BadGateway)
    val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
    val name = obj?.get("name")?.jsonPrimitive?.content
    if (name.isNullOrBlank() || name.trim().length > 100) {
        return respond("invalid name", HttpStatusCode.BadRequest)
    }
    if (!store.isRegistered(eventId)) return respond("event not found", HttpStatusCode.NotFound)
    store.registerEvent(eventId, name = name.trim())
    return respond(
        json.encodeToString(CreatedEventDto.serializer(), eventDetails(store, eventId)),
        HttpStatusCode.OK,
        headers(),
    )
}

/** A create-event body that passed the same checks the real route applies, or the reason it did not. */
private sealed interface CreateEvent {
    class Ok(val name: String, val startsAt: String, val endsAt: String) : CreateEvent
    class Invalid(val reason: String) : CreateEvent
}

/**
 * Validate `POST /events` exactly as the real route does — the mini-edge is a FAITHFUL edge, not a
 * lenient one. Accepting a sloppy value here would let a client ship a floor the real backend would
 * `400`, and the bug would surface only on device.
 *
 * Extracted from the dispatcher to keep it under the harness tier's complexity ceiling, which may only
 * fall (`complexity-budgets`).
 */
private fun parseCreateEvent(json: Json, body: String): CreateEvent {
    val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
    val name = obj?.get("name")?.jsonPrimitive?.content
    if (name.isNullOrBlank() || name.trim().length > 100) return CreateEvent.Invalid("invalid name")
    // `startsAt` is REQUIRED and must be canonical.
    val startsAt = obj["startsAt"]?.jsonPrimitive?.content
    if (startsAt == null || !CANONICAL_CUTOFF.matches(startsAt)) {
        return CreateEvent.Invalid("invalid startsAt")
    }
    // `endsAt` is creator-supplied at mint (capability `event-limits`): when present it must be canonical
    // AND strictly after `startsAt`; when absent it falls back to `startsAt + 30d`.
    val rawEndsAt = obj["endsAt"]?.jsonPrimitive?.content
    val endsAt = when {
        rawEndsAt == null -> plus30Days(startsAt)
        !CANONICAL_CUTOFF.matches(rawEndsAt) || rawEndsAt <= startsAt ->
            return CreateEvent.Invalid("invalid endsAt")
        else -> rawEndsAt
    }
    return CreateEvent.Ok(name.trim(), startsAt, endsAt)
}

/** The `/api/vN` prefix shape the mini-edge splits off, mirroring the backend's own matcher. */
private val VERSION_SEGMENT = Regex("v\\d+")

/** The header a v2 request declares its marketing version in (capability `min-app-version`). */
private const val APP_VERSION_HEADER = "x-snapsync-app-version"

/**
 * Compare two `X.Y` marketing versions NUMERICALLY, part by part; an unparseable version sorts oldest.
 *
 * Not a string comparison, and the difference is not academic: `"0.10" < "0.9"` lexicographically, so a
 * string compare would admit builds the gate exists to refuse and refuse builds it exists to admit —
 * silently, and only from the tenth release onward. The real gate pins this with a test for the same
 * reason; a harness that compared differently would let a client pass here and be refused in production.
 */
internal fun compareAppVersions(a: String, b: String): Int {
    fun parts(v: String): List<Int>? =
        v.trim().takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() || c == '.' } }
            ?.split('.')?.map { it.toIntOrNull() ?: return null }
    val pa = parts(a)
    val pb = parts(b)
    if (pa == null) return if (pb == null) 0 else -1
    if (pb == null) return 1
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val d = (pa.getOrNull(i) ?: 0) - (pb.getOrNull(i) ?: 0)
        if (d != 0) return d
    }
    return 0
}

/**
 * A fixed ISO-8601 `createdAt` for minted events (drift accepted; the world reads no clock).
 *
 * **Carries milliseconds on purpose.** The real backend mints this with `new Date().toISOString()`, which
 * always emits `.sss` — and a fractional-second `createdAt` is exactly what broke the capture-date cutoff
 * (`photo-selection-policy`): reused verbatim it violates the second-precision invariant, and a bare
 * `NSISO8601DateFormatter` then fails to parse it, silently costing the bounded PhotoKit fetch. The world
 * previously minted a *tidier* timestamp than production, so the join gate's normalization went untested
 * against the shape it actually receives. A fake backend must not be cleaner than the real one.
 */
private const val CREATED_AT = "2026-01-01T00:00:00.000Z"

/**
 * The canonical capture-date cutoff shape the real backend demands of `startsAt` (second precision, UTC,
 * no fraction, no offset — capability `photo-selection-policy`). Unlike [CREATED_AT], a `startsAt` is CLEAN by
 * contract: the real endpoint 400s anything else, so the world must too.
 */
private val CANONICAL_CUTOFF = Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$""")

/** The mini-edge's `endsAt` fallback (capability `event-limits`): `startsAt + 30d`, mirroring the real
 *  backend's absent-`endsAt` stamp. The app normalizes any sub-second precision, so the raw instant
 *  string is fine. */
private fun plus30Days(canonical: String): String = (Instant.parse(canonical) + 30.days).toString()

/**
 * The mini-edge's DERIVED retention deadline (capability `event-limits`), mirroring the real backend:
 * `max(createdAt, startsAt) + 30d`. Anchoring at the LATER of the two is what keeps a back-dated event
 * from being born already expired and a created-early event from dying inside its own window.
 */
private fun deleteBy(createdAt: String, startsAt: String): String {
    val anchor = maxOf(Instant.parse(createdAt), Instant.parse(startsAt))
    return (anchor + 30.days).toString()
}

/** Mint a canonical `8-4-4-4-12` v4-shaped UUID deterministically from a counter (no clock/random). */
internal fun mintEventId(n: Long): String {
    val tail = n.toString(16).padStart(12, '0')
    return "00000000-0000-4000-8000-$tail"
}

/**
 * The event-details wire shape `GET /events/<id>` and `PATCH /events/<id>` both serve — one builder, so a
 * rename's response can never drift from the details fetch that follows it.
 *
 * A marker registered without a start date is a LEGACY one: `startsAt` is synthesized from `createdAt`,
 * exactly as the real backend does on read. Note that inherits `createdAt`'s milliseconds — off-canonical
 * on purpose, so the app's normalization is exercised rather than assumed. `endsAt` likewise: stored when
 * creator-supplied, else `startsAt + 30d`.
 */
private fun eventDetails(store: BackendStore, eventId: String): CreatedEventDto {
    val startsAt = store.startsAtOf(eventId) ?: CREATED_AT
    val endsAt = store.endsAtOf(eventId) ?: plus30Days(startsAt)
    return CreatedEventDto(
        eventId,
        store.nameOf(eventId) ?: "",
        CREATED_AT,
        startsAt,
        endsAt,
        deletesAt = deleteBy(CREATED_AT, startsAt),
    )
}
