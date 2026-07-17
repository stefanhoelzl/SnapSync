package app.snapsync.flow

import app.snapsync.feature.download.DownloadController
import app.snapsync.feature.upload.UploadArm
import app.snapsync.model.EventConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The **provision** trigger flow — the shared path for a scanned/typed event link and a freshly created
 * event (capabilities `event-link`, `upload-lifecycle`, `photo-selection-policy`). It **coordinates**
 * the join side effects in order and **decides** nothing; the destructive verbs a provision must never
 * reach simply do not exist in the seams it calls (`upload-lifecycle`).
 *
 * Order (each preserved from the shell's former `provisionEvent`):
 *  1. **Switch** — provisioning a *different* event while joined leaves the previous one on the backend
 *     first (best-effort via [notifyLeave]); re-provisioning the same event is not a switch.
 *  2. **Save** the whole [EventConfig] as-is (never destructured — a newly-added field like the cutoff
 *     must not be dropped before the persist the extension reads).
 *  3. **Refresh** the status sources (re-enumerate the own total, re-read completeness) — synchronous,
 *     so its lines carry this trigger's log context, as before.
 *  4. **Arm** the tier-neutral upload lifecycle ([UploadArm.onProvision]): with access granted it starts
 *     the producer (or stops it for a download-only membership); with no access it defers to the grant.
 *  5. **Album** — create the event album now if opted in and permission is already granted (the grant
 *     subscription covers the grant-after-join case).
 *  6. **Reconcile** foreign downloads and **fetch the name** — each on its own escaping launch, so a
 *     slow one never blocks the join and each labels its own log lines.
 *
 * Port touches ([activeEventId], [notifyLeave], [saveConfig], [refreshStatus], [isGranted]) arrive as
 * `model`-typed effect lambdas built in `compose/`; [ensureAlbumIfOptedIn] and [fetchName] stay
 * `compose/`-supplied shell helpers until their rules sink into features at C3.
 */
class Provision(
    private val scope: CoroutineScope,
    private val uploadArm: UploadArm,
    private val downloadController: DownloadController,
    /** The currently-joined event id, or `null` — the config read (a port touch). */
    private val activeEventId: () -> String?,
    /** Best-effort backend leave of a previous event on a switch. */
    private val notifyLeave: suspend (eventId: String) -> Unit,
    /** Persist the whole config (a port touch). */
    private val saveConfig: suspend (EventConfig) -> Unit,
    /** Re-enumerate the own total + re-read completeness (read-model refreshes). */
    private val refreshStatus: suspend () -> Unit,
    /** Whether photo access is fully granted (a port touch). */
    private val isGranted: () -> Boolean,
    /** Create the event album if opted in (a `compose/`-supplied shell helper until the C3 rule sink). */
    private val ensureAlbumIfOptedIn: suspend () -> Unit,
    /** Best-effort event-name fetch (a `compose/`-supplied shell helper until the C3 rule sink). */
    private val fetchName: suspend (eventId: String) -> Unit,
) {
    suspend fun run(cfg: EventConfig) {
        // 1. Switch: leave the previous event on the backend first (best-effort). Re-scanning the same
        //    event is not a switch and fires no leave.
        activeEventId()?.let { previous ->
            if (previous != cfg.eventId) notifyLeave(previous)
        }
        // 2. Persist the full config as-is (the per-device cutoff rides along untouched).
        saveConfig(cfg)
        // 3. (re)joined event → re-enumerate own total + re-read completeness (synchronous: keeps context).
        refreshStatus()
        // 4. Drive the tier-neutral upload lifecycle; nothing is cancelled or reset (no destructive verb).
        uploadArm.onProvision()
        // 5. Create the event album if opted in and access is already granted (grant subscription covers
        //    the grant-after-join case). Capability `event-album`.
        if (isGranted()) ensureAlbumIfOptedIn()
        // 6. Auto-download the other contributors' photos (no-op under an upload-only direction, gated
        //    inside the controller), and fill a scan-path title by id — each on its own escaping launch.
        scope.launch { downloadController.reconcile(cfg.eventId) }
        if (cfg.name.isEmpty()) scope.launch { fetchName(cfg.eventId) }
    }
}
