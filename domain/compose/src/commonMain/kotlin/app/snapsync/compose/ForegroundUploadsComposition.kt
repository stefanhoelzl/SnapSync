package app.snapsync.compose

import app.snapsync.feature.upload.StoredUploadSettle
import app.snapsync.flow.ForegroundUploads

/**
 * What the upload side contributes to a foreground entry, over the app's ports: the tier pump and the settle of
 * in-flight uploads the backend already stores — two independent steps the `Foreground` flow launches side by side.
 *
 * A top-level factory rather than an `AppCore` body for the same reason as `shareSetLoadFor`: `AppCore` is
 * measured by the `compose` tier's `LargeClass` ceiling.
 */
internal fun foregroundUploadsFor(ports: AppPorts): ForegroundUploads = ForegroundUploads(
    // Delivered unconditionally to the app engine; its cycle's entry gate declines while another
    // mechanism is resolved (`upload-lifecycle`, "Triggers are delivered to the mechanism and
    // declined explicitly").
    //
    // This used to branch on permission here — GRANTED to the tier's pump, LIMITED to the
    // selection drain — and that branch was compensating for thunks that could not see the
    // permission. It said exactly one thing: on an OS carrying the OS-driven mechanism under a
    // full grant, do not pump, because the OS owns scheduling. That IS the resolution, and the
    // engine's gate now says it once. (The two pump entry points it chose between have identical
    // bodies; the choice was never between them.)
    pump = { ports.appDrivenUpload().onForeground() },
    settleStored = storedUploadSettleFor(ports)::settle,
)

/**
 * The foreground settle (capability `upload-state-reconciliation`, "Foreground settles in-flight rows the backend
 * already stores") over the app's ports: the per-device listing it asks, and the ledger whose `REQUESTED` rows it
 * settles through the guarded terminal write.
 *
 * Composed in the APP only — the extension never settles from the listing. Like the join-time load, it needs no
 * `LedgerWriter`: its one write is `markTerminal`, the guarded write a platform callback makes beside a running
 * cycle (capability `sync-ledger`, "Reader and writer capability split").
 */
internal fun storedUploadSettleFor(ports: AppPorts): StoredUploadSettle = StoredUploadSettle(
    files = ports.uploadRecord.files,
    ledger = ports.uploadRecord.ledger,
    deviceId = ports.deviceId,
    log = ports.log,
)
