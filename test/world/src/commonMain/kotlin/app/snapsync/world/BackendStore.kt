package app.snapsync.world

import app.snapsync.model.DeviceManifest
import app.snapsync.model.DeviceManifestAsset
import app.snapsync.model.deviceManifestFromJson
import kotlinx.serialization.Serializable

/** One entry of the per-device file listing (`GET /files/devices/<id>`) — `{filename, size, url}`. */
@Serializable
class FileEntryDto(val filename: String, val size: Long, val url: String)

/** One resource of a union asset (`GET /events/<id>/files`). */
@Serializable
class UnionResourceDto(
    val role: String,
    val contentType: String,
    val key: String,
    val filename: String,
    val size: Long,
    val url: String,
)

/** One complete asset of the event-wide union, tagged with its owning device. */
@Serializable
class UnionAssetDto(
    val deviceId: String,
    val assetId: String,
    val creationDate: String,
    val resources: List<UnionResourceDto>,
)

/**
 * The `201` body of `POST /events` (and the `GET /events/<id>` read). [deletesAt] is the event's
 * retention deadline (capability `event-limits`) — DERIVED by the edge, never stored as a field, and
 * required on every `200`: the client refuses a details response missing it, because an invented
 * deadline would decide whether a membership is destroyed (capability `leave-event`).
 */
@Serializable
class CreatedEventDto(
    val eventId: String,
    val name: String,
    val createdAt: String,
    val startsAt: String,
    val endsAt: String,
    val deletesAt: String,
)

/**
 * The in-memory model of the edge's byte store + registry (capability `harness-world-model`) — the
 * single source of world truth. Three maps:
 *
 * - [byteStore]: `deviceId -> stored object names` (the `files/devices/<deviceId>/<filename>` byte partitions).
 * - [manifests]: `(eventId, deviceId) -> DeviceManifest` (the per-event device manifests, PUT via the
 *   mini-edge; also injected directly for foreign devices).
 * - [events]: the registered-event marker set (a `POST /events` registers; the union gate reads it).
 *
 * From these it computes the edge's read-models **faithfully in behavior** — [deviceListing], [union],
 * and (the same as the per-device listing) the reconcile-seed listing. Byte-level fidelity to the real
 * Deno `api/` edge is NOT a goal: drift is accepted, there is no golden fixture, and each `url` is a
 * synthetic presigned-link stand-in (`https://world.store/<deviceId>/<filename>`) the fake download transport resolves
 * store-direct — never a real presigned S3 URL. [offline] flips the two GET read-models to failure so
 * the mini-edge answers `502`.
 */
class BackendStore {

    private val byteStore = mutableMapOf<String, MutableSet<String>>()
    private val manifests = mutableMapOf<Pair<String, String>, DeviceManifest>()
    private val events = mutableSetOf<String>()
    // Per-event human name (from `POST /events` or a direct injection), served by `GET /events/<id>`.
    private val eventNames = mutableMapOf<String, String>()
    // Per-event start date (capability `event-creation`). Absent ⇒ a legacy marker, whose `startsAt` the
    // mini-edge synthesizes from `createdAt` on read.
    private val eventStarts = mutableMapOf<String, String>()
    // Per-event end date (capability `event-limits`). Absent ⇒ the mini-edge's GET synthesizes `+30d`.
    private val eventEnds = mutableMapOf<String, String>()
    // Per-device config docs (`devices/<id>.json`, the push token) — a SEPARATE namespace from
    // the byte store, so a config never appears in [deviceListing] or the [union].
    private val deviceConfigs = mutableMapOf<String, String>()
    // (eventId, deviceId) pairs that have LEFT (the real edge's `.left.json` departed state). A departed
    // device's manifest STAYS in [manifests] (its photos remain in the union) but it no longer counts as
    // an active member for the last-device reap — the behavioral model of last-write-wins membership.
    private val departed = mutableSetOf<Pair<String, String>>()

    /** Failure lever: when true, the per-device listing and event-union routes fail (mini-edge `502`). */
    var offline: Boolean = false

