package app.snapsync.rig

import app.snapsync.mock.DeclaredVersion
import app.snapsync.mock.MockDevice
import app.snapsync.mock.UploadNetwork
import app.snapsync.mock.MockedSystem
import app.snapsync.model.UnionTrigger
import app.snapsync.model.APP_VERSION_HEADER
import app.snapsync.model.AssetId
import app.snapsync.model.CreateEventRequest
import app.snapsync.model.DeviceManifest
import app.snapsync.model.InviteLinkHints
import app.snapsync.model.ManifestResource
import app.snapsync.model.Reply
import app.snapsync.model.UnionAsset
import app.snapsync.model.uploadKey
import app.snapsync.ports.Backend

/**
 * **What the control channel's operator levers act on** (`docs/testing.md`, "One control protocol, served by two
 * hosts"): a [device] of mocks, of which [mocked] names the systems this host's app actually runs over. A lever is
 * honoured only when every system it moves is one of those; any other answers `409` naming the real system — never a
 * silent success on an idle mock the app is not listening to.
 *
 * The JVM host runs over every mock (the backend aside, when it serves the real `api/`); the app host over the systems
 * its adapter choice mocks (`docs/testing.md`, "Launch-time adapters") — none, for an ordinary rig build.
 */
class MockWorld(
    val device: MockDevice,
    val mocked: Set<MockedSystem>,
    /**
     * The backend's public surface, as another member or the operator's seeding reaches it — or `null` where the
     * channel must not reach it: a device's REAL backend is the shared `snap-sync-dev` zone, where real users' photos
     * live.
     */
    val reach: BackendReach?,
    /** Why the channel does not reach the backend, where [reach] is `null`. */
    val reachRefusal: String,
    /** Why the backend mock's operator is not there, where [MockedSystem.BACKEND] is not mocked. */
    val operatorRefusal: (lever: String) -> String,
    /** The marketing version the app declares to the backend — a cell an operator may change — or `null`. */
    val version: DeclaredVersion?,
    /** The operating system's side of the handlers the channel hands the app, where it plays the OS. */
    val os: PlayedOs,
    /** This device's id, as the app's Keychain holds it. */
    val ownDeviceId: () -> String,
    /** The event the device is joined to, as the screen or the membership file says — or `null`. */
    val joinedEventId: () -> String?,
    /** Set how this build answers an invite link's dev/test hints. */
    val setInviteLinkHints: (InviteLinkHints) -> Unit,
    /** Whether an event this device creates is encrypted — the build's own control, so honoured on every host. */
    val setEncryptsNewEvents: (Boolean) -> Unit,
) {
    fun isMocked(system: MockedSystem): Boolean = system in mocked

    /** Why a lever that moves [system] is refused here, or `null` when it is honoured. */
    fun refusalFor(system: MockedSystem): String? = if (isMocked(system)) {
        null
    } else {
        "${system.what} is REAL on this host, so there is no mock of it for this lever to move — mock `${system.key}` " +
            "(POST /device/adapters on the app host) and relaunch"
    }
}

/**
 * The backend as another member, or its operator's seeding, reaches it: its public surface only — the port, credential
 * free, and the byte route an OS upload crosses. Written once for every backend a host may serve.
 */
class BackendReach(
    /** The device-facing base, carrying exactly one version prefix. */
    private val base: String,
    /** A short name for errors — `mock` or `deno`. */
    private val name: String,
    private val port: Backend,
    private val network: UploadNetwork,
    private val declared: DeclaredVersion,
) {
    /** The object keys the backend lists for [deviceId] in [eventId] — each event holds its own. */
    suspend fun objectsOf(eventId: String, deviceId: String): Set<String> =
        read("the listing", port.deviceFiles(null, eventId, deviceId)).mapTo(mutableSetOf()) { uploadKey(it.assetId, it.role, it.filename) }

    /**
     * The union the backend serves for [eventId] — read through the port with no token, so the backend logs it as an
     * anonymous full read (decision record `changes/incremental-union`, D5), like a browser's.
     */
    suspend fun unionOf(eventId: String): List<UnionAsset> =
        read("the union", port.eventFiles(null, eventId, null, UnionTrigger.FOREGROUND)).assets

    /** The event's name, or `null` for an event the backend does not hold (a `404`). */
    suspend fun eventOf(eventId: String): Pair<Boolean, String?> = when (val reply = port.getEvent(eventId)) {
        is Reply.Ok -> true to reply.value.name
        is Reply.Refused -> if (reply.status == NOT_FOUND) false to null else error("the event details route answered $reply")
        else -> error("the event details route answered $reply for $eventId")
    }

    /** `POST /events` — an event the backend mints. */
    suspend fun createEvent(name: String, startsAt: String): String =
        read("create event", port.createEvent(null, CreateEventRequest(name, startsAt, null))).eventId

    suspend fun join(eventId: String, deviceId: String) {
        read("join $deviceId to $eventId", port.joinEvent(null, eventId, deviceId))
    }

    suspend fun publish(eventId: String, manifest: DeviceManifest) {
        read("publish ${manifest.deviceId}'s manifest", port.publishManifest(null, eventId, manifest.deviceId, manifest))
    }

    /**
     * One resource's bytes — [bytes], or a minimal placeholder — where the app's uploader addresses them: into [eventId],
     * or with none named through the event-less route an earlier build's job still carries, which the backend files
     * under the device's present membership.
     */
    suspend fun upload(
        deviceId: String,
        assetId: AssetId,
        resource: ManifestResource,
        bytes: ByteArray = SEEDED_BYTES,
        eventId: String? = null,
    ) {
        val scope = eventId?.let { "/events/$it" }.orEmpty()
        val url = "$base$scope/files/devices/$deviceId/$assetId/${resource.role.wire}?filename=${resource.filename}"
        val status = network.put(url, mapOf(APP_VERSION_HEADER to declared.value.orEmpty(), CONTENT_TYPE to JPEG), bytes)
        check(status != null && status in SUCCESS) { "upload ${resource.key} for $deviceId was answered $status by the $name backend" }
    }

    private fun <T> read(step: String, reply: Reply<T>): T =
        (reply as? Reply.Ok)?.value ?: error("step '$step' was refused by the $name backend: $reply")

    private companion object {
        const val NOT_FOUND = 404
        const val CONTENT_TYPE = "Content-Type"
        const val JPEG = "image/jpeg"
        val SUCCESS = 200..299
        val SEEDED_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
    }
}
