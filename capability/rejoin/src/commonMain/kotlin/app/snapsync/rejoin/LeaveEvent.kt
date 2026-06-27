package app.snapsync.rejoin

import app.snapsync.config.ConfigStore
import app.snapsync.engine.LedgerBackend
import app.snapsync.eventstatus.EventStatus
import app.snapsync.eventstatus.MutableEventStatusSource
import co.touchlab.kermit.Logger

/**
 * The leave use-case: the inverse of [JoinEvent]. Tears down the configured event's **local** state
 * and returns to [EventStatus.Idle], leaving every already-uploaded object in storage untouched (a
 * later re-scan re-joins and reconciles them back — see `leave-event`).
 *
 * The order is **disable-first** (mirroring the enable gate): disable the producer before the ledger
 * is reset so there is never a concurrent ledger writer during the reset. The platform side-effects —
 * disabling the producer and clearing the discovery cursor — arrive as injected suspend lambdas (as
 * [JoinEvent] takes `clearDiscoveryCursor`), so this stays pure `commonMain` logic and constructs no
 * `LedgerWriter` (the wipe rides [LedgerBackend.resetTo], a reset-family op).
 *
 * **Best-effort, no rollback:** each step runs independently; a failing step is logged and the rest
 * still run. The order is chosen so the worst partial outcome self-heals — if [ConfigStore.clear]
 * fails after the wipe, the event is still configured against an empty ledger, so the next launch's
 * join gate simply re-joins it.
 */
class LeaveEvent(
    private val config: ConfigStore,
    private val ledger: LedgerBackend,
    private val status: MutableEventStatusSource,
    private val disableExtension: suspend () -> Unit,
    private val clearDiscoveryCursor: suspend () -> Unit,
) {
    private val log = Logger.withTag("LeaveEvent")

    suspend fun leave() {
        step("disable producer") { disableExtension() }
        step("reset ledger") { ledger.resetTo(emptyList()) }
        step("clear discovery cursor") { clearDiscoveryCursor() }
        step("clear config") { config.clear() }
        status.set(EventStatus.Idle)
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