    // ---- mutations ------------------------------------------------------------------------------

    /** Deposit one stored object into a device's byte partition (store-direct byte transfer). */
    fun deposit(deviceId: String, filename: String) {
        byteStore.getOrPut(deviceId) { linkedSetOf() }.add(filename)
    }

    /**
     * Register an event marker (the `POST /events` effect / a direct injection), with an optional name and
     * an optional [startsAt] — the event's start date, which is both the default and the FLOOR for every
     * member's capture-date cutoff (capability `photo-selection-policy`).
     *
     * An event registered with **no** `startsAt` models a marker written before start dates existed; the
     * mini-edge's `GET` then synthesizes one from `createdAt`, exactly as the real backend does.
     */
    fun registerEvent(
        eventId: String,
        name: String? = null,
        startsAt: String? = null,
        endsAt: String? = null,
    ) {
        events.add(eventId)
        if (name != null) eventNames[eventId] = name
        if (startsAt != null) eventStarts[eventId] = startsAt
        if (endsAt != null) eventEnds[eventId] = endsAt
    }

    /** The event's human name for `GET /events/<id>` (null when unnamed). */
    fun nameOf(eventId: String): String? = eventNames[eventId]

    /** The event's start date for `GET /events/<id>` (null when registered without one — a legacy marker). */
    fun startsAtOf(eventId: String): String? = eventStarts[eventId]

    /** The event's end date for `GET /events/<id>` (null when registered without one — the mini-edge then
     *  synthesizes `startsAt + 30d`, exactly as the real backend's creator-supplied/fallback stamp). */
    fun endsAtOf(eventId: String): String? = eventEnds[eventId]

    /**
     * Wipe a device's stored byte objects (models an operator deleting the `devices/<id>/files/`
     * partition from the bunny zone — e.g. via the dashboard or native Storage API). The per-device
     * listing then returns empty while the extension ledger still holds `COMPLETED` rows — the
     * storage-reset condition.
     */
    fun wipeBytes(deviceId: String) {
        byteStore.remove(deviceId)
    }

    fun isRegistered(eventId: String): Boolean = eventId in events

    /**
     * Delete an event from the registry — the **nightly sweep's** effect (capability
     * `scheduled-cleanup`), which is the only thing that ever removes one. Every subsequent
     * `GET /events/<id>` then answers `404`, exactly as it would against the real backend after a sweep.
     *
     * A DELIBERATELY blunt lever: the sweep runs out-of-edge and the world models no scheduler, so what
     * matters here is the observable consequence for a device that was still joined.
     */
    fun sweepEvent(eventId: String) {
        events.remove(eventId)
        eventNames.remove(eventId)
        eventStarts.remove(eventId)
        eventEnds.remove(eventId)
    }

    /** Deposit a device manifest from its JSON body (the `PUT /events/<id>/devices/<id>` effect). */
    fun putManifestJson(eventId: String, deviceId: String, json: String) {
        manifests[eventId to deviceId] = deviceManifestFromJson(json)
        departed.remove(eventId to deviceId) // a fresh active manifest supersedes a prior leave (LWW)
    }

    /** Inject a device manifest directly (used to set up foreign devices). */
    fun putManifest(eventId: String, deviceId: String, manifest: DeviceManifest) {
        manifests[eventId to deviceId] = manifest
        departed.remove(eventId to deviceId) // a fresh active manifest supersedes a prior leave (LWW)
    }

    /** Store a device's config doc (the `PUT /devices/<id>` effect — push-token registration). */
    fun putDeviceConfig(deviceId: String, json: String) {
        deviceConfigs[deviceId] = json
    }

    /**
     * Leave an event (the `DELETE /events/<id>/devices/<id>` endpoint, capability `event-leave-endpoint`).
     * RENAME-ONLY: marks the device departed (its manifest STAYS, so the union keeps serving its photos)
     * and does nothing else — no last-member reap, no byte/config GC. The event survives (rejoinable)
     * until it expires and the nightly sweep (capability `scheduled-cleanup`) reclaims it. Idempotent; a
     * leave for an unregistered event is a no-op.
     */
    fun leave(eventId: String, deviceId: String) {
        if (eventId !in events) return
        departed.add(eventId to deviceId)
    }

