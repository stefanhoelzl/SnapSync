package app.snapsync.compose

import app.snapsync.feature.membership.ReconfigureEvent
import app.snapsync.model.grantsPhotoAccess
import kotlinx.coroutines.launch

/**
 * The reconfigure use-case over the core's features (capability `manage-membership`). A builder rather than
 * an `AppCore` body because `AppCore` is measured (see [shareSetLoadFor]). Upload ARMS on enable but drains on
 * disable (no stop); download reconciles on enable and cancels in-flight on disable — the deliberate arm asymmetry
 * lives in the tested use-case.
 */
internal fun AppCore.reconfigureEventFor(): ReconfigureEvent =
    ReconfigureEvent(
        configSource = ports.configSource,
        store = ports.configStore,
        refreshStatus = { refreshStatusSources() },
        armUpload = { uploadTransitions.onReconfigure() },
        ensureAlbum = { cfg ->
            albumCoordinator.ensureAlbum(
                cfg.eventId,
                cfg.name,
                cfg.saveToAlbum,
                hasUsableAccess = ports.photoAccess.permission.value.grantsPhotoAccess,
            )
        },
        // Detached: `tap.reconfigure` is awaited by Save, and a gather's cost grows with what is held.
        gatherAlbum = { cfg -> albumGather.start("reconfigure", cfg.eventId) },
        // On its own escaping launch (like Provision's reconcile), so a slow union read never blocks
        // the command's return.
        startDownloads = { eventId -> scope.launch { downloadController.reconcile(eventId) } },
        cancelDownloads = { downloadController.onLeaveOrSwitch() },
        // The policy bounds are a manifest projection input that lives outside the ledger (capability
        // `manage-membership`); the use-case calls this after its config save has landed.
        bumpManifestVersion = { ports.uploadRecord.ledger.bumpManifestVersion() },
    )
