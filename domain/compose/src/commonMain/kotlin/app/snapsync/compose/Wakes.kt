@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.compose

import app.snapsync.feature.upload.TailRunner
import app.snapsync.feature.upload.TailTrigger
import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.BackgroundTime
import app.snapsync.ports.BackgroundTimeHold
import app.snapsync.ports.OsCompletions
import co.touchlab.kermit.Logger
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * One wake's hold on the process's **background time** (capability `ios-app-shell`, "OS completion handlers are
 * released only after their work completes"; spec `module-architecture`, "Background time is an outbound port named
 * for the need"; decision record `changes/own-work-per-wake`, D3 and D5).
 *
 * Begun **no later than the OS handler is handed over** — before the wake's own work starts — and held across that
 * work, any wait for a signal the handler's release depends on, and the tail the wake hands to; ended when that tail
 * ends. So there is no instant between the handover and the tail's end at which the process holds nothing, and a
 * signal that never comes ends in Apple's expiry rather than in a handler held forever. Background time is per app,
 * not per hold, so a hold per wake costs no time.
 *
 * **On Apple's expiry** — the hold's expiration handler, the only "time is up" a silent push or a transfer wake gets —
 * it requests the tail's stop, releases every OS handler it [guard]s, and ends the hold, **at once**: it never waits
 * for the unit in flight, which runs on until iOS suspends the process (every unit is a safe retry) while no new one
 * starts. Ending promptly is Apple's recipe, and it leaves no watchdog to decide instead. A wake whose hold has expired
 * — a refused begin included, which the port reports as an immediate expiry — requests no tail afterwards: a stop
 * while no tail runs is a no-op, so a tail requested after one would run with no time left to run in.
 */
internal class Wake(
    private val label: String,
    time: BackgroundTime,
    private val tail: TailRunner,
    private val log: Logger,
) {
    private val expired = AtomicBoolean(false)
    private val guarded = AtomicReference<List<OsCompletions.Handover>>(emptyList())
    private val hold = AtomicReference<BackgroundTimeHold?>(null)

    init {
        val granted = time.begin(label) { expire() }
        hold.store(granted)
        // The expiry may have fired inside `begin` (a refused hold), before there was a hold to end.
        if (expired.load()) granted.end()
    }

    /** Whether Apple has said this wake's time is up. */
    val hasExpired: Boolean get() = expired.load()

    /** Release [handover] on this wake's expiry too — at once, should the expiry already have come. */
    fun guard(handover: OsCompletions.Handover) {
        while (true) {
            val current = guarded.load()
            if (guarded.compareAndSet(current, current + handover)) break
        }
        if (expired.load()) handover.releaseOnExpiry(reason())
    }

    /**
     * Hand the rest to the tail as [trigger] — unless this wake's time is already up — and end the hold once that
     * tail has ended. A tail that fails is logged here; the hold is ended on every path.
     */
    suspend fun thenTail(trigger: TailTrigger) {
        try {
            if (expired.load()) {
                log.i { "$label: background time is already up — no tail requested" }
            } else {
                runCatchingCancellable { tail.request(trigger) }
                    .onFailure { log.w(it) { "$label: its tail ($trigger) failed" } }
            }
        } finally {
            end()
        }
    }

    /** End the hold with no tail — the wake had nothing to hand on. */
    fun end() {
        hold.load()?.end()
    }

    /** Apple's expiration handler: stop the tail, release what is guarded, end the hold — and return. */
    private fun expire() {
        if (!expired.compareAndSet(expectedValue = false, newValue = true)) return
        log.w { "OS expiry: ${reason()} — stopping the tail and ending the hold at once" }
        tail.stop(reason())
        guarded.load().forEach { it.releaseOnExpiry(reason()) }
        end()
    }

    private fun reason() = "background time for $label is up"
}

/**
 * The operating system's "time is up" signal for each background task the core is running, keyed by the identifier
 * it delivered (capability `ios-app-shell`, "Background tasks are forwarded by the identifier the OS delivered").
 *
 * A task's expiry action is opened when it is routed and closed when its work ends; [expire] runs the one open for an
 * identifier. The expiry arrives on a thread the core does not choose (the operating system calls the expiration
 * handler on its own queue, and it must be answered promptly), so the table is one atomic reference replaced whole
 * rather than state confined to the composition lane: a hop onto that lane could wait behind a blocking platform call.
 * The operating system runs at most one task per identifier, so an identifier maps to at most one action; a close
 * removes only its own, so a later run of the same task is never closed by an earlier one's end.
 */
internal class TaskExpiries {
    /** One task's expiry action — a class rather than a bare function so a close can compare identities. */
    class Open internal constructor(val onExpiry: () -> Unit)

    private val running = AtomicReference<Map<String, Open>>(emptyMap())

    /** Opens the expiry action for a task now running as [identifier]. */
    fun open(identifier: String, onExpiry: () -> Unit): Open =
        Open(onExpiry).also { open -> replace { it + (identifier to open) } }

    /** Closes [open], if it is still the one open for [identifier]. */
    fun close(identifier: String, open: Open) = replace { if (it[identifier] === open) it - identifier else it }

    /** Runs the expiry action of the task running as [identifier]; `false` when none is. */
    fun expire(identifier: String): Boolean = running.load()[identifier]?.also { it.onExpiry() } != null

    private fun replace(change: (Map<String, Open>) -> Map<String, Open>) {
        while (true) {
            val current = running.load()
            if (running.compareAndSet(current, change(current))) return
        }
    }
}
