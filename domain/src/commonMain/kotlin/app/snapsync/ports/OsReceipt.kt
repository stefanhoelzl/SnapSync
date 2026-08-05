package app.snapsync.ports

import co.touchlab.kermit.Logger
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * An **OS-supplied completion handler**, held until the work its wake triggered is done
 * (capability `ios-app-shell`).
 *
 * iOS hands one of these to every background wake — `handleEventsForBackgroundURLSession`,
 * `BGTask.setTaskCompleted`, the silent-push fetch handler. Calling it declares *"I am done"*, and the
 * system may suspend the process on the strength of that. Every one of them used to be called within
 * tens of milliseconds while the work it announced had only been queued, so the app volunteered to be
 * frozen mid-flight: measured in SNAPSYNC-6, `← onSilentPush (18ms)` against 41 s of real work, and a
 * five-photo import batch suspended ~1 s into a ~30 s budget with one photo never imported.
 *
 * **The type is the guard.** [heldFor] is the only way to release, and it takes the work as a `suspend`
 * block — so "release before the work" is not expressible at a call site, rather than merely discouraged.
 * It lives in `ports/` beside [invocation] (the same cross-cutting shape, over the same kind of injected
 * seam) rather than in the shell, because `:app:*` Kotlin is wiring-only and untested by rule: behaviour
 * placed there could not be covered, and a deadline is a decision the shell gates forbid it from making.
 *
 * **The deadline bounds the hold, never the work.** On expiry the handler is released and the work is
 * left running — deliberately not cancelled. That makes the worst case identical to the old behaviour
 * (a handler released while work continues) and never worse, which is what lets a receipt be held at all
 * without a stall turning into a termination. Cancelling instead would abandon work mid-flight in a
 * process that is about to be suspended anyway, which is the failure this whole capability removes.
 *
 * Bounding a single unit of work is a *different* job with a different owner — see the import deadline
 * in `photo-download`, which exists so a hung import cannot hold the download controller's lock forever.
 */
class OsReceipt(
    /** The entry point this handler belongs to, for the diagnostic line. */
    private val entryPoint: String,
    /** How long the handler may be held before it is released regardless (capability `ios-app-shell`). */
    private val deadline: Duration,
    /** The raw OS handler. Invoked at most once, on every path including a throw. */
    private val release: () -> Unit,
    private val log: Logger = Logger.withTag("OsReceipt"),
) {

    /**
     * Not atomic, and it does not need to be: both release sites below run in the SAME coroutine — the
     * in-scope call and the `finally` — so they cannot interleave. Making it atomic would imply a
     * sharing story that does not exist; a receipt belongs to exactly one wake.
     */
    private var released = false

    /**
     * Run [work], then release the handler. If [deadline] expires first the handler is released early
     * and [work] keeps running; this call still returns only when [work] does, so a caller cannot
     * mistake the release for completion.
     */
    suspend fun heldFor(work: suspend () -> Unit) {
        try {
            coroutineScope {
                val job = launch { work() }
                if (withTimeoutOrNull(deadline) { job.join() } == null) {
                    // Logged, never silent (capability `diagnostic-logging`): a bound that fires
                    // invisibly is indistinguishable from work that finished, and this line is the
                    // only evidence that the mechanism protecting the app actually engaged.
                    log.w { "$entryPoint: OS handler released on its $deadline deadline — its work is still running" }
                }
                releaseOnce()
                // `coroutineScope` now awaits `job`: the receipt was let go, the work was not.
            }
        } finally {
            // Structurally, on EVERY path including a throw out of `work` — an unanswered handler
            // costs the app its future background wakes, which is a worse failure than whatever threw.
            releaseOnce()
        }
    }

    private fun releaseOnce() {
        if (released) return
        released = true
        release()
    }
}

/**
 * How long each OS entry point may hold its handler (capability `ios-app-shell`).
 *
 * **Provisional.** The ~30 s budgets for a silent push and a background-`URLSession` wake are commonly
 * cited rather than documented exactly, and every held span measured so far is ~1.5 s typical — so these
 * carry ~13× headroom on the typical case and are meant to be re-set from the first field dump that
 * shows [OsReceipt]'s expiry line, not defended as derived values.
 */
object ReceiptDeadlines {
    /** A `content-available` push: answered with real margin inside the commonly-cited ~30 s. */
    val SILENT_PUSH: Duration = 20.seconds

    /** A background-`URLSession` events wake — same budget shape as the push. */
    val URL_SESSION_EVENTS: Duration = 20.seconds

    /**
     * A `BGTask`. Generous on purpose: the download backstop is a `BGProcessingTask` and can
     * legitimately be granted minutes, so the OS's own `expirationHandler` is the authority and this
     * constant is only the backstop for its absence.
     */
    val BACKGROUND_TASK: Duration = 120.seconds
}
