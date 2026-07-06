package app.snapsync.membership

import app.snapsync.config.ConfigStore
import co.touchlab.kermit.Logger

/**
 * The leave use-case: tears down the configured event's **local** state, best-effort, leaving every
 * already-uploaded object in storage untouched (a later re-join reconciles them back — see
 * `leave-event`).
 *
 * It does three things, in order: (1) **disable** the background-upload producer, (2) **notify the
 * backend** this device is leaving (via [LeaveNotifier] — the backend renames the device's manifest to
 * its departed `.left.json` sibling and reaps/GCs the event when the last member leaves), then (3)
 * **clear the persisted config**. It never touches the ledger, the discovery cursor, or any join
 * marker — reconciliation now lives in the extension (see [ExtensionReconciler]), which resets its own
 * private ledger, cursor, and `joinedEventId` marker on its next cycle once the configured event no
 * longer matches the marker (or no event is configured at all). The producer is disabled **before**
 * the backend notify and the config clear so no producer work races the teardown, and the notify runs
 * **before** the clear because it needs the still-configured `eventId`.
 *
 * The platform side-effects — disabling the producer and the backend notify — are injected as suspend
 * lambdas, so this stays pure `commonMain` logic and the app shell stays wiring-only; the use-case
 * constructs no ledger type.
 *
 * **Best-effort, no rollback:** each step runs independently; a failing step is logged and the rest
 * still run. The order is chosen so the worst partial outcome self-heals — a failed backend notify
 * never aborts the local teardown (the device still leaves; the un-removed backend membership is the
 * accepted abandon-leak), and if [ConfigStore.clear] fails after the disable, the event is still
 * configured (the user is simply still joined, the producer disabled until the next enable) rather
 * than a half-torn-down state. A stale private ledger left in the extension is reset on its next join
 * via the marker mismatch, not at leave time.
 */
class LeaveEvent(
    private val config: ConfigStore,
    private val disableExtension: suspend () -> Unit,
    private val notifyLeave: suspend () -> Unit,
) {
    private val log = Logger.withTag("LeaveEvent")

    suspend fun leave() {
        step("disable producer") { disableExtension() }
        step("notify backend") { notifyLeave() }
        step("clear config") { config.clear() }
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
