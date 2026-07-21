package app.snapsync.flow

import app.snapsync.feature.album.AlbumCoordinator
import app.snapsync.feature.download.DownloadController
import app.snapsync.feature.membership.EventName
import app.snapsync.feature.membership.SwitchDecision
import app.snapsync.feature.membership.TitleNeed
import app.snapsync.feature.membership.switchDecision
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
 *  1. **Switch** — whether a leave is due is `feature/membership`'s sealed [switchDecision] rule;
 *     on a switch the previous event is left on the backend first (best-effort via [notifyLeave]).
 *  2. **Save** the whole [EventConfig] as-is (never destructured — a newly-added field like the cutoff
 *     must not be dropped before the persist the extension reads).
 *  3. **Refresh** the status sources (re-enumerate the own total, re-read completeness) — synchronous,
 *     so its lines carry this trigger's log context, as before.
 *  4. **Arm** the tier-neutral upload lifecycle ([UploadArm.onProvision]): with access granted it starts
 *     the producer (or stops it for a download-only membership); with no access it defers to the grant.
 *  5. **Album** — ask the coordinator for the event album, unconditionally, passing the access fact
 *     along with the membership's: the granted/opt-in gate is [AlbumCoordinator.ensureAlbum]'s own
 *     leading guard (`event-album`; the grant subscription covers the grant-after-join case).
 *  6. **Reconcile** foreign downloads and **fetch the name** — each on its own escaping launch, so a
 *     slow one never blocks the join and each labels its own log lines. Whether the fetch is due is
 *     [EventName.fetchNeed]'s sealed rule (only a nameless, scan-path config needs one), and whether
 *     the result is persisted is [EventName]'s store rule.
 *
 * Port touches ([activeEventId], [notifyLeave], [saveConfig], [refreshStatus], [isGranted],
 * [fetchEventName]) arrive as `model`-typed effect lambdas built in `compose/`; the album and
 * name rules live in their features ([AlbumCoordinator], [EventName]).
 */
class Provision(
    private val scope: CoroutineScope,
    private val uploadArm: UploadArm,
    private val downloadController: DownloadController,
    /** The event-album coordinator (capability `event-album`); its `ensureAlbum` owns the opt-in gate. */
    private val albumCoordinator: AlbumCoordinator,
    /** The name-refresh rule (capability `join-event`): stores a fetched name iff still ours + changed. */
    private val eventName: EventName,
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
    /** Best-effort event-name fetch by id, or `null` on a miss/failure — the `EventDirectory` effect
     *  built in `compose/` (a port touch a flow may not make directly). */
    private val fetchEventName: suspend (eventId: String) -> String?,
    /** Re-register the device's APNs push token with the backend on join (capability
     *  `push-registration`). Beyond the launch/rotation registration, joining re-`PUT`s the token so a
     *  device whose config the nightly sweep collected (capability `scheduled-cleanup`) is pushable again
     *  the instant it rejoins WARM — before its next cold launch. Idempotent, best-effort; the inert
     *  default keeps world/tests from needing a push stack. */
    private val registerPush: suspend () -> Unit = {},
) {
    suspend fun run(cfg: EventConfig) {
        // 1. Switch: whether a leave is due is membership's sealed rule; on LeavePrevious the flow
        //    fires the best-effort backend leave first. Re-scanning the same event is a Stay.
        when (val decision = switchDecision(activeEventId(), cfg.eventId)) {
            is SwitchDecision.LeavePrevious -> notifyLeave(decision.previousEventId)
            SwitchDecision.Stay -> Unit
        }
        // 2. Persist the full config as-is (the per-device cutoff rides along untouched).
        saveConfig(cfg)
        // 3. (re)joined event → re-enumerate own total + re-read completeness (synchronous: keeps context).
        refreshStatus()
        // 4. Drive the tier-neutral upload lifecycle; nothing is cancelled or reset (no destructive verb).
        uploadArm.onProvision()
        // 5. Event album — an unconditional call carrying the access FACT: the granted/opt-in/name
        //    gate is the coordinator's own leading guard (capability `event-album`), so no caller can
        //    forget it. (The grant subscription covers the grant-after-join case.)
        albumCoordinator.ensureAlbum(cfg.eventId, cfg.name, cfg.saveToAlbum, granted = isGranted())
        // 6. Auto-download the other contributors' photos (no-op under an upload-only direction, gated
        //    inside the controller), and fill a scan-path title by id — each on its own escaping
        //    launch. Whether a fetch is due is membership's sealed rule (a scan-path config arrives
        //    nameless); whether the result is persisted is [EventName]'s.
        scope.launch { downloadController.reconcile(cfg.eventId) }
        // Re-register the push token on join (not on every foreground): closes the warm-rejoin window
        // the sweep's device-record collection opens (capability `push-registration`). Its own escaping
        // launch — a network PUT must never block the join — and best-effort.
        scope.launch { registerPush() }
        when (eventName.fetchNeed(cfg.name)) {
            TitleNeed.MISSING -> scope.launch {
                eventName.storeEventNameIfChanged(cfg.eventId, fetchEventName(cfg.eventId))
            }
            TitleNeed.PRESENT -> Unit
        }
    }
}
