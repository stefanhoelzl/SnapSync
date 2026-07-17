package app.snapsync.flow

import app.snapsync.feature.download.DownloadController
import app.snapsync.feature.membership.EventName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The **foreground** OS-callback trigger flow (spec `module-architecture`, "Rules in features, order
 * in flows"; capability `sync-status` liveness). The scene returned to the foreground: pump the
 * app-driven upload tier (a no-op on the OS-driven tier), then — each on its own launch, so a slow
 * one never blocks the others — re-read the status sources, reconcile foreign downloads, refresh the
 * event title, and renew a stale attestation token.
 *
 * This flow **coordinates** (ordering + fan-out of the escaping launches); it **decides** nothing. The
 * stack-assembly touch, the Darwin liveness-observer (re)registration, and the entry-point log wrap
 * stay in the shell (platform surfaces `flow/` cannot reach); every step that touches a port
 * ([pumpForeground] the tier pump, [refreshStatus] the read-model refreshes, [fetchEventName] the
 * directory fetch, [activeEventId] the config read, [refreshAttestation] the token wake) arrives as a
 * `model`-typed effect lambda built in `compose/`. The title refresh coordinates fetch-then-store:
 * whether a fetched name is *persisted* is [EventName]'s rule (`feature/membership`); a fetch that
 * resolves nothing (offline / 404 / parse) is the sealed no-result and stores nothing.
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
        // App-driven upload tier (iOS 18–26.0): foreground entry pumps an upload cycle. No-op on ≥26.1.
        pumpForeground()
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
