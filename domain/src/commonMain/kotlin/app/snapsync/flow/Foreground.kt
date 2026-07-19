package app.snapsync.flow

import app.snapsync.feature.download.DownloadController
import app.snapsync.feature.membership.EventName
import app.snapsync.feature.status.LedgerCountsPoller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The **foreground** OS-callback trigger flow (spec `module-architecture`, "Rules in features, order
 * in flows"; capability `sync-status` liveness). The scene returned to the foreground: re-read the
 * persisted membership (below), pump the app-driven upload tier (a no-op on the OS-driven tier),
 * start the foreground status poll, then — each on its own launch, so a slow one never blocks the
 * others — re-read the status sources, reconcile foreign downloads, refresh the event title, and
 * renew a stale attestation token.
 *
 * This flow **coordinates** (ordering + fan-out of the escaping launches); it **decides** nothing. The
 * stack-assembly touch and the entry-point log wrap stay in the shell (platform surfaces `flow/`
 * cannot reach); every step that touches a port ([reloadConfig] the membership re-read,
 * [pumpForeground] the tier pump, [refreshStatus] the read-model refreshes, [fetchEventName] the
 * directory fetch, [activeEventId] the config read, [refreshAttestation] the token wake) arrives as a
 * `model`-typed effect lambda built in `compose/`. The title refresh coordinates fetch-then-store:
 * whether a fetched name is *persisted* is [EventName]'s rule (`feature/membership`); a fetch that
 * resolves nothing (offline / 404 / parse) is the sealed no-result and stores nothing.
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
    private val scope: CoroutineScope,
    private val downloadController: DownloadController,
    /** The name-refresh rule (capability `join-event`): stores a fetched name iff still ours + changed. */
    private val eventName: EventName,
    /** The foreground-gated ledger-counts poll (capability `sync-status`); stopped by the Background flow. */
    private val statusPoller: LedgerCountsPoller,
    /** Re-read the persisted membership into the config StateFlow — the port touch, injected. */
    private val reloadConfig: () -> Unit,
    /** The app-driven tier's foreground pump; a no-op on iOS ≥26.1 where the OS owns scheduling. */
    private val pumpForeground: () -> Unit,
    /** Re-read the own-device total + ledger counts + the foreign-download line. */
    private val refreshStatus: suspend () -> Unit,
    /** The active event id, or `null` when unjoined — the config read, injected (a port touch). */
    private val activeEventId: () -> String?,
    /** Best-effort event-name fetch by id (`GET /events/:id`), or `null` on a miss/failure — the
     *  `EventDirectory` effect built in `compose/` (a port touch a flow may not make directly). */
    private val fetchEventName: suspend (eventId: String) -> String?,
    /** Renew the attestation token if it is stale (a wake point; covers launch, the first foreground). */
    private val refreshAttestation: () -> Unit,
) {
    fun run() {
        // Membership first: every reader below (the pump's arm guards, reconcile, the title refresh)
        // acts on the StateFlow this repairs.
        reloadConfig()
        // App-driven upload tier (iOS 18–26.0): foreground entry pumps an upload cycle. No-op on ≥26.1.
        pumpForeground()
        // Keep the ledger counts live while the screen is visible (the first tick waits one cadence;
        // the refreshStatus launch below covers "now").
        statusPoller.start()
        // Each escapes this trigger's synchronous span, labelling its own lines (or none).
        scope.launch { refreshStatus() }
        // Foreground-only discovery (capability `photo-download`): pick up foreign photos and import staged.
        scope.launch { activeEventId()?.let { downloadController.reconcile(it) } }
        // Keep the event title current (fills a name a scan couldn't fetch while offline): fetch, then
        // let the membership rule decide whether the result is persisted.
        scope.launch {
            activeEventId()?.let { id ->
                fetchEventName(id)?.let { fetched -> eventName.storeEventNameIfChanged(id, fetched) }
            }
        }
        // Wake point (capability `device-attestation`): renew the token if stale. Also covers launch.
        refreshAttestation()
    }
}
