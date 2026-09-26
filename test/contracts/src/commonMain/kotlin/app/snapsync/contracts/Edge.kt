package app.snapsync.contracts

import kotlinx.serialization.json.jsonArray

import io.ktor.client.request.get

import app.snapsync.model.APP_VERSION_HEADER
import app.snapsync.model.DeviceManifest
import app.snapsync.model.DeviceManifestAsset
import app.snapsync.model.ManifestResource
import app.snapsync.model.ResourceRole
import app.snapsync.model.encodeToJson
import app.snapsync.model.uploadKey
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

// ---- The Backend contract's shared vocabulary (`docs/testing.md`) ------------------------------------
//
// The contract is about the backend, seen through the port the app ships. A binding pairs an implementation of the
// port — the production `HttpBackend` in front of the real `api/` (`Live`) or the mini-edge (`Fake`), or the
// in-memory mock (`Fake`) — with a way to enter each clause's state: [BackendSetup], through the backend's PUBLIC
// surface, never by writing a stand-in's store directly. A setup step one backend cannot follow is a state that
// backend does not reach, and it says so with `Unreachable`.

/** A version every edge serves: the setup declares it, and so does a client under contract by default. */
const val SERVED_APP_VERSION = "99.0"

/** A version below any minimum an edge has ever set, so declaring it is a build the edge refuses. */
const val REFUSED_APP_VERSION = "0.0"

/** A bearer no edge issued. An edge that verifies tokens answers it `401`. */
const val FOREIGN_TOKEN = "foreign.0.not-a-signature-this-edge-issued"

/** The window every seeded event declares — fixed, so a clause can state the dates the edge must echo. */
const val SEEDED_STARTS_AT = "2030-01-01T00:00:00Z"
const val SEEDED_ENDS_AT = "2030-01-08T00:00:00Z"

/**
 * What the app declares about itself in a clause's state: the build version (the HTTP adapter's constructor value)
 * and the token the clause passes to the port's gated routes.
 */
class ClientIdentity(val appVersion: String, val token: String?) {
    companion object {
        val SERVED = ClientIdentity(SERVED_APP_VERSION, token = null)
    }
}

/** An event [EdgeSetup] created, as the edge answered the create. */
class CreatedEvent(val eventId: String, val name: String, val createdAt: String)

/**
 * One clause's entered state: the addresses the clause needs (minted by the edge, so they cannot be derived
 * from the clause id), and the identity the client under contract must declare in this state.
 */
class Seeded(
    val eventId: String,
    val deviceId: String,
    val event: CreatedEvent? = null,
    val identity: ClientIdentity = ClientIdentity.SERVED,
    val asset: SeededAsset? = null,
)

/** A backend clause's subject: the port, the addresses and identity its state was entered with, and the setup. */
class EdgeSubject<P>(
    val port: P,
    val seeded: Seeded,
    /**
     * The backend's public surface, for a clause whose promise is observable only on another route — a publish's
     * effect on the union. Null for a binding with no backend behind it.
     */
    private val edge: BackendSetup? = null,
) {
    /** The token this state's calls carry. */
    val token: String? get() = seeded.identity.token

    /** The backend's public surface; a clause asking for it on a binding with none fails naming that. */
    val setup: BackendSetup get() = requireNotNull(edge) { "this binding has no backend to read through" }
}

/** How a binding enters backend states: through the backend's public surface, whatever that backend is. */
interface BackendSetup {
    fun freshId(): String
    suspend fun createEvent(name: String): CreatedEvent
    suspend fun join(eventId: String, deviceId: String)

    /** Joins fresh devices until the backend answers `409` — capacity without restating the backend's configured one. */
    suspend fun fillToCapacity(eventId: String)
    suspend fun publish(eventId: String, deviceId: String, assets: List<SeededAsset>)

    /** The asset ids the event's union serves — a read of the public surface, as a member makes it. */
    suspend fun unionAssetIds(eventId: String): Set<String>

    /** One resource's bytes landing, as the app's uploader addresses them. */
    suspend fun upload(deviceId: String, asset: SeededAsset, role: ResourceRole)
}

