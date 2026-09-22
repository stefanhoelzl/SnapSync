package app.snapsync.compose

import app.snapsync.feature.upload.StoredUploadSettle

/**
 * The foreground settle (capability `upload-state-reconciliation`, "Foreground settles in-flight rows the backend
 * already stores") over the app's ports: the per-device listing it asks, and the ledger whose `REQUESTED` rows it
 * settles through the guarded terminal write.
 *
 * Composed in the APP only — the extension never settles from the listing. Like the join-time load, it needs no
 * `LedgerWriter`: its one write is `markTerminal`, the guarded write a platform callback makes beside a running
 * cycle (capability `sync-ledger`, "Reader and writer capability split").
 *
 * A top-level factory rather than an `AppCore` body for the same reason as `shareSetLoadFor`: `AppCore` is
 * measured by the `compose` tier's `LargeClass` ceiling.
 */
internal fun storedUploadSettleFor(ports: AppPorts): StoredUploadSettle = StoredUploadSettle(
    files = ports.uploadRecord.files,
    ledger = ports.uploadRecord.ledger,
    deviceId = ports.deviceId,
    log = ports.log,
)
