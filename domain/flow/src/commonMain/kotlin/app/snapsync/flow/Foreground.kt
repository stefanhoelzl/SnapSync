package app.snapsync.flow

import app.snapsync.model.JoinLoad

import app.snapsync.feature.download.DownloadController
import app.snapsync.feature.membership.MembershipRefresh
import app.snapsync.feature.status.StatusCountsPoller

/**
 * The **foreground** OS-callback trigger flow (`docs/architecture.md`, "Rules in features, order
 * in flows"; capability `sync-status` liveness). The scene returned to the foreground: re-read the
 * persisted membership (below), renew a stale attestation token, start the foreground status poll,
 * then — each on its own launch, so a slow one never blocks the others — settle in-flight uploads whose
 * bytes the backend already stores, re-read the status sources, reconcile foreign downloads, reclaim the
 * staged bytes of already-imported downloads, and refresh the event title. That is foreground entry's
 * **own work**, and all of it (decision record `changes/own-work-per-wake`, D1).
 *
 * **The imports, the upload top-up and the walk are not this flow's** (capability `sync-status`,
 * "Foreground status refresh is not sequenced behind the upload tail"). They are the process's one
 * opportunistic tail, which the inbound port's implementation requests **after** this flow returns (law
 * "A trigger flow never outlives its own run"): a foreground entry arriving while another wake's tail is
 * still walking — outstanding for as long as the app was suspended, 774 s measured (`SNAPSYNC-16`) —
 * joins that tail rather than running a second upload path beside it, and nothing here waits on it, so
 * the status refresh never inherits the walk's latency. `run()` awaits every child it has.
 *
 * This flow **coordinates** (ordering + fan-out of the escaping launches); it **decides** nothing. The
 * stack-assembly touch and the entry-point log wrap stay with the inbound port's implementation; every
 * step that touches a port ([reloadConfig] the membership re-read, [settleStored] the stored-upload
 * settle, [refreshStatus] the read-model refreshes, [fetchEventDetails] the directory fetch,
 * [activeEventId] the config read, [refreshAttestation] the token wake) arrives as a `model`-typed effect
 * lambda built in `compose/`.
 *
 * The membership refresh coordinates fetch-then-fold: what a fetched result *means* is
 * [MembershipRefresh]'s rule (`feature/membership`), including the one destructive consequence — when the
 * event is definitively gone AND past the membership's own stored deadline, the rule tears the membership
 * down, returning the device to the unjoined resting state.
 *
 * That teardown is reachable from THIS trigger and no background one, deliberately. `SilentPush`
 * promises that nothing mints, clears, or leaves, because a background wake can land
 * before the first unlock and read an unreadable config as *absent* — destroying a healthy membership.
 * Foreground entry re-reads the membership from an unlocked device first ([reloadConfig], below), which
 * is the only context where acting on absence is safe.
 *
 * [reloadConfig] runs **first** (migration step 12, replacing the deleted unlock-hook repair): a
 * background launch before the first unlock seeds an unreadable — therefore empty — config
 * StateFlow, and cross-process writes never notify this process, so every step below that reads
 * the membership must see a freshly-read value or the screen sits at the setup gate despite a
 * perfectly good persisted membership. Foreground entry cannot run before the first unlock (the
 * UI requires an unlocked device), so the re-read here always sees readable state.
 *
 * [statusPoller] keeps the counts live *between* entries (capability `sync-status`): the extension
 * records completions in its own process, and with the Darwin ding gone the foreground-gated poll
 * is what moves the joined screen while the user watches. Start/stop ordering is this flow's and
 * the Background flow's coordination; the cadence is the feature's rule.
 *
 * The launches deliberately **escape** the entry-point log context (they run after the synchronous
 * dispatch returns), exactly as the shell's `onForeground` did — so `reconcile` and the other
 * self-wrapping features label their own lines, and `refreshStatus` runs with no ambient prefix.
 */
