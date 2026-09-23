package app.snapsync.flow

import app.snapsync.feature.album.AlbumCoordinator
import app.snapsync.feature.download.DownloadController
import app.snapsync.feature.membership.SwitchDecision
import app.snapsync.feature.membership.switchDecision
import app.snapsync.model.EventConfig

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
 *  1. **Enter the new membership, or save** — the switch decision's two branches:
 *     - a switch or a first join runs `feature/membership`'s `MembershipEntry`, which owns the order: on a
 *       switch, stop the previous membership's uploads (the extension deregistered, the app's transfers
 *       cancelled), then fire the best-effort backend leave; then load the new share set — clear the upload
 *       ledger and seed it from the device's stored-file listing, or leave it empty when the listing fails,
 *       never blocking the join — BEFORE the save, so no cycle ever sees the new membership over the previous
 *       one's ledger; then save the whole [EventConfig] as-is; then start the uploads (the upload arm's join
 *       transition — the extension registration forced where the OS allows it, the app armed where access is
 *       usable), after the save so a registered extension never reads the previous membership's config;
 *     - a re-provision of the joined event ([SwitchDecision.Stay]) only **saves** the config: nothing is
 *       stopped, left, loaded or registered, so a re-scan never wipes the extension's in-flight jobs
 *       (capability `upload-lifecycle`; decision record `changes/both-uploaders-active`, D5).
 *     The config is saved as-is on both branches (never destructured — a newly-added field like the cutoff
 *     must not be dropped before the persist the extension reads).
 *  2. **Refresh** the status sources (re-enumerate the own total, re-read completeness) — synchronous,
 *     so its lines carry this trigger's log context, as before.
 *  3. **Album** — ask the coordinator for the event album, unconditionally, passing the access fact
 *     along with the membership's: the granted/opt-in gate is [AlbumCoordinator.ensureAlbum]'s own
 *     leading guard (`event-album`; the grant subscription covers the grant-after-join case).
 *  4. **Reconcile** foreign downloads and **re-register the push token** — concurrently, so a slow
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
    private val downloadController: DownloadController,
    /** The event-album coordinator (capability `event-album`); its `ensureAlbum` owns the opt-in gate. */
    private val albumCoordinator: AlbumCoordinator,
    /** The currently-joined event id, or `null` — the config read (a port touch). */
    private val activeEventId: () -> String?,
    /**
     * Enter a new membership — leaving `previousEventId` first on a switch (`null` for a first join), making the
     * upload ledger its share set, saving the config and starting its uploads (capabilities
     * `upload-state-reconciliation`, `upload-lifecycle`).
     */
    private val enterMembership: suspend (previousEventId: String?, cfg: EventConfig) -> Unit,
    /** Persist the whole config (a port touch) — the re-provision branch's only step. */
    private val saveConfig: suspend (EventConfig) -> Unit,
    /** Re-enumerate the own total + re-read completeness (read-model refreshes). */
    private val refreshStatus: suspend () -> Unit,
    /** Whether photo access is fully granted (a port touch). */
    private val isGranted: () -> Boolean,
    /** Re-register the device's APNs push token with the backend on join (capability
     *  `push-registration`). Beyond the launch/rotation registration, joining re-`PUT`s the token so a
     *  device whose config the nightly sweep collected (capability `scheduled-cleanup`) is pushable again
     *  the instant it rejoins WARM — before its next cold launch. Idempotent, best-effort. */
    private val registerPush: suspend () -> Unit,
) {
    suspend fun run(cfg: EventConfig) {
        // 1. Enter the new membership (a switch or a first join: leave, load, save, start uploads — the entry's
        //    order), or — re-scanning the joined event — only save: nothing is stopped, left, loaded or registered.
        when (val decision = switchDecision(activeEventId(), cfg.eventId)) {
            is SwitchDecision.Enter -> enterMembership(decision.previousEventId, cfg)
            SwitchDecision.Stay -> saveConfig(cfg)
        }
        // 2. (re)joined event → re-enumerate own total + re-read completeness (synchronous: keeps context).
        refreshStatus()
        // 3. Event album — an unconditional call carrying the access FACT: the granted/opt-in/name
        //    gate is the coordinator's own leading guard (capability `event-album`), so no caller can
        //    forget it. (The grant subscription covers the grant-after-join case.)
        albumCoordinator.ensureAlbum(cfg.eventId, cfg.name, cfg.saveToAlbum, granted = isGranted())
        // 4. Auto-download the other contributors' photos (no-op under an upload-only direction, gated
        //    inside the controller) and re-register the push token — the latter closes the warm-rejoin
        //    window the sweep's device-record collection opens (capability `push-registration`). No
        //    details fetch rides here: the membership this flow just persisted came from details the
        //    route already loaded or minted.
        //
        //    Concurrent, so a slow network PUT never blocks the reconcile — but AWAITED (law "A trigger
        //    flow never outlives its own run"): a join whose reconcile and registration are merely
        //    queued when `run()` returns is a join the caller cannot truthfully report as finished.
        fanOut("Provision") {
            child("reconcile") { downloadController.reconcile(cfg.eventId) }
            child("registerPush") { registerPush() }
        }
    }
}
