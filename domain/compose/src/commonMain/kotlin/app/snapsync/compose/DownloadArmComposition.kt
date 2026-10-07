package app.snapsync.compose

import app.snapsync.feature.download.DownloadArm

/**
 * Whether the download arm may run, over the current membership (capability `receiving-photos`). A top-level builder
 * rather than an [AppCore] body because `AppCore` is measured.
 */
internal fun AppCore.downloadArm(): DownloadArm = DownloadArm(
    // Three-valued, no fallback (capability `receiving-photos`): no membership → `null` → no arm.
    enabled = { services.config.config.value?.direction?.includesDownload },
    // Read fresh at each reconcile: a background wake has no screen's read-model (capability `sync-status`).
    keyHeld = { !services.eventKeys.lostFor(services.config.config.value) },
)