/**
 * [BackendSetup] over the edge's public HTTP surface. Setup is not under contract, and every step checks its own
 * status so a setup failure names the step, not the clause.
 */
class EdgeSetup(private val client: HttpClient, base: String) : BackendSetup {
    private val base = base.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    @OptIn(ExperimentalUuidApi::class)
    override fun freshId(): String = Uuid.random().toString()

    override suspend fun createEvent(name: String): CreatedEvent {
        val body = buildJsonObject {
            put("name", JsonPrimitive(name))
            put("startsAt", JsonPrimitive(SEEDED_STARTS_AT))
            put("endsAt", JsonPrimitive(SEEDED_ENDS_AT))
        }
        val response = client.post("$base/events") {
            header(APP_VERSION_HEADER, SERVED_APP_VERSION)
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
        val created = json.parseToJsonElement(checked("create event", response).bodyAsText()).jsonObject
        return CreatedEvent(
            eventId = created.getValue("eventId").jsonPrimitive.content,
            name = created["name"]?.jsonPrimitive?.content ?: name,
            createdAt = created.getValue("createdAt").jsonPrimitive.content,
        )
    }

    override suspend fun join(eventId: String, deviceId: String) {
        checked("join $deviceId", client.put("$base/events/$eventId/devices/$deviceId") { served() })
    }

    /**
     * Joins fresh devices until the edge answers `409` — how a binding fills an event to capacity without
     * restating the edge's configured capacity here, where it would drift from the deployment it came from.
     */
    override suspend fun fillToCapacity(eventId: String) {
        repeat(MAX_CAPACITY_PROBE) {
            val response = client.put("$base/events/$eventId/devices/${freshId()}") { served() }
            if (response.status == HttpStatusCode.Conflict) return
            checked("fill to capacity", response)
        }
        error("setup step 'fill to capacity': still admitting after $MAX_CAPACITY_PROBE joins")
    }

    override suspend fun publish(eventId: String, deviceId: String, assets: List<SeededAsset>) {
        val manifest = DeviceManifest(deviceId, assets.map { it.manifestEntry() })
        checked(
            "publish manifest",
            client.put("$base/events/$eventId/devices/$deviceId/manifest") {
                served()
                contentType(ContentType.Application.Json)
                setBody(manifest.encodeToJson())
            },
        )
    }

    /** The asset ids the event's union serves — a read of the backend's public surface, as a member makes it. */
    override suspend fun unionAssetIds(eventId: String): Set<String> {
        val response = checked("read the union", client.get("$base/events/$eventId/files") { served() })
        return json.parseToJsonElement(response.bodyAsText()).jsonArray
            .mapTo(mutableSetOf()) { it.jsonObject.getValue("assetId").jsonPrimitive.content }
    }

    /** Uploads one resource's bytes, as the app's uploader addresses them. */
    override suspend fun upload(deviceId: String, asset: SeededAsset, role: ResourceRole) {
        checked(
            "upload ${asset.assetId}/${role.wire}",
            client.put("$base/files/devices/$deviceId/${asset.assetId}/${role.wire}?filename=${asset.filename}") {
                served()
                contentType(ContentType.Image.JPEG)
                setBody(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
            },
        )
    }

    private fun io.ktor.client.request.HttpRequestBuilder.served() = header(APP_VERSION_HEADER, SERVED_APP_VERSION)

    private companion object {
        const val MAX_CAPACITY_PROBE = 200
    }

    private fun checked(step: String, response: HttpResponse): HttpResponse {
        check(response.status.isSuccess()) { "setup step '$step' was refused by the edge: HTTP ${response.status.value}" }
        return response
    }
}

/** An asset a binding seeds: one capture declaring [roles], each uploaded under [filename]. */
class SeededAsset(val assetId: String, val roles: List<ResourceRole>, val filename: String = "IMG_0001.JPG") {
    val creationDate = "2030-01-02T12:00:00Z"

    fun key(role: ResourceRole) = uploadKey(assetId, role, filename)

    fun manifestEntry() = DeviceManifestAsset(
        assetId = assetId,
        creationDate = creationDate,
        resources = roles.map { ManifestResource(it, "image/jpeg", key(it), filename) },
    )
}
