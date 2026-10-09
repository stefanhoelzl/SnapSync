package app.snapsync.services.wake

import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.BackgroundTime
import app.snapsync.ports.BackgroundTimeHold
import co.touchlab.kermit.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * One wake's hold on the process's **background time** — `WakeHold`, not `Wake`, since `Wake` is the port the
 * operating system's scheduled wakes arrive through (OS completion handlers are
 * released only after their work completes; `docs/architecture.md`, "Background time is an outbound port named
 * for the need"; decision record `changes/own-work-per-wake`, D3 and D5).
 *
 * Begun **no later than the OS handler is handed over** — before the wake's own work starts — and held across that
 * work, any wait for a signal the handler's release depends on, and the tail the wake hands to; ended when that tail
 * ends. So there is no instant between the handover and the tail's end at which the process holds nothing, and a
 * signal that never comes ends in Apple's expiry rather than in a handler held forever. Background time is per app,
 * not per hold, so a hold per wake costs no time.
 *
 * A tail requested **in-process** — a membership transition's arm, an upload completion's top-up, a staged download's
 * import, a selection change — holds one too, from the request to the tail's end (phase 11f): free on iOS, and it is
 * what keeps a process the app just left, or an Android worker's top-up, from being frozen mid-unit.
 *
 * **On Apple's expiry** — the hold's expiration handler, the only "time is up" a silent push or a transfer wake gets —
 * it requests the tail's stop ([stopTail]), releases every OS handler it [guard]s, and ends the hold, **at once**: it
 * never waits for the unit in flight, which runs on until iOS suspends the process (every unit is a safe retry) while
 * no new one starts. Ending promptly is Apple's recipe, and it leaves no watchdog to decide instead. A wake whose hold
 * has expired — a refused begin included, which the port reports as an immediate expiry — requests no tail
 * afterwards: a stop while no tail runs is a no-op, so a tail requested after one would run with no time left to run
 * in.
 *
 * The expiry and a [guard] may race on two threads, and neither order drops a handover: a guard records the handover
 * before it looks at the expiry, and the expiry is marked before it reads what is guarded — so at least one of the two
 * releases it, and a handover's release is idempotent ([OsCompletions.Handover.releaseOnExpiry]).
 */
@OptIn(ExperimentalAtomicApi::class)
class WakeHold(
    private val label: String,
    time: BackgroundTime,
    /** Ask the running tail to stop, with the reason its log line carries. */
    private val stopTail: (reason: String) -> Unit,
    private val log: Logger,
    /**
     * The last act before the hold ends — the moment iOS may suspend the process — handed the wake's label: where the
     * app reads its own memory footprint.
     */
    private val settling: (label: String) -> Unit,
) {
    private val expired = AtomicBoolean(false)
    private val guarded = MutableStateFlow<List<OsCompletions.Handover>>(emptyList())

    /** The granted hold — `null` only while `begin` runs, when a refused begin may already have fired the expiry. */
    private val hold = MutableStateFlow<BackgroundTimeHold?>(null)

    init {
        val granted = time.begin(label) { expire() }
        hold.value = granted
        // The expiry may have fired inside `begin` (a refused hold), before there was a hold to end.
        if (expired.load()) granted.end()
    }

    /** Release [handover] on this wake's expiry too — at once, should the expiry already have come. */
    fun guard(handover: OsCompletions.Handover) {
        guarded.update { it + handover }
        if (expired.load()) handover.releaseOnExpiry(reason())
    }

    /**
     * Hand the rest to [tail] — unless this wake's time is already up — then run [finish] while the time is still not
     * up, and end the hold once both have ended. A tail ([description] names it) or a step that fails is logged here;
     * the hold is ended on every path.
     */
    suspend fun thenTail(description: String, tail: suspend () -> Unit, finish: suspend () -> Unit) {
        try {
            if (expired.load()) {
                log.i { "$label: background time is already up — no tail requested" }
            } else {
                runCatchingCancellable { tail() }.onFailure { log.w(it) { "$label: its tail ($description) failed" } }
                finishUnlessExpired(finish)
            }
        } finally {
            try {
                settling(label)
            } finally {
                end()
            }
        }
    }

    private suspend fun finishUnlessExpired(finish: suspend () -> Unit) {
        if (expired.load()) return
        runCatchingCancellable { finish() }
            .onFailure { log.w(it) { "$label: the end-of-wake step failed; the next wake runs it again" } }
    }

    /** End the hold with no tail — the wake had nothing to hand on. */
    fun end() {
        hold.value?.end()
    }

    /** Apple's expiration handler: stop the tail, release what is guarded, end the hold — and return. */
    private fun expire() {
        if (!expired.compareAndSet(expectedValue = false, newValue = true)) return
        log.w { "OS expiry: ${reason()} — stopping the tail and ending the hold at once" }
        stopTail(reason())
        guarded.value.forEach { it.releaseOnExpiry(reason()) }
        end()
    }

    private fun reason() = "background time for $label is up"
}
