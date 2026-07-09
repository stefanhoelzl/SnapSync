package app.snapsync.world

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The Ktor `MockEngine` mini-edge (capability `harness-world-model`): a routing `HttpClient` that
 * answers the app-side metadata calls off the [store], so the REAL common-Ktor seams
 * (`HttpDeviceFilesSource`, `HttpEventUnionSource`, `HttpEventCreationClient`, and the world's
 * `HttpDeviceManifestUploader`) run unmodified against it. It generalizes the repo's existing
 * single-response `MockEngine` test pattern (`HttpEventUnionSourceTest`) into a **route table**
 * dispatching on method + path:
 *
 * ```
 * GET  /files/devices/<id>          -> 200 [ per-device listing ]         (offline -> 502)
 * PUT  /devices/<id>                -> 201 ; store the device config doc (push-token registration)
 * GET  /events/<id>/files           -> 200 [ union ] | 404 (unregistered) (offline -> 502)
 * POST /events                      -> 201 { eventId, name, createdAt } + register marker
 * PUT  /events/<id>/devices/<id>    -> 200 ; deposit the manifest into the store
 * DELETE /events/<id>/devices/<id>  -> 200 ; leave cascade (rename→reap→GC) | 404 (unregistered event)
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
            // encodedPath like "/files/devices/<id>" -> ["files","devices","<id>"]
            val segments = request.url.encodedPath.split('/').filter { it.isNotEmpty() }
            val method = request.method
            val body = (request.body as? TextContent)?.text.orEmpty()

            when {
                // GET /files/devices/<id>
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
                    val name = runCatching {
                        json.parseToJsonElement(body).jsonObject["name"]?.jsonPrimitive?.content
                    }.getOrNull()
                    if (name.isNullOrBlank() || name.trim().length > 100) {
                        return@MockEngine respond("invalid name", HttpStatusCode.BadRequest)
                    }
                    val eventId = mintEventId(eventCounter++)
                    store.registerEvent(eventId, name.trim())
                    respond(
                        json.encodeToString(
                            CreatedEventDto.serializer(),
                            CreatedEventDto(eventId, name.trim(), CREATED_AT),
                        ),
                        HttpStatusCode.Created,
                        jsonHeaders(),
                    )
                }

                // GET /events/<id>  (details / existence — the join gate's fetch)
                method == HttpMethod.Get && segments.size == 2 && segments[0] == "events" -> {
                    if (store.offline) return@MockEngine respond("offline", HttpStatusCode.BadGateway)
                    if (!store.isRegistered(segments[1])) {
                        return@MockEngine respond("event not found", HttpStatusCode.NotFound)
                    }
                    respond(
                        json.encodeToString(
                            CreatedEventDto.serializer(),
                            CreatedEventDto(segments[1], store.nameOf(segments[1]) ?: "", CREATED_AT),
                        ),
                        HttpStatusCode.OK,
                        jsonHeaders(),
                    )
                }

                // PUT /events/<id>/devices/<id>  (manifest write / join enrollment)
                method == HttpMethod.Put && segments.size == 4 &&
                    segments[0] == "events" && segments[2] == "devices" -> {
                    if (store.offline) return@MockEngine respond("offline", HttpStatusCode.BadGateway)
                    store.putManifestJson(eventId = segments[1], deviceId = segments[3], json = body)
                    respond("", HttpStatusCode.OK, jsonHeaders())
                }

                // DELETE /events/<id>/devices/<id>  (leave — rename→reap→GC, gated on the event marker)
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
 * A fixed ISO-8601 `createdAt` for minted events (drift accepted; the world reads no clock).
 *
 * **Carries milliseconds on purpose.** The real backend mints this with `new Date().toISOString()`, which
 * always emits `.sss` — and a fractional-second `createdAt` is exactly what broke the capture-date cutoff
 * (`photo-date-cutoff`): reused verbatim it violates the second-precision invariant, and a bare
 * `NSISO8601DateFormatter` then fails to parse it, silently costing the bounded PhotoKit fetch. The world
 * previously minted a *tidier* timestamp than production, so the join gate's normalization went untested
 * against the shape it actually receives. A fake backend must not be cleaner than the real one.
 */
private const val CREATED_AT = "2026-01-01T00:00:00.000Z"

/** Mint a canonical `8-4-4-4-12` v4-shaped UUID deterministically from a counter (no clock/random). */
internal fun mintEventId(n: Long): String {
    val tail = n.toString(16).padStart(12, '0')
    return "00000000-0000-4000-8000-$tail"
}
