@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.feature.upload

import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.BackgroundScheduler
import app.snapsync.ports.CycleResult
import app.snapsync.ports.LogScope
import app.snapsync.ports.invocation
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.concurrent.Volatile
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** How much of the tail a request needs. Ordered: a larger scope covers a smaller one. */
enum class TailScope {
    /** ② alone — what a freed upload slot needs. */
    TOP_UP,

    /** ① import → ② top-up → ③ walk → manifest (full grant only) → ② again when ③ added rows. */
    FULL,
}

/** When a trigger schedules the next heartbeat after its tail, subject always to the `SKIPPED` rule. */
enum class Rearm {
    /** Whatever the tail left, unless it declined. */
    ALWAYS,

    /** Only when work remains ([CycleResult.PROCESSING]). */
    WHEN_WORK_REMAINS,

    /** Never — completions re-request the top-up while the app is open. */
    NEVER,
}

/**
 * The wakes that request the tail, each with the part of it it needs and its re-arm policy (capability
 * `ios-url-session-upload`, "The tail runner reimplements the OS scheduler"; decision record
 * `changes/own-work-per-wake`, D1 and D2). Every trigger's own work has already run, outside the runner, before it
 * requests.
 */
enum class TailTrigger(val scope: TailScope, val rearm: Rearm) {
    /** A membership transition or a launch armed the engine — the only trigger that arms the FIRST heartbeat. */
    ARM(TailScope.FULL, Rearm.ALWAYS),

    /** Foreground entry: a force-quit cancelled every pending heartbeat, and reopening is when it can be restored. */
    FOREGROUND(TailScope.FULL, Rearm.ALWAYS),

    /** A silent push for the active event — the reliable wake, clustered exactly when an event is live. */
    SILENT_PUSH(TailScope.FULL, Rearm.ALWAYS),

    /** The selection changed under a partial grant: the member just acted, and the chain may be severed. */
    SELECTION_CHANGE(TailScope.FULL, Rearm.ALWAYS),

    /** The heartbeat `BGTask`: one-shot, so its own re-submission is what keeps it alive. */
    HEARTBEAT(TailScope.FULL, Rearm.ALWAYS),

    /** A background upload-session relaunch: the drain continues itself, so re-arm only on remaining work. */
    UPLOAD_SESSION_EVENTS(TailScope.FULL, Rearm.WHEN_WORK_REMAINS),

    /**
     * A background download-session relaunch. The re-arm table names no policy for it; it is given the upload
     * relaunch's — a background wake whose tail left upload work re-arms the heartbeat, one that finished does not.
     */
    DOWNLOAD_SESSION_EVENTS(TailScope.FULL, Rearm.WHEN_WORK_REMAINS),

    /** An upload finished and freed a slot: the top-up alone, and only while the app may create. */
    UPLOAD_COMPLETED(TailScope.TOP_UP, Rearm.NEVER),
}

/** What the tail's walk unit (③: discovery walk → manifest publish) reports. */
sealed interface WalkOutcome {
    /** The walk completed and was decided: its upload [result], and whether it recorded new rows. */
    data class Walked(val result: CycleResult, val addedRows: Boolean) : WalkOutcome

    /** A stop arrived while the walk ran: it decided nothing and wrote nothing, and the next tail walks in full. */
    data object Abandoned : WalkOutcome
}

/**
 * How a tail ended: its upload units' [result] for the re-arm decision, and whether a stop [cut] it short (in which
 * case [result] is `PROCESSING`, unless the units had already declined with `SKIPPED`).
 */
data class TailOutcome(val result: CycleResult, val cut: Boolean)