    // ---- inspection (for tests) -----------------------------------------------------------------

    /** The raw object names stored for a device — inspectable world outcome. */
    fun objectsOf(deviceId: String): Set<String> = byteStore[deviceId].orEmpty().toSet()

    fun manifestOf(eventId: String, deviceId: String): DeviceManifest? = manifests[eventId to deviceId]

    /** Whether a device has LEFT this event (its manifest persists in the union) — inspectable outcome. */
    fun isDeparted(eventId: String, deviceId: String): Boolean = (eventId to deviceId) in departed

    /** The stored config doc for a device (the registered push token), or null — inspectable outcome. */
    fun deviceConfigOf(deviceId: String): String? = deviceConfigs[deviceId]

    // ---- read-models ----------------------------------------------------------------------------

    /**
     * The per-device file listing (`GET /files/devices/<id>`) — one entry per stored object. Serves
     * BOTH the rejoin reconcile seed (`HttpDeviceFilesSource`) and own-device status completeness
     * (the ledger-backed status source); the world computes it once.
     */
    fun deviceListing(deviceId: String): List<FileEntryDto> =
        byteStore[deviceId].orEmpty().map { filename ->
            FileEntryDto(filename = filename, size = 1L, url = syntheticUrl(deviceId, filename))
        }

    /**
     * The event-wide union (`GET /events/<id>/files`): every contributing device's **complete** assets
     * (every manifest resource `key` present in that device's byte partition), each tagged with its
     * `deviceId`. Returns `null` when the event is unregistered (the marker gate — a 404-equivalent,
     * distinct from a registered-but-empty event which returns an empty list).
     */
    fun union(eventId: String): List<UnionAssetDto>? {
        if (eventId !in events) return null
        val out = mutableListOf<UnionAssetDto>()
        for ((keyPair, manifest) in manifests) {
            val (mEventId, deviceId) = keyPair
            if (mEventId != eventId) continue
            val present = byteStore[deviceId].orEmpty()
            for (asset in manifest.assets) {
                if (asset.resources.isEmpty()) continue
                if (!asset.resources.all { it.key in present }) continue // complete-only
                out.add(
                    UnionAssetDto(
                        deviceId = deviceId,
                        assetId = asset.assetId,
                        creationDate = asset.creationDate,
                        resources = asset.resources.map { r ->
                            UnionResourceDto(
                                role = r.role.wire,
                                contentType = r.contentType,
                                key = r.key,
                                filename = r.filename,
                                size = 1L,
                                url = syntheticUrl(deviceId, r.key),
                            )
                        },
                    ),
                )
            }
        }
        return out
    }

    companion object {
        /** The synthetic in-memory download handle — resolved store-direct by the fake download seams. */
        /**
         * A synthetic stand-in for the backend's presigned S3 link. **It must be `https`**: the real
         * `QueuedPhotoDownloadJobs` guards every url with `isFetchableUrl` and skips anything that is not
         * `http`/`https` — handing a background `URLSession` a hostless or non-HTTP url raises an
         * uncatchable Obj-C exception, so the guard is load-bearing, not cosmetic.
         *
         * This was `world://…` while the world faked `PhotoDownloadJobs` and no guard ever ran, so the
         * harness proved downloads worked over a scheme production would have refused. Composing the real
         * jobs surfaced it immediately (capability `harness-world-model`).
         */
        fun syntheticUrl(deviceId: String, filename: String): String =
            "https://world.store/$deviceId/$filename"
    }
}

/** Build a foreign device's manifest asset for [BackendStore.putManifest]. */
fun foreignManifest(deviceId: String, assets: List<DeviceManifestAsset>): DeviceManifest =
    DeviceManifest(deviceId = deviceId, assets = assets)
