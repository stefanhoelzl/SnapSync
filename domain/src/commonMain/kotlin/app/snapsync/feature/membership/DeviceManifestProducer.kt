package app.snapsync.feature.membership

import app.snapsync.model.LedgerEntry
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.encodeToJson
import app.snapsync.model.projectDeviceManifest
import app.snapsync.ports.DeviceManifestStore
import app.snapsync.ports.Enrollment

/**
 * Writes the per-event device manifest each cycle (capability `device-manifest`). The **sole** writer of
 * the manifest; it PUTs **synchronously in-cycle** (no background `URLSession`).
 *
 * The manifest is a **projection of the upload ledger's COMPLETED rows** (capability `sync-ledger`),
 * admitted by the membership's one policy (capability `photo-selection-policy`). It used to be projected
 * from a device-global accumulator this class also maintained — a second durable structure tracking the
 * same deletion-aware asset set with different columns, and pruning it on the same signals. The ledger
 * already had to be right about all of that (a wrong row re-uploads a whole library, or hides a photo
 * forever), so the accumulator was duplication that could only ever disagree.
 *
 * **What the change costs, stated plainly:** the manifest now lists **completed** resources rather than
 * **discovered** ones. A just-taken photo appears in the union when its bytes land instead of when it is
 * noticed — which is the honest timing, because the union's consumers can only fetch bytes that exist.
 * The event union's byte-presence cross-check (capability `bunny-list-endpoint`) therefore stops being
 * the mechanism that hides not-yet-uploaded assets and becomes defense-in-depth against a
 * COMPLETED-but-absent byte.
 *
 * Deletion-awareness comes from the ledger's **absence mark**: an asset the change feed reports removed
 * has its rows marked (never deleted — their bytes are still on the backend, and the rows are what stop a
 * restored asset re-uploading), and the projection excludes marked rows, so they leave it with no second
 * structure to keep in step. There is no full-enumeration retain-live backstop: it was fed the
 * policy-admitted set, so a raised capture cutoff discarded rows for photos still present and still
 * uploaded (capability `sync-ledger`).
 *
 * A kill mid-PUT loses nothing durable (the snapshot recomputes next cycle); the manifest is write-only
 * in v1 so transient staleness is benign and self-heals.
 */
class DeviceManifestProducer(
    private val store: DeviceManifestStore,
    private val uploader: Enrollment,
    private val deviceId: String,
) {
    /**
     * Project and PUT. [rows] are the ledger's COMPLETED rows carrying manifest detail; [policy] is the
     * membership's admission, applied here exactly as every other consumer applies it.
     */
    suspend fun produce(eventId: String, policy: SelectionPolicy, rows: List<LedgerEntry>) {
        val json = projectDeviceManifest(deviceId, rows, policy).encodeToJson()
        // Skip-if-unchanged, keyed by EVENT. The projected JSON is event-independent (`{deviceId,
        // assets}`), so without the event id in the marker a **switch** to a new event would compare
        // equal to the prior event's upload and skip writing the new event's (still-absent) device.json.
        val marker = "$eventId $json"
        if (marker == store.loadLastUploaded()) return
        if (uploader.put(eventId, deviceId, json)) store.saveLastUploaded(marker)
    }
}