/**
 * The app process's **opportunistic tail**: one process-wide, single-flight runner of the work every OS wake leaves
 * after its own (capability `ios-app-shell`, "Each OS wake does its own work, then hands the rest to one
 * opportunistic tail"; `ios-url-session-upload`, "The tail runner reimplements the OS scheduler"; decision record
 * `changes/own-work-per-wake`). It is the successor of [BackgroundUploadPump], and carries over every one of its
 * rules.
 *
 * **The units, in cost order.** A [TailScope.FULL] pass runs ① [importStaged] (staged bytes are already paid for and
 * are what the member sees), ② [topUp] (re-create retry-spent failures, enqueue known `DISCOVERED` rows), and — only
 * while [walkPermitted], i.e. under a full grant — ③ [walkAndPublish], the one costly, unbounded step; when ③ added
 * rows, ② runs once more so they get jobs in the same tail. A [TailScope.TOP_UP] pass runs ② alone: a freed slot
 * changes nothing the walk or the manifest would see (design D2). The units are injected: this runner knows their
 * order and their stop, never their internals (① is the download arm's, which this feature may not name).
 *
 * **Single-flight, and joining keeps its obligations.** At most one tail runs; the ledger's writer family would
 * double-write otherwise. A request arriving while one runs **joins** it: the running tail makes exactly one more
 * pass, at the largest scope any joiner needs, however many joined — no request is lost, no second tail starts,
 * nothing is queued. The joiner awaits that tail, **including** the pass it requested, and then applies **its own**
 * trigger's re-arm to the tail's outcome (a caller that returned at once could not be what a `BGTask` is held for,
 * and would skip its one-shot re-submission — both measured in the field under the pump). The decision to end and
 * the clearing of the running state are one step under [mutex], so a request can never slip between them.
 *
 * **Cooperative stop.** [stop] is Apple's "time is up", forwarded; it only sets a flag and returns. The unit in
 * flight completes — every unit receives `stopRequested` and stops at its own next boundary (between two imports,
 * two job creations) — and no further unit or pass starts. ③ must **abandon** on a stop rather than decide on a
 * partial enumeration: a partial walk is never authoritative. A tail a stop cut short reports `PROCESSING` — a stop
 * says nothing about whether work remains, so the only safe reading is that it does — unless the units had already
 * declined with `SKIPPED`. The stop consumes any pending pass; its joiners receive the cut outcome.
 *
 * **Rules carried over from the pump.** `PROCESSING` never busy-loops: a truncated ② is not re-run for that reason
 * alone (a completion or the heartbeat re-requests it); the ③ → ② loop runs only because ③ recorded rows. `SKIPPED`
 * never re-arms, at any trigger. The re-arm decision is exhaustive over [CycleResult] and made outside [mutex]. A
 * completion is requested only while [mayCreate] (it is always recorded by the transport first). A failed tail fails
 * every waiter and consumes its pending pass, so no phantom pass lands on the next request. Ledger counts refresh
 * after each unit only while [foregrounded] (design D11), best-effort.
 *
 * ⚠️ **Nothing a unit (or the refresh) does may request the tail and await it** — the join would wait on itself. It is
 * refused loudly: a request made from inside this runner's own tail throws rather than deadlocking.
 */
