package app.snapsync.http

import app.snapsync.model.APP_VERSION_HEADER
import app.snapsync.model.ApnsPushToken
import app.snapsync.model.CreateEventRequest
import app.snapsync.model.DeviceFile
import app.snapsync.model.DeviceManifest
import app.snapsync.model.EventCreated
import app.snapsync.model.EventMeta
import app.snapsync.model.EventRenamed
import app.snapsync.model.MintRequest
import app.snapsync.model.RenewRequest
import app.snapsync.model.Reply
import app.snapsync.model.ResourceRole
import app.snapsync.model.UnionAsset
import app.snapsync.model.UnionResource
import app.snapsync.model.encodeToJson
import app.snapsync.ports.Backend
import co.touchlab.kermit.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.time.TimeSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull

private val httpLog = Logger.withTag("Http")

/**
 * The [Backend] port over HTTP — the device API's routes, their paths, methods and bodies, and nothing else.
 *
 * **One class for every platform.** It is written against Ktor's engine-neutral [HttpClient], which each composition
 * supplies: the Darwin engine on iOS (`darwinHttpClient`), a JVM engine where tests talk to the real backend, a
 * `MockEngine` in front of the world's mini-edge. A second platform supplies its engine, not a second copy of the
 * API.
 *
 * What it owns is wire: the `Authorization: Bearer` header on the calls that take a token, the calling build's
 * declared version ([appVersion]) on EVERY request — including the ungated `/attest/…` bootstrap, so an obsolete
 * build that can still mint a token learns it is obsolete on its first contact (capability `app-update-required`) —
 * the JSON shapes, and one log line per request. What a status MEANS is decided above it.
 *
 * **Decoding is strict where leniency is dangerous.** The per-device listing requires `assetId`, `role` (against the
 * closed [ResourceRole] vocabulary) and `filename`: the previous listing shape carried a field named `filename`
 * holding the storage KEY where this one holds the CAPTURE NAME, and a lenient decode would accept either and seed
 * nonsense. A success that does not decode is [Reply.Malformed], never an invented value.
 *
 * Every id in a path is a UUID, so no path encoding is required.
 */
