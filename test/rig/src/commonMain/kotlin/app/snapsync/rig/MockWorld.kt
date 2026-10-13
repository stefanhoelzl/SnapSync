package app.snapsync.rig

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.device.DeviceId
import app.snapsync.mock.DeclaredVersion
import app.snapsync.mock.MockDevice
import app.snapsync.mock.MockedSystem
import app.snapsync.mock.UploadNetwork
import app.snapsync.model.DeviceManifest
import app.snapsync.model.HttpRequest
import app.snapsync.model.HttpResult
import app.snapsync.model.InviteLinkHints
import app.snapsync.model.ManifestResource
import app.snapsync.model.ResourceRole
import app.snapsync.model.UnionAsset
import app.snapsync.model.UnionResource
import app.snapsync.model.UnionTrigger
import app.snapsync.model.uploadKey
import app.snapsync.ports.HttpTransport
import app.snapsync.wire.api.CreateEventReply
import app.snapsync.wire.api.DeviceFilesReply
import app.snapsync.wire.api.EventFilesReply
import app.snapsync.wire.api.EventNotFound
import app.snapsync.wire.api.GatedRequest
import app.snapsync.wire.api.GetEventReply
import app.snapsync.wire.api.JoinEventReply
import app.snapsync.wire.api.LeaveEventReply
import app.snapsync.wire.api.Limits
import app.snapsync.wire.api.PublishManifestReply
import app.snapsync.wire.api.Reply
import app.snapsync.wire.api.ReportReceivedReply
import app.snapsync.wire.api.Routes
import app.snapsync.wire.models.EventCreate
import app.snapsync.wire.models.Manifest
import app.snapsync.wire.models.ManifestAsset
import app.snapsync.wire.models.Received
import app.snapsync.wire.models.Role
import app.snapsync.wire.runtime.WireResponse

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
 * The backend as another member, or its operator's seeding, reaches it: its public surface only — the device API over
 * [transport], credential free (the dev server's fallback bearer serves it, as the mock's does), and the byte route an
 * OS upload crosses. Written once for every backend a host may serve.
 */
