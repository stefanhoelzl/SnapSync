package app.snapsync.compose

import app.snapsync.feature.download.DownloadArm

/**
 * Whether the download arm may run, over the current membership. A top-level builder rather than an [AppCore] body
 * because `AppCore` is measured.
 */
internal fun AppCore.downloadArm(): DownloadArm = DownloadArm(
    // Three-valued, no fallback: no membership → `null` → no arm.
    enabled = services.config::downloadsEnabled,
    // Read fresh at each reconcile: a background wake has no screen's read-model.

    keyHeld = { !services.eventKeys.lostFor(services.config.config.value) },
)
