package app.snapsync.ports

import co.touchlab.kermit.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.time.Duration

/**
 * The OS completion handlers of **one** background `URLSession`, held from the handover until the events
 * it woke us for have been delivered *and* the work they feed has finished (capability `ios-app-shell`).
 *
 * "From the handover" is exact to within a dispatch: [adopt] enqueues onto [scope], and the deadline
 * starts when that coroutine RUNS, not when the OS called. Measured at 5-12 ms across ten field wakes,
 * against a 20 s bound — but it is not zero, and a [scope] blocked in a platform call would widen it
 * without bound. That is a known, deliberately-unaddressed caveat; its expiry trigger is the first dump
 * showing a wake-to-adopt gap above ~1 s.
 *
 * This is the one type licensed to hold an OS completion handler; a `:test:architecture` guard fails the
 * build on a mutable handler-shaped field anywhere else. The confinement — rather than a prohibition —
 * is deliberate: storing the handler is the platform's own recipe (*"You should then store that
 * completion handler before creating a background configuration object with the same identifier"*).
 * What a bare field cannot do is bound the hold or hold two, and those are the two properties this type
 * exists to make provable in one tested place.
 *
 * **Why this type exists at all, and the other three handlers do not need it.** The silent-push handler
 * and both `BGTask` handlers are answered inside the call that receives them, so [OsReceipt] alone
 * suffices. A background-`URLSession` handler is different in kind: it arrives at
 * `handleEventsForBackgroundURLSession`, and what it is waiting for — the session reporting its events
 * delivered — arrives later, on a different queue. That gap is what turned both of them into fields,
 * and the gap is exactly the interval in which the signal may never come.
 *
 * **The window.** [adopt] joins the *current* window; [drained] closes it, installs a fresh one, runs
 * [work], and releases every handler that was in the closed window. A handler adopted after a drain
 * therefore waits for the next one, which is correct — its own wake's events have not been delivered
 * yet.
 *
 * ⚠️ **The multi-handler case carries no forcing proof, and is not claimed to.** No Apple document and
 * no measurement of ours says a second `handleEventsForBackgroundURLSession` can arrive for one session
 * before the first is released; Apple's own sample stores a single handler and would overwrite it. It is
 * represented here because a single slot *cannot* represent it — the earlier handler is dropped and
 * never called, which costs the app its future background wakes — and because the cost of representing
 * it is one deferred rather than one reference. Expiry trigger: a field dump showing two adopts inside
 * one window, or an Apple statement that the delivery is serialized; either settles it and this KDoc
 * should then say which.
 *
 * **State is confined to [scope], which MUST be serial.** Both entry points are called from threads we do
 * not choose — [adopt] from the main thread via the app delegate, [drained] from a session-owned queue —
 * so neither touches [window] directly; each hands its work to [scope]. [window] is a plain non-atomic
 * `var`, and that is only safe because every composition injects a **single-threaded** scope: the device
 * shell's composition lane, the harness's `newSingleThreadContext`, the tests' scheduler. A
 * multi-threaded scope would race it. Stated here because the constructor accepts any [CoroutineScope]
 * and the compiler cannot express the requirement.
 */
