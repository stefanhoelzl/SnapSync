package app.snapsync.feature.membership

import app.snapsync.ports.ConfigSource
import app.snapsync.ports.ConfigStore
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The leave use-case: tears down the configured event's **local** state, best-effort, leaving every
 * already-uploaded object in storage untouched (a later re-join reconciles them back — see
 * `leave-event`).
 *
 * It does three things, in order: (1) **stop** the upload producer, (2) **clear the persisted config**,
 * then (3) **notify the backend** this device is leaving (via [HttpLeaveNotifier] — the backend renames the
 * device's manifest to its departed `.left.json` sibling and reaps/GCs the event when the last member
 * leaves). The `eventId` is snapshotted **synchronously before** the clear (from [ConfigSource]) and
 * passed into the notify, so the notify still targets the correct event even though the config is
 * already gone.
 *
 * **Leaving destroys no dedup state.** [stopUploads] is the `stop()` half of the `UploadProducer` seam
 * (capability `upload-lifecycle`): it cancels in-flight work and nothing else. The ledger, the discovery
 * cursor, and the device-manifest accumulator are **kept** — the ledger key is the bare filename with no
 * event scoping, and leaving an event does not remove this device's bytes from its storage partition, so
 * a `COMPLETED` row stays *true* across a leave (`sync-ledger`, "Event-independent key"). Wiping them
 * would force a re-upload of everything already stored on the next join, which is exactly what the
 * app-driven tier used to do here. The upload tier clears only the `joinedEventId` marker, on its next
 * cycle, once the configured event no longer matches (see [ExtensionReconciler]); a later join of *any*
 * event then reconciles fresh and re-uploads nothing already in the byte partition.
 *
 * **The local teardown never waits on the network.** The clear is the second, awaited step, so
 * [ConfigSource] goes `null` — and the screen leaves the joined layer — the instant the local state is
 * torn down. The backend notify is then dispatched **fire-and-forget** on the injected app-lifetime
 * [scope] (which outlives the screen transition), so a slow or hung `DELETE` can never freeze the
 * screen after the user confirms "Leave". The same holds on the switch path (the departed event's
 * `DELETE` never delays the new event's join).
 *
 * The platform side-effects — stopping the producer and the backend notify — are injected as suspend
 * lambdas (the notify as `suspend (eventId) -> Unit`), so this stays pure `commonMain` logic and the
 * app shell stays wiring-only; the use-case constructs no ledger type.
 *
 * **Best-effort, no rollback:** each step runs independently; a failing step is logged and the rest
 * still run. The order is chosen so the worst partial outcome self-heals — a failed backend notify
 * never aborts the local teardown (the device still leaves; the un-removed backend membership is the
 * accepted abandon-leak), and if [ConfigStore.clear] fails after the stop, the event is still
 * configured (the user is simply still joined, the producer stopped until the next start) rather
 * than a half-torn-down state. The notify is dispatched **unconditionally** after the clear step — a
 * failed clear does not suppress it (the resulting transient "backend told, still joined locally"
 * self-heals when the producer restarts and re-writes the manifest). A stale join marker is cleared on
 * the upload tier's next cycle via the marker mismatch, not at leave time.
 */
class LeaveEvent(
    private val config: ConfigStore,
    private val configSource: ConfigSource,
    private val stopUploads: suspend () -> Unit,
    private val notifyLeave: suspend (eventId: String) -> Unit,
    private val scope: CoroutineScope,
) {
    private val log = Logger.withTag("LeaveEvent")

    suspend fun leave() {
        // Snapshot the eventId synchronously BEFORE the clear so the backgrounded notify targets the
        // right event even though the config is gone by the time it runs (no race on the cleared cell).
        val eventId = configSource.config.value?.eventId
        step("stop uploads") { stopUploads() }
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