class TailRunner(
    private val importStaged: suspend (stopRequested: () -> Boolean) -> Unit,
    private val topUp: suspend (stopRequested: () -> Boolean) -> CycleResult,
    private val walkAndPublish: suspend (stopRequested: () -> Boolean) -> WalkOutcome,
    /** Whether ③ may run at all — a full grant. Under a partial grant the tail reads no library. */
    private val walkPermitted: () -> Boolean,
    /** The app's admission right now: whether a completion may request the top-up. */
    private val mayCreate: () -> Boolean,
    /** Whether the app is foregrounded now: the only state in which a unit refreshes the counts. */
    private val foregrounded: () -> Boolean,
    private val refreshStatus: suspend () -> Unit,
    private val scheduler: BackgroundScheduler,
    private val log: Logger = Logger.withTag("TailRunner"),
    private val logScope: LogScope = LogScope.NoOp,
) {
    private val mutex = Mutex()

    /**
     * The running tail, or `null`. Written only under [mutex]; read without it by [stop], which must not suspend —
     * hence atomic. "Is a tail running" and "what do joiners await" are one fact.
     */
    private val current = AtomicReference<Run?>(null)

    /**
     * Request the tail for [trigger], and return once the tail that covers it has ended — then apply [trigger]'s
     * re-arm to its outcome. Answers `null` for a completion the app's admission declines (nothing was requested).
     * A tail that fails rethrows here, in the caller that started it and in every joiner.
     */
    suspend fun request(trigger: TailTrigger): TailOutcome? =
        log.invocation(logScope, "tail.request", params = "trigger=$trigger", result = { it?.toString() ?: "not requested" }) {
            check(currentCoroutineContext()[InsideTail]?.runner !== this) {
                "a tail unit requested the tail it is running in — that join would wait on itself"
            }
            if (trigger == TailTrigger.UPLOAD_COMPLETED && !mayCreate()) {
                log.i { "upload completed; the app may not create now — recorded, no top-up requested" }
                return@invocation null
            }
            val outcome = admit(trigger)
            if (shouldSchedule(outcome, trigger.rearm)) scheduler.scheduleNext()
            outcome
        }

    /**
     * The operating system says time is up: the running tail completes the unit in flight, starts nothing further,
     * and ends. Returns at once — an expiration handler must not wait for the work it stops. With no tail running
     * there is nothing to stop; a caller whose time is up must not request the tail afterwards.
     */
    fun stop(reason: String) {
        val run = current.load()
        if (run == null) {
            log.i { "stop requested ($reason) with no tail running" }
            return
        }
        run.stop.store(true)
        log.w { "stop requested ($reason) during ${run.unit} — it completes; nothing further starts" }
    }

    /** Joins the running tail, or starts one and drives it here. */
    private suspend fun admit(trigger: TailTrigger): TailOutcome {
        val (run, joined) = mutex.withLock {
            val running = current.load()
            if (running != null) {
                running.pending = running.pending?.let { maxOf(it, trigger.scope) } ?: trigger.scope
                running to true
            } else {
                Run(trigger.scope).also { current.store(it) } to false
            }
        }
        if (joined) log.i { "$trigger joined the running tail; it makes one more ${run.pending} pass" }
        return if (joined) run.done.await() else withContext(InsideTail(this)) { drive(run) }
    }

    private suspend fun drive(run: Run): TailOutcome {
        try {
            var scope = run.first
            while (true) {
                val pass = runPass(run, scope)
                // Decide-and-exit atomically, so a joiner either extends this tail or finds none running.
                scope = mutex.withLock { nextPass(run, pass) } ?: return run.done.await()
            }
        } catch (t: Throwable) {
            // As in the pump: `NonCancellable` so the cleanup runs even when [t] is a cancellation, or a joiner would
            // await a deferred nothing can complete, forever. Unreachable today (no critical section suspends while
            // holding the lock, and every composition injects a serial scope) — kept because neither is enforced.
            withContext(NonCancellable) {
                mutex.withLock {
                    // Fail the waiters rather than park them: their work is this tail's, and it did not happen. The
                    // pending pass belonged to this tail too; left set, it would arm a phantom pass on the next one.
                    run.pending = null
                    run.done.completeExceptionally(t)
                    current.compareAndSet(run, null)
                }
            }
            throw t
        }
    }

    /** Under [mutex]: the next pass's scope, or `null` after publishing this tail's outcome and clearing it. */
    private fun nextPass(run: Run, pass: Pass): TailScope? {
        val pending = run.pending
        run.pending = null
        val stopped = run.stop.load()
        if (pending != null && !stopped) return pending
        // A stop that dropped a joiner's pass cut the tail as surely as one that stopped a unit.
        val outcome = pass.outcome(cut = pass.cut || (stopped && pending != null))
        if (outcome.cut) log.w { "tail stopped with work left: reporting ${outcome.result}" }
        run.done.complete(outcome)
        current.compareAndSet(run, null)
        return null
    }

    private suspend fun runPass(run: Run, scope: TailScope): Pass {
        val pass = Pass()
        val full = scope == TailScope.FULL
        if (full && !step(run, pass, UNIT_IMPORT) { importStaged(run.stopRequested) }) return pass
        if (!step(run, pass, UNIT_TOP_UP) { pass.results += topUp(run.stopRequested) }) return pass
        if (!full || !walkPermitted()) return pass
        var added = false
        if (!step(run, pass, UNIT_WALK) { added = pass.walked(walkAndPublish(run.stopRequested)) }) return pass
        if (added) step(run, pass, UNIT_TOP_UP) { pass.results += topUp(run.stopRequested) }
        return pass
    }

    /** Runs [body] as one unit unless a stop came first; `false` when the pass was cut here. */
    private suspend inline fun step(run: Run, pass: Pass, name: String, body: () -> Unit): Boolean {
        if (run.stop.load()) {
            log.i { "stopped before $name — not started" }
            pass.cut = true
            return false
        }
        run.unit = name
        body()
        refreshIfForegrounded()
        return !pass.cut
    }

    private suspend fun refreshIfForegrounded() {
        if (!foregrounded()) return
        runCatchingCancellable { refreshStatus() }
            .onFailure { log.w(it) { "status refresh after a tail unit failed" } }
    }

    /**
     * The re-arm decision for [outcome] under [rearm] — exhaustive, so a new [CycleResult] variant is a compile error
     * here rather than a policy nobody chose. `SKIPPED` schedules nothing at any trigger: the membership contributes
     * nothing, and the transition that changes that arrives as [TailTrigger.ARM].
     */
    private fun shouldSchedule(outcome: TailOutcome, rearm: Rearm): Boolean = when (outcome.result) {
        CycleResult.SKIPPED -> false
        CycleResult.PROCESSING -> rearm != Rearm.NEVER
        CycleResult.COMPLETED, CycleResult.FAILED -> rearm == Rearm.ALWAYS
    }

    /** One tail: its first scope, the pass joiners requested, its stop, and what its waiters await. */
    private class Run(val first: TailScope) {
        val done = CompletableDeferred<TailOutcome>()

        /** Guarded by [mutex]. */
        var pending: TailScope? = null
        val stop = AtomicBoolean(false)
        val stopRequested: () -> Boolean = { stop.load() }

        /** The unit in flight, for the stop's diagnostic line. */
        @Volatile
        var unit: String = "admission"
    }

    /** One pass's upload results, in order, and whether a stop cut it. */
    private class Pass {
        val results = mutableListOf<CycleResult>()
        var cut = false

        /** Records ③'s outcome; `true` when it added rows (so ② runs again). */
        fun walked(outcome: WalkOutcome): Boolean = when (outcome) {
            WalkOutcome.Abandoned -> {
                cut = true
                false
            }
            is WalkOutcome.Walked -> {
                results += outcome.result
                outcome.addedRows
            }
        }

        /**
         * The pass's upload outcome. The latest unit's `SKIPPED` wins — the freshest gate answer says the membership
         * contributes nothing. A cut pass is otherwise `PROCESSING`. An uncut one reports work remaining if any unit
         * did, then a failure, then completion.
         */
        fun outcome(cut: Boolean): TailOutcome {
            val last = results.lastOrNull()
            val result = when {
                last == CycleResult.SKIPPED -> CycleResult.SKIPPED
                cut || CycleResult.PROCESSING in results -> CycleResult.PROCESSING
                CycleResult.FAILED in results -> CycleResult.FAILED
                else -> CycleResult.COMPLETED
            }
            return TailOutcome(result, cut)
        }
    }

    /** Marks a coroutine as running inside [runner]'s own tail, so a self-join is refused rather than deadlocked. */
    private class InsideTail(val runner: TailRunner) : AbstractCoroutineContextElement(InsideTail) {
        companion object Key : CoroutineContext.Key<InsideTail>
    }

    private companion object {
        const val UNIT_IMPORT = "① import"
        const val UNIT_TOP_UP = "② top-up"
        const val UNIT_WALK = "③ walk → manifest"
    }
}
