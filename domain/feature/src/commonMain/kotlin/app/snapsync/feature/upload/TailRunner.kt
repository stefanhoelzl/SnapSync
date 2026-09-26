@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.feature.upload

import app.snapsync.model.runCatchingCancellable
import app.snapsync.model.CycleResult
import app.snapsync.ports.EntryContext
import app.snapsync.ports.invocation
import app.snapsync.services.wake.Heartbeat
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.selects.select
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

/**
 * Which of the tail's units a request needs. Joining requests merge with [plus] — the smallest scope covering both —
 * so a joiner never drops a unit another joiner needed, and never adds one nobody asked for (a staged download and a
 * freed upload slot together need ① and ②, not a walk).
 */
enum class TailScope(internal val imports: Boolean, internal val topsUp: Boolean, internal val walks: Boolean) {
    /** ① alone — what a download staged in a running process needs. */
    IMPORT(imports = true, topsUp = false, walks = false),

    /** ② alone — what a freed upload slot needs. */
    TOP_UP(imports = false, topsUp = true, walks = false),

    /** ① then ② — what an import and a top-up requested together need; still no walk. */
    IMPORT_AND_TOP_UP(imports = true, topsUp = true, walks = false),

    /** ① import → ② top-up → ③ walk → manifest (full grant only) → ② again when ③ added rows. */
    FULL(imports = true, topsUp = true, walks = true),
    ;

    /** The smallest scope covering both this and [other]. */
    operator fun plus(other: TailScope): TailScope = entries.first {
        it.imports == (imports || other.imports) && it.topsUp == (topsUp || other.topsUp) &&
            it.walks == (walks || other.walks)
    }
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
 * `background-upload`, "The tail runner reimplements the OS scheduler"; decision record
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

    /**
     * A download finished staging in a process that is already running (no relaunch delivered it): the import alone.
     * A staged photo changes nothing the walk or the top-up would see, and a burst stages one resource at a time — a
     * walk per staged resource is exactly the waste the tail exists to avoid. It re-arms nothing: the wake or the
     * foreground it arrived in owns the heartbeat's re-arm.
     */
    DOWNLOAD_STAGED(TailScope.IMPORT, Rearm.NEVER),
}

/**
 * What the tail hands its import unit (①): Apple's stop, and the one wait a unit may give up on.
 *
 * An import is a photo-library transaction, and a transaction can stall and never report — the claim it holds keeps
 * any other drain off that photo, so nothing is lost by not waiting, but a tail that waited would hold every later
 * request hostage behind it (they join the running tail), which is exactly what "a stalled import blocks no other
 * work" rules out (capability `receiving-photos`). So the unit awaits each import through [awaitUnlessInterrupted]: it
 * completes normally, or the wait gives way — leaving the import claimed and running — when Apple's stop arrives
 * (capability `sync-status`, "Expiry stops work cooperatively at the next boundary") or when another request joins
 * the tail. No clock is involved: the tail stops waiting only because something else is due.
 */
