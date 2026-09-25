@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.ports

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The **OS-supplied completion handlers** of one kind of wake, held across that wake's **own work** and released
 * exactly once (capability `sync-status`, "OS completion handlers are released only after their work completes";
 * decision record `changes/own-work-per-wake`, D1, D3 and D5).
 *
 * iOS hands one of these to every background wake — the silent-push fetch handler, a `BGTask`'s `setTaskCompleted`,
 * `handleEventsForBackgroundURLSession`'s handler. Calling it declares *"I am done"*, and the system may suspend the
 * process on the strength of it: released while the own work is merely queued, it freezes the process mid-flight;
 * held for work the event is not about, it spends a budget the platform polices (for a `URLSession` wake a
 * watchdog-backed assertion — overrun is a kill without warning).
 *
 * **This is the one type licensed to hold such a handler**, and a `:test:architecture` guard fails the build on a
 * handler-shaped mutable field anywhere else (`docs/architecture.md`, "OS completion handlers are held in
 * one type"). It replaces `OsReceipt` and `BackgroundEventsReceipts`, which bounded the hold with a deadline of the
 * app's own — the 20 s bound the field evidence condemned: it released on 45 % of download wakes and 66 % of
 * upload-session wakes on an iPhone XS, iOS suspended the app ≤ 0.4 s later, and import batches were cut off.
 *
 * **Two release paths, and neither is a clock.**
 *  - [releaseAfter] takes the own work as a `suspend` block and releases after it — on every path, a throw included —
 *    so "release before the own work" is not expressible at a call site. It releases every handler handed over
 *    before that work began (the **window**); one handed over meanwhile waits for the next — its own wake's events
 *    have not been delivered yet. Every outstanding handler is released and none is replaced: a single stored slot
 *    would overwrite an earlier handler, which is never called and costs the app its future background wakes.
 *  - [Handover.releaseOnExpiry] is the operating system's own "time is up" for the wake — a `BGTask`'s forwarded
 *    expiration handler, or the expiry of the background time the core took at the handover. It releases **at once**,
 *    on the thread the signal arrives on, and does not wait for the own work, which runs on until the process is
 *    suspended (every unit is a safe retry). That is Apple's recipe: end the task promptly, never let the watchdog
 *    decide.
 *
 * **Where the release runs.** [releaseLane] is where [releaseAfter] releases — the release only, never the work. A
 * background-`URLSession` handler must be called on the main thread (*"Because the provided completion handler is part
 * of UIKit, you must call it on your main thread"*), and its drain signal arrives on a session-owned queue, so the
 * lane is what puts it there. An expiry release runs on the signal's own thread, which for that handler is the main
 * thread already: the background-time port's expiry is `beginBackgroundTask`'s expiration handler, which UIKit calls
 * on main. No thread requirement is stated for the other two handlers, and none is extended to them.
 *
 * **Thread-safe by construction.** Handovers arrive on the main thread, drains on a session queue, expiries on the
 * operating system's queue: the outstanding set is one atomic reference replaced whole, and each handler's release is
 * a compare-and-set — so two paths racing to release one handler release it once.
 */
class OsCompletions(
    /** The entry point these handlers belong to, for the diagnostic lines. */
    private val entryPoint: String,
    /** Where [releaseAfter] releases — see the class KDoc. */
    private val releaseLane: CoroutineContext = EmptyCoroutineContext,
    private val log: Logger = Logger.withTag("OsCompletions"),
) {
    private val outstanding = AtomicReference<List<Handover>>(emptyList())

    /** The operating system handed over [handler]. Held until a [releaseAfter] that begins later, or an expiry. */
    fun adopt(handler: () -> Unit): Handover = Handover(handler).also { handover -> update { it + handover } }

    /**
     * Run [ownWork], then release every handler handed over before it began — after the work, on every path.
     * [ownWork] is the wake's own work and nothing else; what remains is the tail's, which runs under the process's
     * background time rather than under these handlers.
     */
    suspend fun releaseAfter(ownWork: suspend () -> Unit) {
        val window = outstanding.load()
        try {
            ownWork()
        } finally {
            // `NonCancellable`: a cancelled caller must still answer the operating system — an unanswered handler costs
            // the app its future background wakes, which is worse than whatever cancelled it.
            withContext(NonCancellable + releaseLane) { window.forEach { it.release(expired = null) } }
        }
    }

    private fun update(change: (List<Handover>) -> List<Handover>) {
        while (true) {
            val current = outstanding.load()
            if (outstanding.compareAndSet(current, change(current))) return
        }
    }

    /** One handler the operating system handed over, and the only way to answer it early. */
    inner class Handover internal constructor(private val handler: () -> Unit) {
        private val answered = AtomicBoolean(false)
        private val signal = CompletableDeferred<Unit>()

        /** Whether this handler has been released, by either path. */
        val isReleased: Boolean get() = answered.load()

        /** Suspends until this handler has been released, by either path. */
        suspend fun awaitRelease() = signal.await()

        /**
         * The operating system's time for this wake is up ([reason] names the signal): release now, on this thread,
         * without waiting for the own work. Does nothing when the handler was released already.
         */
        fun releaseOnExpiry(reason: String) {
            release(expired = reason)
        }

        internal fun release(expired: String?) {
            if (!answered.compareAndSet(expectedValue = false, newValue = true)) return
            update { held -> held - this }
            try {
                handler()
            } finally {
                signal.complete(Unit)
                // Logged, never silent (capability `privacy-security`): a release on the OS's signal is the only
                // evidence that the wake's own work did not finish inside the time the OS gave it.
                if (expired != null) {
                    log.w {
                        "$entryPoint: OS handler released on the operating system's expiry ($expired) — " +
                            "its own work had not finished"
                    }
                }
            }
        }
    }
}
