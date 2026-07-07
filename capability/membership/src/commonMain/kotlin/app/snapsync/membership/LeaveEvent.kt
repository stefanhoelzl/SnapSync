package app.snapsync.membership

import app.snapsync.config.ConfigSource
import app.snapsync.config.ConfigStore
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The leave use-case: tears down the configured event's **local** state, best-effort, leaving every
 * already-uploaded object in storage untouched (a later re-join reconciles them back — see
 * `leave-event`).
 *
 * It does three things, in order: (1) **disable** the background-upload producer, (2) **clear the
 * persisted config**, then (3) **notify the backend** this device is leaving (via [LeaveNotifier] —
 * the backend renames the device's manifest to its departed `.left.json` sibling and reaps/GCs the
 * event when the last member leaves). The `eventId` is snapshotted **synchronously before** the clear
 * (from [ConfigSource]) and passed into the notify, so the notify still targets the correct event even
 * though the config is already gone. It never touches the ledger, the discovery cursor, or any join
 * marker — reconciliation now lives in the extension (see [ExtensionReconciler]), which resets its own
 * private ledger, cursor, and `joinedEventId` marker on its next cycle once the configured event no
 * longer matches the marker (or no event is configured at all).
 *
 * **The local teardown never waits on the network.** The clear is the second, awaited step, so
 * [ConfigSource] goes `null` — and the screen leaves the joined layer — the instant the local state is
 * torn down. The backend notify is then dispatched **fire-and-forget** on the injected app-lifetime
 * [scope] (which outlives the screen transition), so a slow or hung `DELETE` can never freeze the
 * screen after the user confirms "Leave". The same holds on the switch path (the departed event's
 * `DELETE` never delays the new event's join).
 *
 * The platform side-effects — disabling the producer and the backend notify — are injected as suspend
 * lambdas (the notify as `suspend (eventId) -> Unit`), so this stays pure `commonMain` logic and the
 * app shell stays wiring-only; the use-case constructs no ledger type.
 *
 * **Best-effort, no rollback:** each step runs independently; a failing step is logged and the rest
 * still run. The order is chosen so the worst partial outcome self-heals — a failed backend notify
 * never aborts the local teardown (the device still leaves; the un-removed backend membership is the
 * accepted abandon-leak), and if [ConfigStore.clear] fails after the disable, the event is still
 * configured (the user is simply still joined, the producer disabled until the next enable) rather
 * than a half-torn-down state. The notify is dispatched **unconditionally** after the clear step — a
 * failed clear does not suppress it (the resulting transient "backend told, still joined locally"
 * self-heals when the producer re-enables and re-writes the manifest). A stale private ledger left in
 * the extension is reset on its next join via the marker mismatch, not at leave time.
 */
class LeaveEvent(
    private val config: ConfigStore,
    private val configSource: ConfigSource,
    private val disableExtension: suspend () -> Unit,
    private val notifyLeave: suspend (eventId: String) -> Unit,
    private val scope: CoroutineScope,
) {
    private val log = Logger.withTag("LeaveEvent")

    suspend fun leave() {
        // Snapshot the eventId synchronously BEFORE the clear so the backgrounded notify targets the
        // right event even though the config is gone by the time it runs (no race on the cleared cell).
        val eventId = configSource.config.value?.eventId
        step("disable producer") { disableExtension() }
        step("clear config") { config.clear() }
        // Fire-and-forget on the app-lifetime scope: the local teardown (and thus the screen flip) never
        // waits on the DELETE. Dispatched unconditionally after the clear (a failed clear does not gate it).
        if (eventId != null) {
            scope.launch { step("notify backend") { notifyLeave(eventId) } }
        }
    }

    private inline fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            // Best-effort: a failed step never aborts the leave (the order self-heals; see the class doc).
            log.e(e) { "leave step failed: $name" }
        }
    }
}
