package app.snapsync.world

import app.snapsync.gallery.DeviceManifest
import app.snapsync.gallery.DeviceManifestAsset
import app.snapsync.gallery.deviceManifestFromJson
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

/** The `201` body of `POST /events`. */
@Serializable
class CreatedEventDto(val eventId: String, val name: String, val createdAt: String)

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
 * Deno `backend/` edge is NOT a goal: drift is accepted, there is no golden fixture, and each `url` is a
 * synthetic in-memory handle (`world://<deviceId>/<filename>`) the fake download seams resolve
 * store-direct — never a real presigned S3 URL. [offline] flips the two GET read-models to failure so
 * the mini-edge answers `502`.
 */
class BackendStore {

    private val byteStore = mutableMapOf<String, MutableSet<String>>()
    private val manifests = mutableMapOf<Pair<String, String>, DeviceManifest>()
    private val events = mutableSetOf<String>()
    // Per-device config docs (`devices/<id>.json`, the push token) — a SEPARATE namespace from
    // the byte store, so a config never appears in [deviceListing] or the [union].
    private val deviceConfigs = mutableMapOf<String, String>()

    /** Failure lever: when true, the per-device listing and event-union routes fail (mini-edge `502`). */
    var offline: Boolean = false

    // ---- mutations ------------------------------------------------------------------------------

    /** Deposit one stored object into a device's byte partition (store-direct byte transfer). */
    fun deposit(deviceId: String, filename: String) {
        byteStore.getOrPut(deviceId) { linkedSetOf() }.add(filename)
    }

    /** Register an event marker (the `POST /events` effect / a direct injection). */
    fun registerEvent(eventId: String) {
        events.add(eventId)
    }

    /**
     * Wipe a device's stored byte objects (models an operator `reset-storage` deleting the
     * `devices/<id>/files/` partition). The per-device listing then returns empty while the extension
     * ledger still holds `COMPLETED` rows — the storage-reset condition.
     */
    fun wipeBytes(deviceId: String) {
        byteStore.remove(deviceId)
    }

    fun isRegistered(eventId: String): Boolean = eventId in events

    /** Deposit a device manifest from its JSON body (the `PUT /events/<id>/devices/<id>` effect). */
    fun putManifestJson(eventId: String, deviceId: String, json: String) {
        manifests[eventId to deviceId] = deviceManifestFromJson(json)
    }

    /** Inject a device manifest directly (used to set up foreign devices). */
    fun putManifest(eventId: String, deviceId: String, manifest: DeviceManifest) {
        manifests[eventId to deviceId] = manifest
    }

    /** Store a device's config doc (the `PUT /devices/<id>` effect — push-token registration). */
    fun putDeviceConfig(deviceId: String, json: String) {
        deviceConfigs[deviceId] = json
    }

    // ---- inspection (for tests) -----------------------------------------------------------------

    /** The raw object names stored for a device — inspectable world outcome. */
    fun objectsOf(deviceId: String): Set<String> = byteStore[deviceId].orEmpty().toSet()

    fun manifestOf(eventId: String, deviceId: String): DeviceManifest? = manifests[eventId to deviceId]

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
        fun syntheticUrl(deviceId: String, filename: String): String = "world://$deviceId/$filename"
    }
}

/** Build a foreign device's manifest asset for [BackendStore.putManifest]. */
fun foreignManifest(deviceId: String, assets: List<DeviceManifestAsset>): DeviceManifest =
    DeviceManifest(deviceId = deviceId, assets = assets)