@OptIn(ExperimentalEncodingApi::class)
class HttpBackend(
    private val client: HttpClient,
    base: String,
    private val appVersion: String,
) : Backend {

    private val base = base.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun challenge(): Reply<String> =
        exchange(HttpMethod.Get, "/attest/challenge", token = null) { field(it, "challenge") }

    override suspend fun mintToken(req: MintRequest): Reply<String> = exchange(
        HttpMethod.Post,
        "/attest/token",
        token = null,
        body = JsonObject(
            mapOf(
                "deviceId" to JsonPrimitive(req.deviceId),
                "keyId" to JsonPrimitive(req.keyId),
                "attestation" to JsonPrimitive(Base64.encode(req.attestation)),
                "challenge" to JsonPrimitive(req.challenge),
            ),
        ).toString(),
    ) { field(it, "token") }

    override suspend fun renewToken(req: RenewRequest): Reply<String> = exchange(
        HttpMethod.Post,
        "/attest/renew",
        token = null,
        body = JsonObject(
            mapOf(
                "deviceId" to JsonPrimitive(req.deviceId),
                "assertion" to JsonPrimitive(Base64.encode(req.assertion)),
                "challenge" to JsonPrimitive(req.challenge),
            ),
        ).toString(),
    ) { field(it, "token") }

    // `endsAt` is sent verbatim like `startsAt`, and omitted entirely when null — an absent `endsAt` is the backend's
    // legacy `+30d` fallback signal.
    override suspend fun createEvent(token: String?, req: CreateEventRequest): Reply<EventCreated> = exchange(
        HttpMethod.Post,
        "/events",
        token,
        body = JsonObject(
            listOfNotNull(
                "name" to JsonPrimitive(req.name),
                "startsAt" to JsonPrimitive(req.startsAt),
                req.endsAt?.let { "endsAt" to JsonPrimitive(it) },
            ).toMap(),
        ).toString(),
    ) { text -> EventCreated(eventId = field(text, "eventId"), name = optional(text, "name")) }

    // Every field optional, as the backend sent it: whether an absent one makes the answer unusable is the reader's.
    override suspend fun getEvent(eventId: String): Reply<EventMeta> =
        exchange(HttpMethod.Get, "/events/$eventId", token = null) { text ->
            val meta = objectOf(text)
            EventMeta(
                eventId = meta.optional("eventId"),
                name = meta.optional("name"),
                createdAt = meta.optional("createdAt"),
                startsAt = meta.optional("startsAt"),
                endsAt = meta.optional("endsAt"),
                deletesAt = meta.optional("deletesAt"),
            )
        }

    // The route echoes the whole event; only the name is read here.
    override suspend fun renameEvent(token: String?, eventId: String, name: String): Reply<EventRenamed> = exchange(
        HttpMethod.Patch,
        "/events/$eventId",
        token,
        body = JsonObject(mapOf("name" to JsonPrimitive(name))).toString(),
    ) { text -> EventRenamed(optional(text, "name")) }

    override suspend fun joinEvent(token: String?, eventId: String, deviceId: String): Reply<Unit> =
        exchange(HttpMethod.Put, "/events/$eventId/devices/$deviceId", token) { }

    override suspend fun publishManifest(
        token: String?,
        eventId: String,
        deviceId: String,
        manifest: DeviceManifest,
    ): Reply<Unit> =
        exchange(HttpMethod.Put, "/events/$eventId/devices/$deviceId/manifest", token, body = manifest.encodeToJson()) { }

    override suspend fun leaveEvent(token: String?, eventId: String, deviceId: String): Reply<Unit> =
        exchange(HttpMethod.Delete, "/events/$eventId/devices/$deviceId", token) { }

    override suspend fun eventFiles(eventId: String): Reply<List<UnionAsset>> =
        exchange(HttpMethod.Get, "/events/$eventId/files", token = null) { text ->
            json.decodeFromString(ListSerializer(AssetDto.serializer()), text).map { dto ->
                UnionAsset(
                    deviceId = dto.deviceId,
                    assetId = dto.assetId,
                    creationDate = dto.creationDate,
                    resources = dto.resources.map { UnionResource(it.key, it.url, it.role, it.contentType, it.filename) },
                )
            }
        }

    override suspend fun deviceFiles(token: String?, deviceId: String): Reply<List<DeviceFile>> =
        exchange(HttpMethod.Get, "/files/devices/$deviceId", token) { text ->
            json.decodeFromString(ListSerializer(StoredDto.serializer()), text).map { DeviceFile(it.assetId, it.role, it.filename) }
        }

    override suspend fun putDeviceConfig(token: String?, deviceId: String, push: ApnsPushToken): Reply<Unit> =
        exchange(HttpMethod.Put, "/devices/$deviceId", token, body = deviceConfigJson(push)) { }

    /**
     * One request: the headers every call carries, the answer read into a [Reply], and one log line. Never throws
     * but for cancellation — a transport failure is [Reply.Unreachable], a success whose body [read] rejects is
     * [Reply.Malformed].
     */
    private suspend fun <T> exchange(
        method: HttpMethod,
        path: String,
        token: String?,
        body: String? = null,
        read: (String) -> T,
    ): Reply<T> {
        val url = "$base$path"
        val start = TimeSource.Monotonic.markNow()
        return try {
            val response = client.request(url) {
                this.method = method
                token?.let { header("Authorization", "Bearer $it") }
                header(APP_VERSION_HEADER, appVersion)
                body?.let {
                    contentType(ContentType.Application.Json)
                    setBody(it)
                }
            }
            val text = response.bodyAsText()
            httpLog.i {
                "${method.value} $url → ${response.status.value} " +
                    "(${start.elapsedNow().inWholeMilliseconds}ms, req=${body?.length ?: 0}, resp=${text.length})"
            }
            if (response.status.isSuccess()) decoded(text, read) else Reply.Refused(response.status.value, text)
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            httpLog.w(t) { "${method.value} $url → FAILED (${start.elapsedNow().inWholeMilliseconds}ms)" }
            Reply.Unreachable(t)
        }
    }

    private fun <T> decoded(text: String, read: (String) -> T): Reply<T> =
        try {
            Reply.Ok(read(text))
        } catch (e: IllegalArgumentException) {
            // kotlinx.serialization's decoding failures are all IllegalArgumentExceptions (SerializationException
            // among them), as are a missing field and a non-object body read through [field].
            Reply.Malformed(e.message ?: e::class.simpleName.orEmpty())
        }

    // The bodies whose every field is optional are read as a JSON object rather than through a generated serializer:
    // the shape is one line per field either way, and a decode-only serializer's encoding half is code no test can
    // reach. The listings keep their serializers, because their every field is REQUIRED — which is the strictness.

    private fun objectOf(text: String): JsonObject = json.parseToJsonElement(text).jsonObject

    /** The string (or number, as written) under [name], or `null` when it is absent or `null`. */
    private fun JsonObject.optional(name: String): String? = (get(name) as? JsonPrimitive)?.contentOrNull

    private fun optional(text: String, name: String): String? = objectOf(text).optional(name)

    /** A required string [name] of the JSON object [text]. */
    private fun field(text: String, name: String): String = requireNotNull(optional(text, name)) { "no `$name` in the body" }

    @Serializable
    private class AssetDto(val deviceId: String, val assetId: String, val creationDate: String, val resources: List<ResourceDto>)

    @Serializable
    private class ResourceDto(val key: String, val url: String, val role: String, val contentType: String, val filename: String)

    /** One stored resource, in the terms the backend addresses resources by. Every field required — see the class doc. */
    @Serializable
    private class StoredDto(val assetId: String, val role: ResourceRole, val filename: String)
}

/** The `devices/<id>` config body for [token] — always `kind: "apns"` in this app, and no event id: it is device-scoped. */
internal fun deviceConfigJson(token: ApnsPushToken): String = JsonObject(
    mapOf(
        "pushToken" to JsonObject(
            mapOf("kind" to JsonPrimitive("apns"), "token" to JsonPrimitive(token.token), "env" to JsonPrimitive(token.env)),
        ),
    ),
).toString()