class Foreground(
    private val downloadController: DownloadController,
    /** The membership-refresh rule (capability `join-event`): folds a fetched result, backfills, and on
     *  a CONFIRMED absence performs the teardown itself. */
    private val membershipRefresh: MembershipRefresh,
    /** The foreground-gated ledger-counts poll (capability `sync-status`); stopped by the Background flow. */
    private val statusPoller: StatusCountsPoller,
    /** Re-read the persisted membership into the config StateFlow — the port touch, injected. */
    private val reloadConfig: suspend () -> Unit,
    /** Settle the `REQUESTED` rows whose bytes the backend's per-device listing already stores (capability
     *  `photo-sharing`) — the listing fetch and the guarded write. The upload side's only own work. */
    private val settleStored: suspend () -> Unit,
    /** Re-read the own-device total + ledger counts + the foreign-download line. */
    private val refreshStatus: suspend () -> Unit,
    /** The active event id, or `null` when unjoined — the config read, injected (a port touch). */
    private val activeEventId: () -> String?,
    /** Event-details fetch by id (`GET /events/:id`) — the `EventDirectory` effect built in `compose/`
     *  (a port touch a flow may not make directly). Carries the SEALED outcome: `NotFound` (definitively
     *  gone) must stay distinguishable from `Failed` (could not tell), because that difference is the
     *  only thing separating a real deletion from a transient fault. */
    private val fetchEventDetails: suspend (eventId: String) -> JoinLoad,
    /** Renew the attestation token if it is stale (a wake point; covers launch, the first foreground). */
    private val refreshAttestation: suspend () -> Unit,
) {
    suspend fun run() {
        // Membership first: every reader below (reconcile, the title refresh) acts on the StateFlow this
        // repairs.
        reloadConfig()
        // Wake point (capability `privacy-security`): renew the token if stale. Also covers launch.
        // BEFORE the network-bearing work below, not after it: this used to be a fire-and-forget launch
        // fired alongside them, so a refresh and the fetches it exists to authorize raced, and a fetch
        // could go out carrying the very token being replaced. `refresh()` short-circuits on a
        // fresh token, so the sequencing costs nothing in the common case.
        refreshAttestation()
        // Keep the ledger counts live while the screen is visible (the first tick waits one cadence;
        // the refreshStatus launch below covers "now"). Non-blocking: the poller owns its own scope.
        statusPoller.start()
        // Concurrent AND awaited, so `run()` returns when they are done rather than when they are
        // queued (law "A trigger flow never outlives its own run"). Each still labels its own log
        // lines: `coroutineScope` children escape this trigger's synchronous span exactly as the
        // former `scope.launch` bodies did. A child that THROWS cancels none of its siblings.
        fanOut("Foreground") {
            // Not behind anything (capability `photo-sharing`, "Foreground settles in-flight rows
            // the backend already stores"): bytes can land long before the OS acknowledges their job, and this
            // exists to correct the status the member is looking at now. Its one write is the guarded terminal
            // write the platform's callbacks already make beside a running tail, so it needs no ordering with it.
            child("settleStored") { settleStored() }
            child("refreshStatus") { refreshStatus() }
            // Foreground discovery (capability `receiving-photos`): pick up foreign photos, plan and enqueue. It
            // imports nothing — the staged imports are the tail's first unit, requested after this flow returns.
            child("reconcile") { activeEventId()?.let { downloadController.reconcile(it) } }
            // The staged-byte backlog reclaim (capability `receiving-photos`): free the files of assets
            // whose import is confirmed but whose resource rows predate per-asset release, so a received
            // photo is not stored twice — as a library asset and as a staged file — forever.
            //
            // HERE, on foreground entry: the reclaim is a ONE-SHOT backlog whose whole value is that it
            // eventually runs on every affected install, and foreground entry is the one trigger a user
            // reaching the app cannot avoid — a background task the OS may defer indefinitely (the retired
            // download backstop was one) may never run at all. It is
            // self-extinguishing — releasing drops the very rows that made the work findable — so the
            // cost from the second foreground onward is one store query that returns nothing, with no
            // flag, no migration marker and no run-once bookkeeping to get wrong.
            //
            // UNCONDITIONAL, unlike `reconcile`: the backlog belongs to the DEVICE, not to a membership.
            // A device that has left, or has since joined an upload-only event, still holds the orphaned
            // files of everything it imported before — and nothing else reaches them (`onLeaveOrSwitch`
            // releases only the NON-terminal rows it is about to prune; an imported row is terminal by
            // construction). Behind the `activeEventId()` guard the leak would simply survive.
            //
            // Concurrent with the reconcile above, and that is safe: both act only on rows whose
            // confirming write has already committed, and every path that commits one releases that
            // row's bytes and drops its resource rows INLINE under the controller's mutex before
            // returning. So this pass can neither observe a row mid-import nor drop resource rows whose
            // paths it did not read.
            child("releaseSettledBytes") { downloadController.releaseSettledBytes() }
            // Keep the membership current: fetch, then let the membership rule decide what the result MEANS
            // (name refresh, window/retention backfill, or — on a CONFIRMED absence — the teardown).
            child("membershipRefresh") {
                activeEventId()?.let { id -> membershipRefresh.refresh(id, fetchEventDetails(id)) }
            }
        }
    }
}
