package app.snapsync.gallery

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One resource entry inside a [DeviceManifestAsset] (capability `device-manifest`): a generic [role],
 * the resource's MIME [contentType], its [key] (the storage object name `<assetId>-<role>.<ext>` — its
 * `files/<deviceId>/` storage key minus that prefix, byte-identical to what the producer uploads under,
 * see [uploadKey]; the fetch handle), and the human [filename] as captured. These field names are shared
 * verbatim with the event-wide union read, so the union is a straight projection of the manifest.
 */
@Serializable
class ManifestResource(
    val role: ResourceRole,
    val contentType: String,
    val key: String,
    val filename: String,
)

/**
 * One asset entry inside a [DeviceManifest] (capability `device-manifest`): the device-local
 * [assetId], its [creationDate] (ISO-8601 capture timestamp), and a non-empty [resources] set of
 * generic, originals-only [ManifestResource]s. Carries no `version` (the document is mutable and
 * rewritten each cycle, not a write-once contract).
 */
@Serializable
class DeviceManifestAsset(
    val assetId: String,
    val creationDate: String,
    val resources: List<ManifestResource>,
)

/**
 * The per-event device manifest (capability `device-manifest`): one object per (event, device) at
 * `/events/<eventId>/device/<deviceId>.json`, carrying the stable [deviceId] and the device's
 * [assets] for that event. It **replaces** the per-asset manifest objects — one document instead of N
 * — and is a **mutable, full-state snapshot** rewritten each cycle (no read-modify-write, last-write
 * wins, self-healing). It is write-only in v1: nothing in-app reads it (status comes from the gallery
 * enumeration seam × the per-device file listing); it exists as forward-prep for restore / the
 * event-wide union.
 */
@Serializable
class DeviceManifest(
    val deviceId: String,
    val assets: List<DeviceManifestAsset>,
)

/** Strict JSON for the device manifest — declared fields only. */
private val deviceManifestJson = Json { encodeDefaults = true }

/** Serialize to the `device.json` body (UTF-8 string). */
fun DeviceManifest.encodeToJson(): String =
    deviceManifestJson.encodeToString(DeviceManifest.serializer(), this)

/** Parse a device manifest from its JSON text; throws on malformed JSON or schema mismatch. */
fun deviceManifestFromJson(text: String): DeviceManifest =
    deviceManifestJson.decodeFromString(DeviceManifest.serializer(), text)

/**
 * Project a device-global [accumulator] (every discovered, not-deleted asset) into a single event's
 * [DeviceManifest]: keep only assets whose [DeviceManifestAsset.creationDate] is at or after
 * [startDate] (an ISO-8601 string compared lexicographically — valid for the same `…Z` formatter the
 * synthesis uses), or **all** of them when [startDate] is `null` (the whole-library scope — the
 * projection is then the identity). Entries are sorted by `assetId` so the serialized snapshot is
 * deterministic, making the producer's skip-if-unchanged comparison stable.
 */
fun projectDeviceManifest(
    deviceId: String,
    accumulator: Collection<DeviceManifestAsset>,
    startDate: String?,
): DeviceManifest {
    val assets = accumulator
        .filter { startDate == null || it.creationDate >= startDate }
        .sortedBy { it.assetId }
    return DeviceManifest(deviceId = deviceId, assets = assets)
}