class TailSignal internal constructor(
    private val stop: () -> Boolean,
    private val interrupts: AtomicReference<CompletableDeferred<Unit>>,
) {
    /** Whether Apple's "time is up" has arrived: start no further import. */
    fun stopRequested(): Boolean = stop()

    /**
     * Await [work] — `true` once it completed (await it to see its outcome) — unless a stop or a joining request
     * comes first: then `false`, with [work] left running.
     */
    suspend fun awaitUnlessInterrupted(work: Deferred<*>): Boolean {
        val gate = interrupts.load()
        val finished = work.isCompleted || !stop() && select {
            work.onJoin { true }
            gate.onAwait { false }
        }
        // Consumed: a later wait waits again, unless the stop that interrupted it still holds.
        if (!finished) interrupts.compareAndSet(gate, CompletableDeferred())
        return finished
    }
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
 * after its own (capability `sync-status`, "Each OS wake does its own work, then hands the rest to one
 * opportunistic tail"; `background-upload`, "The tail runner reimplements the OS scheduler"; decision record
 * `changes/own-work-per-wake`). It is the successor of the retired `BackgroundUploadPump`, and carries over every one of its
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
 * pass, covering the union of the scopes the joiners need, however many joined — no request is lost, no second tail starts,
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
 * never re-arms for the upload units' sake, at any trigger — only staged imports still waiting ([importsRemain]) re-arm
 * a trigger whose upload outcome would not. The re-arm decision is exhaustive over [CycleResult] and made outside
 * [mutex]. A
 * completion is requested only while [mayCreate] (it is always recorded by the transport first). A failed tail fails
 * every waiter and consumes its pending pass, so no phantom pass lands on the next request. Ledger counts refresh
 * after each unit only while [foregrounded] (design D11), best-effort.
 *
 * ⚠️ **Nothing a unit (or the refresh) does may request the tail and await it** — the join would wait on itself. It is
 * refused loudly: a request made from inside this runner's own tail throws rather than deadlocking.
 */
class TailRunner(
    /**
     * ① — handed a [TailSignal] rather than a bare stop flag, because an import is the one unit that can wait on
     * something that never reports (a photo-library transaction), and it must not hold the tail hostage: see
     * [TailSignal.awaitUnlessInterrupted].
     */
    private val importStaged: suspend (signal: TailSignal) -> Unit,
    private val topUp: suspend (stopRequested: () -> Boolean) -> CycleResult,
    private val walkAndPublish: suspend (stopRequested: () -> Boolean) -> WalkOutcome,
    /** Whether ③ may run at all — a full grant. Under a partial grant the tail reads no library. */
    private val walkPermitted: () -> Boolean,
    /** The app's admission right now: whether a completion may request the top-up. */
    private val mayCreate: () -> Boolean,
    /** Whether the app is foregrounded now: the only state in which a unit refreshes the counts. */
    private val foregrounded: () -> Boolean,
    private val refreshStatus: suspend () -> Unit,
    /** The heartbeat the re-arm rule arms (capability `background-upload`). */
    private val heartbeat: Heartbeat,
    /**
     * Whether staged downloads are still waiting to be imported — asked after a tail whose upload units would not
     * re-arm on their own. Leftover imports count as work remaining, so they keep the heartbeat armed until ① has
     * drained them (declared in phase 11f): without it a membership that only receives never arms one, and a photo
     * whose save iOS cut short waited for the next push or the next opening (capability `receiving-photos`, "Photos
     * arrive without the app being opened"). A read of the core's own store; a failed read re-arms nothing.
     */
    private val importsRemain: suspend () -> Boolean,
    /**
     * What a stop left behind, beyond the units it kept from running — for the operating-system expiry line
     * (capability `privacy-security`, "Operating-system expiry is logged"): at least the staged downloads not yet
     * imported. A read of the core's own stores; best-effort, and never consulted unless a stop cut a tail.
     */
    private val leftover: suspend () -> String,
    private val log: Logger = Logger.withTag("TailRunner"),
    private val entryContext: EntryContext = EntryContext.NoOp,
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
        log.invocation(entryContext, "tail.request", params = "trigger=$trigger", result = { it?.toString() ?: "not requested" }) {
            check(currentCoroutineContext()[InsideTail]?.runner !== this) {
                "a tail unit requested the tail it is running in — that join would wait on itself"
            }
            if (trigger == TailTrigger.UPLOAD_COMPLETED && !mayCreate()) {
                log.i { "upload completed; the app may not create now — recorded, no top-up requested" }
                return@invocation null
            }
            val outcome = admit(trigger)
            if (shouldSchedule(outcome, trigger.rearm) || importsLeft(trigger.rearm)) heartbeat.arm()
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
        run.stopReason = reason
        run.stop.store(true)
        run.interrupt()
        // The first half of the expiry line: which signal, and what was running. The second half — whether that unit
        // completed or was abandoned, and what was left — is written once the tail has actually stopped ([drive]).
        log.w {
            val fate = if (run.unit == UNIT_WALK) "the walk is abandoned" else "that unit completes"
            "OS expiry — $reason — during ${run.unit}: $fate; nothing further starts"
        }
    }

    /** Joins the running tail, or starts one and drives it here. */
    private suspend fun admit(trigger: TailTrigger): TailOutcome {
        val (run, joined) = mutex.withLock {
            val running = current.load()
            if (running != null) {
                running.pending = running.pending?.let { it + trigger.scope } ?: trigger.scope
                // A joiner is work waiting: an import the running unit is merely awaiting must not hold it back.
                running.interrupt()
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
                scope = mutex.withLock { nextPass(run, pass) } ?: break
            }
            val outcome = run.done.await()
            if (outcome.cut) logStopped(run)
            return outcome
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
        // A pass that ran no upload unit (an import alone) says nothing about uploads: the outcome keeps the latest
        // pass that did, so a joiner's re-arm is still decided on what the upload units last reported.
        if (pass.results.isNotEmpty()) run.lastResults = pass.results.toList()
        if (pending != null && !stopped) return pending
        if (stopped && pending != null) run.left += "the joiners' further $pending pass"
        // A stop that dropped a joiner's pass cut the tail as surely as one that stopped a unit.
        val outcome = outcome(run.lastResults, cut = pass.cut || (stopped && pending != null))
        run.done.complete(outcome)
        current.compareAndSet(run, null)
        return null
    }

    private suspend fun runPass(run: Run, scope: TailScope): Pass {
        val pass = Pass()
        // A join that landed before this unit began is served by the pass it requested, not by abandoning a wait here.
        if (scope.imports) run.interrupts.store(CompletableDeferred())
        if (scope.imports && !step(run, pass, UNIT_IMPORT) { importStaged(run.signal) }) return pass
        if (scope.topsUp && !step(run, pass, UNIT_TOP_UP) { pass.results += topUp(run.stopRequested) }) return pass
        if (!scope.walks || !walkPermitted()) return pass
        var added = false
        val walked = step(run, pass, UNIT_WALK) {
            val outcome = walkAndPublish(run.stopRequested)
            if (outcome == WalkOutcome.Abandoned) run.abandonedWalk = true
            added = pass.walked(outcome)
        }
        if (!walked) return pass
        if (added) step(run, pass, UNIT_TOP_UP) { pass.results += topUp(run.stopRequested) }
        return pass
    }

    /** Runs [body] as one unit unless a stop came first; `false` when the pass was cut here. */
    private suspend inline fun step(run: Run, pass: Pass, name: String, body: () -> Unit): Boolean {
        if (run.stop.load()) {
            log.i { "stopped before $name — not started" }
            run.left += name
            pass.cut = true
            return false
        }
        run.unit = name
        body()
        refreshIfForegrounded()
        return !pass.cut
    }

    /**
     * The second half of the operating-system expiry line (capability `privacy-security`): whether the unit that was
     * running when the stop came completed or was abandoned, and what the stop left for a later wake.
     */
    private suspend fun logStopped(run: Run) {
        val unitEnd = if (run.abandonedWalk) "the walk was abandoned, recording nothing" else "${run.unit} completed"
        val extra = runCatchingCancellable { leftover() }.getOrElse { "left: unreadable (${it.message})" }
        val notRun = run.left.ifEmpty { listOf("nothing further was due") }.joinToString()
        val tail = if (extra.isEmpty()) "" else "; $extra"
        log.w { "tail stopped (${run.stopReason}): $unitEnd; not run: $notRun$tail" }
    }

    private suspend fun refreshIfForegrounded() {
        if (!foregrounded()) return
        runCatchingCancellable { refreshStatus() }
            .onFailure { log.w(it) { "status refresh after a tail unit failed" } }
    }

    /**
     * The re-arm decision for [outcome] under [rearm] — exhaustive, so a new [CycleResult] variant is a compile error
     * here rather than a policy nobody chose. `SKIPPED` schedules nothing for the uploads at any trigger: the
     * membership contributes nothing, and the transition that changes that arrives as [TailTrigger.ARM]. Leftover staged imports
     * are the other half of "work remains" ([importsLeft]).
     */
    private fun shouldSchedule(outcome: TailOutcome, rearm: Rearm): Boolean = when (outcome.result) {
        CycleResult.SKIPPED -> false
        CycleResult.PROCESSING, is CycleResult.Paused -> rearm != Rearm.NEVER
        CycleResult.COMPLETED, CycleResult.FAILED -> rearm == Rearm.ALWAYS
    }

    /**
     * Whether leftover staged imports re-arm a trigger its upload outcome did not: a trigger that never re-arms still
     * does not, and one that does counts them as work remaining — decided outside [mutex], like the rest of the re-arm.
     */
    private suspend fun importsLeft(rearm: Rearm): Boolean {
        if (rearm == Rearm.NEVER) return false
        return runCatchingCancellable { importsRemain() }
            .onFailure { log.w(it) { "whether staged imports remain is unreadable — no re-arm for them" } }
            .getOrDefault(false)
            .also { left -> if (left) log.i { "staged downloads remain to import — the heartbeat is re-armed" } }
    }

    /** One tail: its first scope, the pass joiners requested, its stop, and what its waiters await. */
    private class Run(val first: TailScope) {
        val done = CompletableDeferred<TailOutcome>()

        /** Guarded by [mutex]. */
        var pending: TailScope? = null
        val stop = AtomicBoolean(false)
        val stopRequested: () -> Boolean = { stop.load() }

        /** Completed when a stop or a join interrupts a wait; replaced once a wait has consumed it. */
        val interrupts = AtomicReference(CompletableDeferred<Unit>())
        val signal = TailSignal(stopRequested, interrupts)

        fun interrupt() {
            interrupts.load().complete(Unit)
        }

        /** The unit in flight, for the stop's diagnostic line. */
        @Volatile
        var unit: String = "admission"

        /** The signal that stopped this tail, for the expiry line. */
        @Volatile
        var stopReason: String = ""

        /** The upload results of the latest pass that ran an upload unit. Written only by the driving coroutine. */
        var lastResults: List<CycleResult> = emptyList()

        /** The units a stop kept from running, for the expiry line. Written only by the driving coroutine. */
        val left = mutableListOf<String>()

        /** Whether a stop abandoned the walk. Written only by the driving coroutine. */
        var abandonedWalk = false
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
    }

    /**
     * The tail's upload outcome over [results] — its latest upload-bearing pass. The latest unit's `SKIPPED` wins: the
     * freshest gate answer says the membership contributes nothing. A cut tail is otherwise `PROCESSING`. An uncut one
     * reports work remaining if any unit did (a paused unit's work remains too), then a failure, then completion; a
     * tail that ran no upload unit at all (an import alone) completed.
     */
    private fun outcome(results: List<CycleResult>, cut: Boolean): TailOutcome {
        val result = when {
            results.lastOrNull() == CycleResult.SKIPPED -> CycleResult.SKIPPED
            cut || results.any { it == CycleResult.PROCESSING || it is CycleResult.Paused } -> CycleResult.PROCESSING
            CycleResult.FAILED in results -> CycleResult.FAILED
            else -> CycleResult.COMPLETED
        }
        return TailOutcome(result, cut)
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
