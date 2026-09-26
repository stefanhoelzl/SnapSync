package app.snapsync.compose

import app.snapsync.feature.upload.StoredUploadSettle
import app.snapsync.services.backend.DeviceFilesSource

/**
 * The foreground settle (capability `photo-sharing`, "Foreground settles in-flight rows the backend
 * already stores") over the app's ports: the per-device listing it asks, and the ledger whose `REQUESTED` rows it
 * settles through the guarded terminal write — the upload side's only own work at a foreground entry; its top-up and
 * walk are the process tail's (decision record `changes/own-work-per-wake`, D1).
 *
 * Composed in the APP only — the extension never settles from the listing. Like the join-time load, it needs no
 * `LedgerWriter`: its one write is `markTerminal`, the guarded write a platform callback makes beside a running
 * tail (capability `photo-sharing`, "Reader and writer capability split").
 *
 * A top-level factory rather than an `AppCore` body for the same reason as `shareSetLoadFor`: `AppCore` is measured
 * by the `compose` tier's `LargeClass` ceiling.
 */
internal fun storedUploadSettleFor(ports: AppPorts, files: DeviceFilesSource): StoredUploadSettle = StoredUploadSettle(
    files = files,
    ledger = ports.uploadRecord.ledger,
    identity = ports.deviceIdentity,
    log = ports.log,
)
