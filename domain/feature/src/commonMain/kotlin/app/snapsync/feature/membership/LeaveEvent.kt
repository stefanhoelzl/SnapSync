package app.snapsync.feature.membership

import app.snapsync.ports.ConfigSource
import app.snapsync.ports.ConfigStore
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The leave use-case: tears down the configured event's **local** state, best-effort, leaving every
 * already-uploaded object in storage untouched (see `leave-event`).
 *
 * It does four things, in order: (1) **stop** the upload producer, (2) **clear the upload ledger**,
 * (3) **clear the persisted config**, then (4) **notify the backend** this device is leaving (via the
 * `LeaveNotifier` port — the backend renames the device's manifest to its departed `.left.json` sibling and
 * reaps/GCs the event when the last member leaves). The `eventId` is snapshotted **synchronously before**
 * the clears (from [ConfigSource]) and passed into the notify, so the notify still targets the correct
 * event even though the config is already gone.
 *
 * **Leaving clears the upload ledger** — a deliberate reversal of the rule that no lifecycle transition
 * destroys dedup state (capabilities `sync-ledger`, `upload-lifecycle`). The ledger is the current
 * membership's share set: a later join rebuilds it from the device's stored-file listing, so nothing already
 * stored is uploaded again unless that listing fails (then the cost is idempotent re-uploads, bounded by
 * the next event's window). Only the **upload** ledger is cleared: the download store's handle-carrying rows
 * are what stop this device uploading its own imports back into an event, and nothing here touches them
 * (capability `download-store`).
 *
 * Stop comes first so no mechanism starts new work against rows about to vanish. A transfer already in
 * flight may still complete after the clear: its outcome finds no row, is acknowledged and discarded, and
 * the bytes it landed are on the backend with no row — which the next join's listing seeds `COMPLETED`.
 * Nothing is lost and nothing loops.
 *
 * **The local teardown never waits on the network.** The clears are awaited, so [ConfigSource] goes
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
 * anyway). If [ConfigStore.clear] fails, the event is still configured — the user is simply still joined,
 * the producer stopped until the next start, with an empty ledger whose cost is an idempotent re-upload. The
 * notify is dispatched **unconditionally** after the clears — a failed clear does not suppress it.
 *
 * The leave deliberately **keeps** the device-manifest record: nothing about the server's copy changed
 * here, so the belief is still true. It is the *re-join*'s enrollment that falsifies it, and that is where
 * it is cleared ([ManifestDeviceEnroller]).
 */
class LeaveEvent(
    private val config: ConfigStore,
    private val configSource: ConfigSource,
    private val stopUploads: suspend () -> Unit,
    /** Clear the upload ledger — the store's reset family, callable without the `LedgerWriter`. */
    private val clearLedger: suspend () -> Unit,
    private val notifyLeave: suspend (eventId: String) -> Unit,
    private val scope: CoroutineScope,
) {
    private val steps = Steps(Logger.withTag("LeaveEvent"), "leave")

    suspend fun leave() {
        // Snapshot the eventId synchronously BEFORE the clears so the backgrounded notify targets the
        // right event even though the config is gone by the time it runs (no race on the cleared cell).
        val eventId = configSource.config.value?.eventId
        steps.bestEffort("stop uploads") { stopUploads() }
        steps.bestEffort("clear upload ledger") { clearLedger() }
        steps.bestEffort("clear config") { config.clear() }
        // Fire-and-forget on the app-lifetime scope: the local teardown (and thus the screen flip) never
        // waits on the DELETE. Dispatched unconditionally after the clears (a failed clear does not gate it).
        if (eventId != null) {
            scope.launch { steps.bestEffort("notify backend") { notifyLeave(eventId) } }
        }
    }

}
