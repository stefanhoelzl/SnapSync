package app.snapsync.compose

import app.snapsync.feature.download.DownloadArm
import app.snapsync.feature.download.StoreDownloadStatusSource

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

/**
 * The joined screen's download progress, scoped to the joined event and counting the others' declared photos only for
 * a member who receives, net of this device's own published declaration. Top-level for the reason [downloadArm] is.
 */
internal fun AppCore.downloadStatus(): StoreDownloadStatusSource = StoreDownloadStatusSource(
    services.downloadStore,
    currentEvent = services.config::activeEventId,
    receives = services.config::downloadsEnabled,
    manifest = services.manifestStore,
)