class BackendReach(
    /** The device-facing base, carrying exactly one version prefix. */
    private val base: String,
    /** A short name for errors — `mock` or `deno`. */
    private val name: String,
    private val transport: HttpTransport,
    private val network: UploadNetwork,
    private val declared: DeclaredVersion,
) {
    /** The object keys the backend lists for [deviceId] in [eventId] — each event holds its own. */
    suspend fun objectsOf(eventId: String, deviceId: String): Set<String> {
        val reply = send(Routes.DeviceFiles.request(eventId, deviceId))
        val listed = reply as? DeviceFilesReply.Listed ?: refused("the listing", reply)
        return listed.items.mapTo(mutableSetOf()) { uploadKey(AssetId(it.assetId), it.role.inApp(), it.filename) }
    }

    /**
     * The union the backend serves for [eventId] — read with no token, so the backend logs it as an anonymous full
     * read (decision record `changes/incremental-union`, D5), like a browser's.
     */
    suspend fun unionOf(eventId: String): List<UnionAsset> {
        val reply = send(Routes.EventFiles.request(eventId, snapSyncTrigger = UnionTrigger.FOREGROUND.wire))
        val page = reply as? EventFilesReply.Page ?: refused("the union", reply)
        return page.items.map { asset ->
            UnionAsset(
                deviceId = requireNotNull(DeviceId.parse(asset.deviceId)) { "not a device id: '${asset.deviceId}'" },
                assetId = AssetId(asset.assetId),
                creationDate = asset.creationDate,
                resources = asset.resources.map {
                    UnionResource(it.key, it.url.orEmpty(), it.role.value, it.contentType, it.filename)
                },
            )
        }
    }

    /** The event's name, or `null` for an event the backend does not hold (a `404`). */
    suspend fun eventOf(
        eventId: String,
    ): Pair<Boolean, String?> = when (val reply = send(Routes.GetEvent.request(eventId))) {
        is GetEventReply.Found -> true to reply.eventDetails.name
        EventNotFound -> false to null
        else -> error("the event details route answered $reply for $eventId")
    }

    /**
     * The `closedAt` the event read serves — what a build that predates open-until dates acts on — or `null` while it
     * serves none, and for an event the backend does not hold.
     */
    suspend fun closedAtOf(eventId: String): String? =
        (send(Routes.GetEvent.request(eventId)) as? GetEventReply.Found)?.eventDetails?.closedAt

    /** `POST /events` — an event the backend mints. */
    suspend fun createEvent(name: String, startsAt: String): String {
        val reply = send(Routes.CreateEvent.request(EventCreate(name = name, startsAt = startsAt)))
        return (reply as? CreateEventReply.Created ?: refused("create event", reply)).event.eventId
    }

    suspend fun join(eventId: String, deviceId: String) {
        val reply = send(Routes.JoinEvent.request(eventId, deviceId))
        if (reply != JoinEventReply.Joined) refused("join $deviceId to $eventId", reply)
    }

    suspend fun leave(eventId: String, deviceId: String) {
        val reply = send(Routes.LeaveEvent.request(eventId, deviceId))
        if (reply != LeaveEventReply.Left) refused("leave $deviceId from $eventId", reply)
    }

    /**
     * [deviceId] reports it holds every photo of the others up to the position the union of [eventId] stands at now —
     * what a current build says once its downloads are all in its library.
     */
    suspend fun reportReceived(eventId: String, deviceId: String) {
        val page = send(Routes.EventFiles.request(eventId, snapSyncTrigger = UnionTrigger.FOREGROUND.wire))
        val position = (page as? EventFilesReply.Page ?: refused("the union", page)).snapSyncCursor.toLong()
        val reply = send(Routes.ReportReceived.request(eventId, deviceId, Received(position)))
        if (reply != ReportReceivedReply.Recorded) refused("$deviceId's received report", reply)
    }

    suspend fun publish(eventId: String, manifest: DeviceManifest) {
        val reply = send(Routes.PublishManifest.request(eventId, manifest.deviceId.value, manifest.onWire()))
        if (reply != PublishManifestReply.Published) refused("publish ${manifest.deviceId}'s manifest", reply)
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
        val role = resource.role.wire
        val path = eventId?.let { Routes.UploadResource.path(it, deviceId, assetId.value, role, resource.filename) }
            ?: Routes.UploadResourceEventless.path(deviceId, assetId.value, role, resource.filename)
        val status = network.put(base + path, versionHeaders() + (CONTENT_TYPE to JPEG), bytes)
        check(status != null && status in SUCCESS) {
            "upload ${resource.key} for $deviceId was answered $status by the $name backend"
        }
    }

    /** [request], sent as the gateway would send it without a credential: the declared version, no token. */
    private suspend fun <R : Reply> send(request: GatedRequest<R>): R {
        val wire = request.wire
        val sent = HttpRequest(wire.method, base + wire.path, wire.headers + versionHeaders(), wire.body)
        return when (val result = transport.send(sent)) {
            is HttpResult.Response -> request.decide(WireResponse(result.status, result.headers, result.body))
            is HttpResult.Unreachable -> request.unreachable(result.reason)
        }
    }

    private fun versionHeaders(): Map<String, String> =
        declared.value?.let { mapOf(Limits.VERSION_HEADER to it) }.orEmpty()

    private fun refused(step: String, reply: Reply): Nothing = error(
        "step '$step' was refused by the $name backend: $reply",
    )

    private companion object {
        const val CONTENT_TYPE = "Content-Type"
        const val JPEG = "image/jpeg"
        val SUCCESS = 200..299
        val SEEDED_BYTES = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
    }
}

private fun Role.inApp(): ResourceRole = ResourceRole.entries.first { it.wire == value }

/** A seeded member's snapshot as the backend takes it; an unstamped one is version 0, the oldest. */
private fun DeviceManifest.onWire(): Manifest = Manifest(
    deviceId = deviceId.value,
    assets = assets.map { asset ->
        ManifestAsset(
            assetId = asset.assetId.value,
            creationDate = asset.creationDate,
            resources = asset.resources.map {
                app.snapsync.wire.models.ManifestResource(
                    Role.fromValue(it.role.wire) ?: error("no described role for ${it.role}"),
                    it.contentType,
                    it.key,
                    it.filename,
                )
            },
        )
    },
    version = version ?: 0L,
    `final` = final,
)
