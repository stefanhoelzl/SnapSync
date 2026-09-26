package app.snapsync.world

import app.snapsync.model.APP_VERSION_HEADER
import app.snapsync.model.AssetId
import app.snapsync.model.CreateEventRequest
import app.snapsync.model.DeviceManifest
import app.snapsync.model.ManifestResource
import app.snapsync.model.Reply
import app.snapsync.model.uploadKey
import app.snapsync.model.UnionAsset
import app.snapsync.ports.Backend
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess

/**
 * The world's **backend-neutral** reads, levers and seeding (`docs/testing.md`, "Neutral inspection
 * and minted event ids beside the mini-edge-only surface") — `World.neutral`.
 *
 * Everything the backend's public surface can carry goes over the world's [port] — the production `HttpBackend`,
 * credential-free, as the backend's other members would call it — so it is written ONCE for both backends. Bytes
 * are the OS transfer's route, not the port's, so their seeding goes over the bare [client]. What only the mini-edge's in-memory store can
 * answer is an explicit [Answer.Unavailable] on any other backend, never an empty value or a silent no-op.
 *
 * Its own class rather than more members on `World`, which is at its complexity ceiling (`docs/architecture.md`):
 * this is one concern — the backend as a test sees it — and it reads as one.
 */
class NeutralBackend internal constructor(
    private val backend: WorldBackend,
    /** The world's bare backend client, for the one route the port does not carry: bytes. */
    private val client: HttpClient,
    private val host: String,
    /** The world's backend port, declaring the world's app version. */
    private val port: Backend,
    /** The version the world's requests declare — the byte seeding declares it too, as the port does. */
    private val appVersion: () -> String,
) {

    // ---- reads ----------------------------------------------------------------------------------

    /** The object keys the backend lists for [deviceId] — its per-device listing, over HTTP. */
    suspend fun objectsOf(deviceId: String): Answer<Set<String>> =
        Answer.Available(read("the listing", port.deviceFiles(null, deviceId)).map { uploadKey(it.assetId, it.role, it.filename) }.toSet())

    /** The event-wide union the backend serves for [eventId], over HTTP. */
    suspend fun unionOf(eventId: String): Answer<List<UnionAsset>> =
        Answer.Available(read("the union", port.eventFiles(eventId)))

    /** Whether the backend knows [eventId] — its details route answering `200` rather than `404`. */
    suspend fun isRegistered(eventId: String): Answer<Boolean> = when (val reply = port.getEvent(eventId)) {
        is Reply.Ok -> Answer.Available(true)
        is Reply.Refused -> if (reply.status == NOT_FOUND) Answer.Available(false) else error("the event details route answered $reply for $eventId")
        else -> error("the event details route answered $reply for $eventId")
    }

    /** The manifest the backend holds for [deviceId] in [eventId]. */
    fun manifestOf(eventId: String, deviceId: String): Answer<DeviceManifest?> =
        onMiniEdge("the manifest read", "the real edge serves no route that reads a manifest back") {
            it.manifestOf(eventId, deviceId)
        }

    /** How many manifest publishes the backend STORED for this membership. */
    fun publishesOf(eventId: String, deviceId: String): Answer<Int> =
        onMiniEdge("the publish counter", NOT_ON_THE_HTTP_SURFACE) { it.publishesOf(eventId, deviceId) }

    /** How many manifest publishes the backend REFUSED as older than the one it holds. */
    fun refusedPublishesOf(eventId: String, deviceId: String): Answer<Int> =
        onMiniEdge("the refused-publish counter", NOT_ON_THE_HTTP_SURFACE) { it.refusedPublishesOf(eventId, deviceId) }

    /** The manifest version the backend stores for this membership. */
    fun manifestVersionOf(eventId: String, deviceId: String): Answer<Long?> =
        onMiniEdge("the stored manifest version", NOT_ON_THE_HTTP_SURFACE) { it.manifestVersionOf(eventId, deviceId) }

    /** Whether [deviceId] has LEFT [eventId] — its membership marked departed. */
    fun isDeparted(eventId: String, deviceId: String): Answer<Boolean> =
        onMiniEdge("the departed read", NOT_ON_THE_HTTP_SURFACE) { it.isDeparted(eventId, deviceId) }

    /** The config document [deviceId] registered (its push token), or null — `PUT /devices/<id>`'s effect. */
    fun deviceConfigOf(deviceId: String): Answer<String?> =
        onMiniEdge("the device-config read", NOT_ON_THE_HTTP_SURFACE) { it.deviceConfigOf(deviceId) }

    /** How many registrations (`PUT /devices/<id>`) the backend stored for [deviceId]. */
    fun deviceConfigWritesOf(deviceId: String): Answer<Int> =
        onMiniEdge("the device-config write counter", NOT_ON_THE_HTTP_SURFACE) { it.deviceConfigWritesOf(deviceId) }

    /** The name the backend serves for [eventId] — its details route, over HTTP; null when it has none. */
    suspend fun eventNameOf(eventId: String): Answer<String?> = when (val reply = port.getEvent(eventId)) {
        is Reply.Ok -> Answer.Available(reply.value.name)
        is Reply.Refused -> if (reply.status == NOT_FOUND) Answer.Available(null) else error("the event details route answered $reply for $eventId")
        else -> error("the event details route answered $reply for $eventId")
    }

    /** Every push the backend would have sent, in order — the APNs mock's record. */
    fun pushesSent(): Answer<List<BackendStore.SentPush>> =
        onMiniEdge("the pushes read", "the local real edge sends no push and keeps no record of one") {
            it.pushesSent()
        }

    // ---- levers ---------------------------------------------------------------------------------

    /** Only the per-device file listing fails (`502`); every other route serves. */
    fun setDeviceListingFails(fails: Boolean): Answer<Unit> =
        onMiniEdge("the listing-failure lever", "nothing makes the real edge fail one route") {
            it.failDeviceListing = fails
        }

    /** The next token-bearing request to a gated route is answered `401`, once. */
    fun refuseNextCredential(): Answer<Unit> =
        onMiniEdge("the credential-refusal lever", "the local real edge authenticates with a fallback credential") {
            it.refuseNextCredential = true
        }

    /** Hold every leave until [releaseLeave]: the backend that has not answered yet. */
    fun holdLeave(): Answer<Unit> =
        onMiniEdge("the leave hold", "nothing holds a real request open") {
            it.leaveHold = kotlinx.coroutines.CompletableDeferred()
        }

    /** Let a held leave (and every later one) be answered. */
    fun releaseLeave(): Answer<Unit> =
        onMiniEdge("the leave release", "nothing holds a real request open") {
            it.leaveHold?.complete(Unit)
            it.leaveHold = null
        }

    /**
     * An event registered before start dates existed — no `startsAt`, so the backend synthesizes one from its
     * creation time, with the millisecond precision a legacy marker carries. Returns the id, minted as the backend
     * mints one.
     */
    fun registerLegacyEvent(name: String): Answer<String> =
        onMiniEdge("the legacy-event lever", "the real edge's create requires a start date") {
            val eventId = legacyEventId()
            it.registerEvent(eventId, name)
            eventId
        }

    /** Backend-offline — the per-device listing and event-union routes answer `502` (`docs/testing.md`). */
    fun setOffline(offline: Boolean): Answer<Unit> =
        onMiniEdge("backend-offline", "nothing makes the real edge answer 502") { it.offline = offline }

    /** The minimum app version the backend demands (capability `app-update-required`); `null` turns the gate off. */
    fun setMinAppVersion(minimum: String?): Answer<Unit> =
        onMiniEdge("the minimum-app-version lever", "the real edge fixes its minimum version when it starts") {
            it.minAppVersion = minimum
        }

    /** Devices an event admits before its join answers `409` (capability `event-lifetime`). */
    fun setCapacity(capacity: Int): Answer<Unit> =
        onMiniEdge("the capacity lever", "the real edge fixes its capacity when it starts") { it.capacity = capacity }

    /** The nightly sweep deleting [eventId] (capability `event-lifetime`). */
    fun sweepEvent(eventId: String): Answer<Unit> =
        onMiniEdge("the event sweep", NOT_RUNTIME_DRIVABLE) { it.sweepEvent(eventId) }

    /** A storage reset wiping [deviceId]'s bytes. */
    fun wipeBytes(deviceId: String): Answer<Unit> =
        onMiniEdge("the byte wipe", NOT_RUNTIME_DRIVABLE) { it.wipeBytes(deviceId) }

    /** The backend collecting one of [deviceId]'s objects. */
    fun collectBytes(deviceId: String, filename: String): Answer<Unit> =
        onMiniEdge("the byte collection", NOT_RUNTIME_DRIVABLE) { it.collectBytes(deviceId, filename) }

    // ---- seeding through the public surface (the world's minted-id helpers stand on these) -------------

    /** `POST /events` — the event id the backend mints, the only kind the real backend accepts. */
    internal suspend fun createEvent(name: String, startsAt: String, endsAt: String?): String =
        read("create event", port.createEvent(null, CreateEventRequest(name, startsAt, endsAt))).eventId

    /** The join, with no body. */
    internal suspend fun join(eventId: String, deviceId: String) {
        read("join $deviceId to $eventId", port.joinEvent(null, eventId, deviceId))
    }

    /** One resource's bytes, where the app's uploader addresses them. */
    internal suspend fun upload(deviceId: String, assetId: AssetId, resource: ManifestResource) {
        checked(
            "upload ${resource.key} for $deviceId",
            client.put("$host/files/devices/$deviceId/$assetId/${resource.role.wire}?filename=${resource.filename}") {
                header(APP_VERSION_HEADER, appVersion())
                contentType(ContentType.Image.JPEG)
                setBody(SEEDED_BYTES)
            },
        )
    }

    /** A member's manifest publish. */
    internal suspend fun publish(eventId: String, manifest: DeviceManifest) {
        read("publish ${manifest.deviceId}'s manifest", port.publishManifest(null, eventId, manifest.deviceId, manifest))
    }

    /** A served answer's value, or a failure naming the setup step and what the backend answered. */
    private fun <T> read(step: String, reply: Reply<T>): T =
        (reply as? Reply.Ok)?.value ?: error("world setup step '$step' was refused by the ${backend.name} backend: $reply")

    private suspend fun checked(step: String, response: HttpResponse): HttpResponse {
        check(response.status.isSuccess()) {
            "world setup step '$step' was refused by the ${backend.name} backend: HTTP ${response.status.value} " +
                response.bodyAsText()
        }
        return response
    }

    /** A UUID for a legacy marker, fresh per call, in a range the mini-edge's own minting never reaches. */
    private fun legacyEventId(): String {
        legacyCounter += 1
        return "00000000-0000-4000-9000-" + legacyCounter.toString().padStart(LEGACY_ID_DIGITS, '0')
    }
    private var legacyCounter = 0L

    private inline fun <T> onMiniEdge(operation: String, why: String, read: (BackendStore) -> T): Answer<T> =
        (backend as? MiniEdgeBackend)?.let { Answer.Available(read(it.store)) }
            ?: Answer.unavailable(backend, operation, why)

    private companion object {
        const val NOT_FOUND = 404
        const val NOT_ON_THE_HTTP_SURFACE = "the real edge keeps it in its database and serves no route that reads it"
        const val LEGACY_ID_DIGITS = 12
        const val NOT_RUNTIME_DRIVABLE = "the real edge runs it on its own schedule, and no route drives it at runtime"

        /** A minimal JPEG, as the backend contracts' setup seeds one — the bytes are never read back. */
        val SEEDED_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
    }
}