class BackgroundEventsReceipts(
    private val scope: CoroutineScope,
    /** The entry point these handlers belong to, for the diagnostic line and the receipt's expiry log. */
    private val entryPoint: String,
    /** How long any one handler may be held, measured from **its own** handover. */
    private val deadline: Duration,
    /**
     * What this session's drain feeds — the upload pump's cycle, or the download arm's outstanding
     * imports. Runs on [drained], whether or not any handler is outstanding: a foreground drain must
     * still do the work, it simply has nobody waiting on it.
     */
    private val work: suspend () -> Unit,
    /** Where each handler is released. See [OsReceipt.releaseLane] — UIKit requires main for these. */
    private val releaseLane: CoroutineContext = EmptyCoroutineContext,
    private val log: Logger = Logger.withTag("BackgroundEventsReceipts"),
) {

    /** Completed when the events outstanding at its creation have drained and [work] has finished. */
    private var window = CompletableDeferred<Unit>()

    /**
     * Serialises [work] runs across overlapping drains. Not redundant with the single lane: coroutines
     * interleave at every suspension point, and the download arm's work is a **destructive** read — it
     * takes the outstanding-import list and clears it — so a second drain overlapping the first would
     * find the list empty and release its handlers against imports that are still running.
     *
     * ⚠️ **The cost, stated:** [work] is NOT bounded — the deadline bounds the handler hold, never this —
     * so a stuck [work] parks every later drain here behind it. On downloads that is reachable in normal
     * operation: an import's own deadline is 30 s, longer than this session's 20 s handler budget, so a
     * slow batch outlives the receipt that announced it. Handlers still release on their deadlines, so
     * the visible symptom is a run of expiry lines and no drain work, not a stall. Accepted because the
     * alternative — independent drains, which is what the download side used to do — is what lets a
     * second drain release its handlers against the first's unfinished imports.
     */
    private val running = Mutex()

    /**
     * The OS handed over a completion handler for this session. Returns immediately; the handler is held
     * on [scope] until this window drains or the deadline expires.
     */
    fun adopt(handler: () -> Unit) {
        // `ATOMIC`: the body runs even if [scope] is already cancelled. A plain `launch` would simply
        // never run it, and the handler would be dropped with no release and no line — an entry point
        // collapsing into silence, which is the exact failure this type exists to prevent. Started
        // atomically, the wait below throws cancellation immediately and [OsReceipt]'s `finally` still
        // releases (its release is `NonCancellable`), so a dead scope costs promptness, not the handler.
        scope.launch(start = CoroutineStart.ATOMIC) {
            val awaited = window
            OsReceipt(
                entryPoint = entryPoint,
                // `INFINITE` deliberately — **the bound is applied below**, to the await itself.
                //
                // [OsReceipt]'s own deadline releases the handler and then, by design, leaves its block
                // running: right when the block is real work already in flight, wrong here, where the
                // block is a pure wait for a signal that may never arrive. Left running it parks one
                // coroutine per wake until the next drain, or forever if none comes. Cancelling *this*
                // wait abandons nothing — the work a drain feeds runs in [drained]'s own coroutine, not
                // in this one — so the bound belongs where it can also end the wait.
                //
                // Two mechanics if you touch this: on a real dispatcher `INFINITE` registers no timeout
                // at all, but under `TestCoroutineScheduler` it IS an event at a clamped `Long.MAX_VALUE`
                // — disposed by the inner timeout below, so a test that adopts, never drains, and then
                // calls `advanceUntilIdle()` would otherwise jump virtual time to the end of the world.
                deadline = Duration.INFINITE,
                release = handler,
                log = log,
                releaseLane = releaseLane,
            ).heldFor {
                if (withTimeoutOrNull(deadline) { awaited.await() } == null) {
                    // Logged, never silent (capability `diagnostic-logging`): this is the only evidence
                    // that a wake's session never reported, and it is the stated trigger for revisiting
                    // the constant.
                    //
                    // ⚠️ The line's opening clause — up to and including the deadline — is LOAD-BEARING
                    // and pinned by a guard (capability `architecture-guards`, "The OS-receipt expiry line
                    // is pinned"); it is quoted there, not here, because that guard also requires this file
                    // to contain it exactly once. The rig's consumers read the line's presence as "the
                    // bound engaged" and its ABSENCE as "the work finished", and for these two handlers
                    // this is the ONLY emitter: [OsReceipt]'s own cannot fire, its deadline being INFINITE
                    // by construction above. Reword the prose after the dash freely; leave the opening
                    // clause alone.
                    log.w {
                        "$entryPoint: OS handler released on its $deadline deadline — " +
                            "the session never reported its events drained"
                    }
                }
            }
        }
    }

    /** The session reported every event delivered. Run the work it feeds, then release what was held. */
    fun drained() {
        scope.launch {
            val closing = window
            window = CompletableDeferred()
            try {
                running.withLock { work() }
            } catch (t: Throwable) {
                // The release does NOT depend on this catch — `finally` below completes the window on
                // every path, thrown or not. What the catch buys is the entry point in the message: the
                // scope's handler would log the same throwable with no idea which wake's work it was,
                // and this is the one line tying a failed drain to the session that triggered it.
                log.w(t) { "$entryPoint: the work a drain feeds failed — its handlers are released anyway" }
            } finally {
                closing.complete(Unit)
            }
        }
    }
}
