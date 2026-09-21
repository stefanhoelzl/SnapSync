package app.snapsync.flow

import app.snapsync.feature.album.AlbumCoordinator
import app.snapsync.feature.download.DownloadController
import app.snapsync.feature.membership.SwitchDecision
import app.snapsync.feature.membership.switchDecision
import app.snapsync.model.EventConfig
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The **provision** trigger flow — the shared path for a scanned/typed event link and a freshly created
 * event (capabilities `event-link`, `upload-lifecycle`, `photo-selection-policy`). It **coordinates**
 * the join side effects in order and **decides** nothing: whether a transition is due is
 * `feature/membership`'s sealed [switchDecision] rule.
 *
 * A provision into a **new** membership replaces the upload ledger — the ledger is the current
 * membership's share set (capability `sync-ledger`). That reverses the old rule that no destructive verb
 * reaches a provision (`upload-lifecycle`): a switch now stops the previous membership's uploads, and a
 * switch or a first join resets the ledger through [enterMembership]. A re-provision of the joined event
 * reaches neither.
 *
 * Order:
 *  1–2. **Enter the new membership** (a switch or a first join; `feature/membership`'s `MembershipEntry`
 *     owns the order): on a switch, stop the previous membership's uploads, then fire the best-effort
 *     backend leave; then load the new share set — clear the upload ledger and seed it from the device's
 *     stored-file listing, or leave it empty when the listing fails. It never blocks the join. It runs
 *     BEFORE the save, so no cycle ever sees the new membership over the previous one's ledger; a crash
 *     between the two leaves either an unjoined device whose next join reloads anyway, or the previous
 *     membership, whose next walk re-records its work.
 *  3. **Save** the whole [EventConfig] as-is (never destructured — a newly-added field like the cutoff
 *     must not be dropped before the persist the extension reads).
 *  4. **Refresh** the status sources (re-enumerate the own total, re-read completeness) — synchronous,
 *     so its lines carry this trigger's log context, as before.
 *  5. **Reconcile the upload mechanisms** ([reconcileUploads] — the upload arm's join transition,
 *     capability `upload-lifecycle`): the extension registration is forced (the stale-record repair) and
 *     the app engine armed or disarmed, from resolution and the membership's direction. It runs after the
 *     share-set load, so a first join has no registered extension that could race it.
 *  6. **Album** — ask the coordinator for the event album, unconditionally, passing the access fact
 *     along with the membership's: the granted/opt-in gate is [AlbumCoordinator.ensureAlbum]'s own
 *     leading guard (`event-album`; the grant subscription covers the grant-after-join case).
 *  7. **Reconcile** foreign downloads and **re-register the push token** — concurrently, so a slow
 *     one never blocks the other and each labels its own log lines, but awaited before `run()`
 *     returns (law "A trigger flow never outlives its own run").
 *
 * This flow issues **no** event-details fetch. It once did, to fill a title a scan could not fetch
 * while offline; a membership can no longer arrive nameless (capability `event-link`), and every
 * provision route — interactive join, `autoJoin`, switch, headless create — has just loaded or minted
 * the event's details, so the fetch was redundant by construction. `Foreground` is the sole trigger
 * that refreshes the membership (capability `join-event`).
 *
 * Port touches ([activeEventId], [enterMembership], [saveConfig], [refreshStatus], [isGranted]) arrive as
 * `model`-typed effect lambdas built in `compose/`; the album rule lives in its feature
 * ([AlbumCoordinator]).
 */
class Provision(
    /** The upload arm's join transition (capability `upload-lifecycle`) — built in `compose/`. */
    private val reconcileUploads: suspend () -> Unit,
    private val downloadController: DownloadController,
    /** The event-album coordinator (capability `event-album`); its `ensureAlbum` owns the opt-in gate. */
    private val albumCoordinator: AlbumCoordinator,
    /** The currently-joined event id, or `null` — the config read (a port touch). */
    private val activeEventId: () -> String?,
    /**
     * Enter a new membership — leaving `previousEventId` first on a switch (`null` for a first join) — and
     * make the upload ledger its share set (capability `upload-state-reconciliation`).
     */
    private val enterMembership: suspend (previousEventId: String?) -> Unit,
    /** Persist the whole config (a port touch). */
    private val saveConfig: suspend (EventConfig) -> Unit,
    /** Re-enumerate the own total + re-read completeness (read-model refreshes). */
    private val refreshStatus: suspend () -> Unit,
    /** Whether photo access is fully granted (a port touch). */
    private val isGranted: () -> Boolean,
    /** Re-register the device's APNs push token with the backend on join (capability
     *  `push-registration`). Beyond the launch/rotation registration, joining re-`PUT`s the token so a
     *  device whose config the nightly sweep collected (capability `scheduled-cleanup`) is pushable again
     *  the instant it rejoins WARM — before its next cold launch. Idempotent, best-effort; the inert
     *  default keeps world/tests from needing a push stack. */
    private val registerPush: suspend () -> Unit = {},
) {
    suspend fun run(cfg: EventConfig) {
        // 1–2. Enter the new membership — a switch or a first join. Re-scanning the same event is a Stay:
        //      nothing is stopped, left or loaded.
        when (val decision = switchDecision(activeEventId(), cfg.eventId)) {
            is SwitchDecision.Enter -> enterMembership(decision.previousEventId)
            SwitchDecision.Stay -> Unit
        }
        // 3. Persist the full config as-is (the per-device cutoff rides along untouched).
        saveConfig(cfg)
        // 4. (re)joined event → re-enumerate own total + re-read completeness (synchronous: keeps context).
        refreshStatus()
        // 5. Reconcile the upload mechanisms for the membership just saved.
        reconcileUploads()
        // 6. Event album — an unconditional call carrying the access FACT: the granted/opt-in/name
        //    gate is the coordinator's own leading guard (capability `event-album`), so no caller can
        //    forget it. (The grant subscription covers the grant-after-join case.)
        albumCoordinator.ensureAlbum(cfg.eventId, cfg.name, cfg.saveToAlbum, granted = isGranted())
        // 7. Auto-download the other contributors' photos (no-op under an upload-only direction, gated
        //    inside the controller) and re-register the push token — the latter closes the warm-rejoin
        //    window the sweep's device-record collection opens (capability `push-registration`). No
        //    details fetch rides here: the membership this flow just persisted came from details the
        //    route already loaded or minted.
        //
        //    Concurrent, so a slow network PUT never blocks the reconcile — but AWAITED (law "A trigger
        //    flow never outlives its own run"): a join whose reconcile and registration are merely
        //    queued when `run()` returns is a join the caller cannot truthfully report as finished.
        coroutineScope {
            launch { downloadController.reconcile(cfg.eventId) }
            launch { registerPush() }
        }
    }
}
