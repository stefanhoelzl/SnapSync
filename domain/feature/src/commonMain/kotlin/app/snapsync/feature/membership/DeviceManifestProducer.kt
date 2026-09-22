package app.snapsync.feature.membership

import app.snapsync.model.LedgerEntry
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.encodeToJson
import app.snapsync.model.projectDeviceManifest
import app.snapsync.model.withVersion
import app.snapsync.ports.DeviceManifestStore
import app.snapsync.ports.ManifestPublisher

/**
 * Writes the per-event device manifest each cycle (capability `device-manifest`). The **sole** writer of
 * the manifest; it PUTs **synchronously in-cycle** (no background `URLSession`).
 *
 * The manifest is a **projection of the upload ledger** (capability `sync-ledger`), admitted by the
 * membership's one policy (capability `photo-selection-policy`). It used to be projected from a
 * device-global accumulator this class also maintained — a second durable structure tracking the same
 * deletion-aware asset set with different columns, and pruning it on the same signals. The ledger already
 * had to be right about all of that (a wrong row re-uploads a whole library, or hides a photo forever),
 * so the accumulator was duplication that could only ever disagree.
 *
 * **What it declares, stated plainly:** every resource this device INTENDS to provide, whatever its
 * upload state — not the ones whose bytes have landed. A just-taken photo is listed when it is noticed,
 * and the backend keeps it out of the event union until every role it declares has arrived. That is the
 * honest division of labour: the device says what it will contribute, the backend says what is fetchable.
 * Listing only completed resources instead made a half-uploaded Live Photo readable as a complete
 * one-resource asset, which recipients imported as a still and never revisited.
 *
 * Deletion-awareness comes from the ledger itself: an authoritative walk that no longer returns an in-window
 * asset deletes its rows, so it leaves the projection with no second structure to keep in step (capability
 * `sync-ledger`, "Deletion is a presence diff over an authoritative walk"). The walk is judged by presence,
 * never by the policy-admitted set — the retired retain-live reconcile was fed that set, so a raised capture
 * cutoff discarded rows for photos still present and still uploaded.
 *
 * A kill mid-PUT loses nothing durable (the snapshot recomputes next cycle); the manifest is write-only
 * in v1 so transient staleness is benign and self-heals.
 *
 * Both processes publish (the app and, on iOS ≥26.1, the upload extension), and each publish carries the
 * ledger's manifest version its cycle read first; the backend refuses a strictly older one. That ordering is
 * what keeps two crossing publishes from leaving the backend a snapshot behind (capability `device-manifest`).
 */
class DeviceManifestProducer(
    private val store: DeviceManifestStore,
    private val publisher: ManifestPublisher,
    private val deviceId: String,
) {
    /**
     * Project and PUT; answers whether the published projection **changed**. [rows] are the ledger's
     * rows carrying manifest detail; [policy] is the membership's admission, applied here exactly as
     * every other consumer applies it.
     *
     * `true` means this call confirmed a write of a projection different from the last one confirmed.
     * `false` covers both "the projection was unchanged, so nothing was PUT" and "the PUT was not
     * confirmed".
     *
     * **Nothing reads this answer any more, and that is deliberate rather than an oversight.** It used to
     * gate the device's completion notify; the versioned device API removed that call, and the wake is
     * now the backend's effect of the write that makes an asset fetchable (capability
     * `upload-completion-notify`). It is kept because "did this cycle actually publish?" is the honest
     * result of the operation and is what a test asserts on — not because a caller branches on it.
     */
    suspend fun produce(
        eventId: String,
        policy: SelectionPolicy,
        rows: List<LedgerEntry>,
        manifestVersion: Long,
    ): Boolean {
        val json = projectDeviceManifest(deviceId, rows, policy).withVersion(manifestVersion).encodeToJson()
        // Skip-if-unchanged, keyed by EVENT. The projected JSON is event-independent (`{deviceId,
        // assets, version}`), so without the event id in the marker a **switch** to a new event would compare
        // equal to the prior event's upload and skip writing the new event's (still-absent) device.json.
        //
        // The marker holds the VERSION too — it rides inside the JSON — and that is load-bearing, not
        // incidental. Two cycles can read one version while a change commits between their row reads, so one
        // version can name two snapshots; the backend accepts an equal version, so the older can land last.
        // A marker comparing only the content would then name the newer snapshot while the backend holds the
        // older, and skip forever. With the version in it, the next cycle — which reads a higher version —
        // finds no match and republishes (decision record `changes/manifest-versions`, D3).
        //
        // A publish the backend refused as older answers 2xx like a stored one, and is recorded the same way:
        // a snapshot at least as new is already there, and at worst the next cycle republishes once.
        val marker = "$eventId $json"
        if (marker == store.loadLastUploaded()) return false
        if (!publisher.publish(eventId, deviceId, json)) return false
        store.saveLastUploaded(marker)
        return true
    }
}
