package app.snapsync.feature.membership

import app.snapsync.model.EventConfig
import app.snapsync.model.runCatchingCancellable
import app.snapsync.services.config.ConfigService
import app.snapsync.services.leave.PendingLeaves
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The leave use-case: tears down the configured event's **local** state, best-effort, leaving every
 * already-uploaded object in storage untouched (see `manage-membership`).
 *
 * It does four things, in order: (1) **stop** the upload producer, (2) **clear the upload ledger**,
 * (3) **clear the persisted config**, then (4) **notify the backend** this device is leaving (via the
 * `LeaveNotifier` port — the backend records the membership as gone, keeps what it shared, and closes an ended event
 * the leave leaves with nobody unsettled). The membership is snapshotted **synchronously before** the clears (from
 * [ConfigService]) and passed into the notify, so the notify still targets the correct event even though the config
 * is already gone.
 *
 * **Every leave says whether this device has everything** (capability `manage-membership`): the member's own and the
 * app's once the event is finished for it are the same leave, so the backend never tells them apart — it records
 * `done` for a member that left having everything and `left` otherwise. Only receiving is the device's to say (the
 * backend judges its share itself), and only after the event's range has ended can a member have everything; before
 * it the answer is `false` without asking. Asking reads the event's photos over the network, so it is asked in the
 * background notify, never before the teardown; a doubt — offline, an unreadable union, a kill before the answer —
 * is `false`.
 *
 * **Leaving clears the upload ledger** — a deliberate reversal of the rule that no lifecycle transition
 * destroys dedup state (capabilities `photo-sharing`, `background-upload`). The ledger is the current
 * membership's share set: a later join rebuilds it from the device's stored-file listing, so nothing already
 * stored is uploaded again unless that listing fails (then the cost is idempotent re-uploads, bounded by
 * the next event's window). Only the **upload** ledger is cleared: the download store's handle-carrying rows
 * are what stop this device uploading its own imports back into an event, and nothing here touches them
 * (capability `receiving-photos`).
 *
 * Stop comes first so no mechanism starts new work against rows about to vanish. A transfer already in
 * flight may still complete after the clear: its outcome finds no row, is acknowledged and discarded, and
 * the bytes it landed are on the backend with no row — which the next join's listing seeds `COMPLETED`.
 * Nothing is lost and nothing loops.
 *
 * **The local teardown never waits on the network.** The clears are awaited, so [ConfigService] goes
 * `null` — and the screen leaves the joined layer — the instant the local state is torn down. The backend
 * notify is then dispatched **fire-and-forget** on the injected app-lifetime [scope] (which outlives the
 * screen transition), so a slow or hung `DELETE` can never freeze the screen after the user confirms
 * "Leave".
 *
 * The platform side-effects — stopping the producer, clearing the ledger, and the backend notify — are
 * injected as suspend lambdas, so this stays pure `commonMain` logic and the app shell stays wiring-only.
 * The notify lambda is built in `compose/` over the `LeaveNotifier` port — the same one `flow/Provision`
 * gets for the switch path, so the two routes to "this device left" cannot diverge.
 *
 * **Best-effort, no rollback:** each step runs independently; a failing step is logged and the rest still
 * run. A failed ledger clear does not stop the config clear or the notify (the next join clears the ledger
 * anyway). If [ConfigService.clear] fails, the event is still configured — the user is simply still joined,
 * the producer stopped until the next start, with an empty ledger whose cost is an idempotent re-upload. The
 * notify is dispatched **unconditionally** after the clears — a failed clear does not suppress it.
 *
 * The leave deliberately **keeps** the device-manifest record: nothing about the server's copy changed
 * here, so the belief is still true. It is the *re-join*'s enrollment that falsifies it, and that is where
 * it is cleared ([ManifestDeviceEnroller]).
 */
class LeaveEvent(
    private val config: ConfigService,
    private val stopUploads: suspend () -> Unit,
    /** Clear the upload ledger — the store's reset family, callable without the `LedgerWriter`. */
    private val clearLedger: suspend () -> Unit,
    private val notifyLeave: suspend (eventId: String, received: Boolean) -> Unit,
    /**
     * Whether this device holds every photo of the others in the membership [EventConfig] describes — the sibling
     * download controller's answer (feature-blindness), asked with the snapshot because the config is gone by then.
     */
    private val everythingReceived: suspend (EventConfig) -> Boolean,
    private val scope: CoroutineScope,
    /**
     * Where the leave is recorded as owed to the backend BEFORE anything is torn down (capability `event-lifetime`,
     * "A leave made offline still counts"): a kill between the teardown and [notifyLeave] then loses nothing — the
     * next wake delivers it.
     */
    private val pendingLeaves: PendingLeaves,
) {
    private val steps = Steps(Logger.withTag("LeaveEvent"), "leave")

    suspend fun leave() {
        // Snapshot the eventId synchronously BEFORE the clears so the backgrounded notify targets the
        // right event even though the config is gone by the time it runs (no race on the cleared cell).
        val current = config.config.value
        val eventId = current?.eventId
        val ended = current != null && config.hasEnded(current)
        if (eventId != null) steps.bestEffort("record the leave") { pendingLeaves.record(eventId) }
        steps.bestEffort("stop uploads") { stopUploads() }
        steps.bestEffort("clear upload ledger") { clearLedger() }
        steps.bestEffort("clear config") { config.clear() }
        // Fire-and-forget on the app-lifetime scope: the local teardown (and thus the screen flip) never
        // waits on the DELETE. Dispatched unconditionally after the clears (a failed clear does not gate it).
        if (current != null) {
            scope.launch {
                val received = ended &&
                    runCatchingCancellable { everythingReceived(current) }.getOrDefault(false)
                steps.bestEffort("notify backend") { notifyLeave(current.eventId, received) }
            }
        }
    }

}
