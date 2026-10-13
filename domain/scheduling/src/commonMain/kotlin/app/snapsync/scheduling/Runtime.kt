@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.scheduling

import app.snapsync.inbound.Completion
import app.snapsync.inbound.Log
import app.snapsync.inbound.Running
import app.snapsync.inbound.Trigger
import app.snapsync.inbound.of
import app.snapsync.ports.BackgroundTime
import app.snapsync.ports.BackgroundTimeHold
import app.snapsync.ports.ThreadLabel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Delay
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * How a delivery's run gets its time from the operating system.
 *
 * [HELD]: the run holds the process's background time from its start to its end — a silent push, a relaunch for a
 * session's events, a scene coming to the front, work started from inside the process. [GRANTED]: the delivery is
 * itself a grant of time — a scheduled wake, which the operating system keeps open until its completion — and
 * holding more would only cost (on Android 11 a hold is a foreground service with a notification).
 */
internal enum class OsTime { HELD, GRANTED }

/**
 * The process's runtime (`docs/patterns.md`, "Entry points"): the supervised scope every run is launched in, the
 * background-time holds runs take, and the operating system's expiry. Built once per process by its composition and
 * handed to every entry point; nothing else starts work.
 *
 * **Containment.** A run that throws is contained where it was started — its entry point, or (for work nobody
 * awaits) the process scope's handler — and reported as an `Error` line, so it reaches the crash channel. A
 * cancellation is never a failure.
 *
 * **Expiry.** Time is up when the operating system says so: a scheduled wake's expiry, or the end of a held
 * background time. The run is cancelled, its cancellation is given at most [grace] to unwind — work that must happen
 * together is one non-cancellable operation, short and with nothing slow inside — and then its completion is
 * answered and its hold ended **regardless**: too early and the process is suspended holding a lock it shares with
 * the other process, too late and the watchdog kills it.
 */
class Runtime(
    /** The job the process's scope lives under — the root's, which ends it; `null` for a process that never ends it. */
    parent: Job?,
    /** The composition lane every run is dispatched on. */
    lane: CoroutineDispatcher,
    /**
     * The process's background time, which every held run begins a hold on — `null` in a process that has none to hold
     * (the upload extension: the operating system keeps it alive for exactly the one delivery it answers).
     */
    private val backgroundTime: BackgroundTime?,
    private val threadLabel: ThreadLabel,
    private val grace: Duration = EXPIRY_GRACE,
) {
    private val log = Log("Runtime")

    /**
     * The process scope every run is launched in: supervised, on the composition lane, labelling each task with its
     * run's trigger. Public only for the old zones' own launches, which phase 4 moves under entry points and sequences;
     * no new code launches in it (`CoroutinesInScheduling`).
     */
    val scope = CoroutineScope(
        SupervisorJob(parent) + labelling(lane, threadLabel) + RuntimeElement(this) +
            CoroutineExceptionHandler { context, failure ->
                log.unlabelled.e("unawaited work failed", "trigger" of context[Trigger], cause = failure)
            },
    )

    internal val flights = SingleFlight(this)

    /**
     * Starts [body] as the run of [trigger], answering [completion] when it ends. The run holds background time when
     * [time] says so. Answers the [Running] the delivering adapter expires.
     */
    internal fun start(trigger: Trigger, time: OsTime, completion: Completion, body: suspend () -> Unit): Running {
        val run = Run(trigger.label, completion)
        val job = scope.launch(trigger, start = CoroutineStart.LAZY) { contained(trigger.label) { body() } }
        // The hold is begun on the delivering thread, before it returns: the operating system may suspend the process
        // the moment its callback returns, so time asked for later, from the lane, may never be asked for at all.
        run.attach(job)
        if (time == OsTime.HELD) backgroundTime?.let { run.hold(it.begin(trigger.label, run)) }
        job.start()
        return run
    }

    /**
     * Work nobody awaits, started from inside the process: it runs in the process scope under its own background-time
     * hold, labelled [trigger] — the caller's, so the lines of what a trigger started still name it.
     */
    internal fun detached(trigger: Trigger, body: suspend () -> Unit) {
        start(trigger, OsTime.HELD, Completion.None, body)
    }

    /**
     * Starts [body] detached for a caller outside any run — a membership transition arming its uploads — labelled as
     * the entry point [name].
     */
    fun detached(name: String, body: suspend () -> Unit) = detached(Trigger.of(name), body)

    /** Runs [body] on the delivering thread, labelled [name], contained. */
    internal fun inline(name: String, body: () -> Unit) {
        val replaced = threadLabel.swap(name)
        try {
            body()
        } catch (failure: Throwable) {
            log.unlabelled.e("delivery failed", "trigger" of name, cause = failure)
        } finally {
            threadLabel.swap(replaced)
        }
    }

    /**
     * Blocks the delivering thread until [body] answers. Any throwable answers [onFailure] — a cancellation included:
     * the caller is the platform's synchronous call, an Objective-C boundary any Kotlin throwable aborts the process
     * across, and nothing above it could act on a cancellation.
     */
    internal fun <R> blocking(name: String, onFailure: R, body: suspend () -> R): R {
        val replaced = threadLabel.swap(name)
        return try {
            runBlocking(Trigger.of(name) + RuntimeElement(this)) { body() }
        } catch (failure: Throwable) {
            log.unlabelled.e("delivery failed", "trigger" of name, cause = failure)
            onFailure
        } finally {
            threadLabel.swap(replaced)
        }
    }

    /** The first of the three containment sites: a run's failure is reported, never rethrown. */
    private suspend fun contained(label: String, body: suspend () -> Unit) {
        try {
            body()
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Throwable) {
            log.e("run failed", "trigger" of label, cause = failure)
        }
    }

    /**
     * One run as its delivering adapter sees it: expired by the operating system, answered exactly once — its hold,
     * when it took one, ended with the answer.
     */
    private inner class Run(private val label: String, private val completion: Completion) : Running {
        private val answered = AtomicBoolean(false)
        private lateinit var job: Job
        private val held = AtomicReference<BackgroundTimeHold?>(null)

        /** When the operating system expired the run — what its unwind is measured from (`EXPIRY_GRACE`'s evidence). */
        private val expiredAt = AtomicReference<TimeMark?>(null)

        /** The run's hold — ended at once when the run was answered while it was being begun (time up on arrival). */
        fun hold(hold: BackgroundTimeHold) {
            held.store(hold)
            if (answered.load()) release()
        }

        /** Ends the hold, once — whichever of [hold] and [answer] comes last. */
        private fun release() {
            held.exchange(null)?.end()
        }

        fun attach(job: Job) {
            this.job = job
            job.invokeOnCompletion {
                expiredAt.load()?.let {
                    log.unlabelled.i("expired run unwound", "trigger" of label, "after" of it.elapsedNow())
                }
                answer()
            }
        }

        override fun expire() {
            if (answered.load() || job.isCancelled) return
            log.unlabelled.w("operating system expiry: cancelling", "trigger" of label)
            expiredAt.store(TimeSource.Monotonic.markNow())
            job.cancel(CancellationException("the operating system's time for $label is up"))
            // The grace: the cancellation unwinds within it, or the completion is answered without waiting further.
            scope.launch(Trigger.of(label)) {
                delay(grace)
                if (!answered.load()) log.w("expiry grace ran out: answering before the run unwound", "grace" of grace)
                answer()
            }
        }

        private fun answer() {
            if (!answered.compareAndSet(expectedValue = false, newValue = true)) return
            release()
            completion.complete()
        }
    }
}

/**
 * How long a cancelled run is given to unwind after the operating system's expiry before its completion is answered
 * regardless. Measured on the SE2 (iOS 26.6, 9,941-photo library, a rig build with background time mocked so the
 * channel could fire the expiry; 2026-10-13): 19 expiries landing in foregrounds, walks and top-ups unwound within
 * 1.6–287 ms (`expired run unwound`). A cancellation waits out the non-cancellable step it lands in, and the slowest
 * such step seen was a top-up's job creation — export, seal and create — at ~1.3 s. Twice that, capped at 2 s: 2 s.
 */
val EXPIRY_GRACE: Duration = 2.seconds

/** The runtime a coroutine runs under, so `steps { }` reaches it without being handed it. */
internal class RuntimeElement(val runtime: Runtime) : AbstractCoroutineContextElement(RuntimeElement) {
    companion object Key : CoroutineContext.Key<RuntimeElement>
}

/**
 * The composition lane, with each dispatched task's [Trigger] in the thread's [ThreadLabel] for its duration — so a
 * Kermit line written inside a run is labelled like a `Log` line. A lane that schedules its own delays keeps doing so
 * ([labelling]): `delay` asks the context's dispatcher, and a wrapper that did not pass it on would move every delay
 * of the core onto the coroutines library's own timer thread.
 */
private open class Labelling(private val lane: CoroutineDispatcher, private val label: ThreadLabel) :
    CoroutineDispatcher() {
    override fun isDispatchNeeded(context: CoroutineContext): Boolean = lane.isDispatchNeeded(context)

    override fun dispatch(context: CoroutineContext, block: Runnable) = lane.dispatch(context) {
        val replaced = label.swap(context[Trigger]?.label)
        try {
            block.run()
        } finally {
            label.swap(replaced)
        }
    }
}

/** [Labelling] over a lane that schedules delays, which it keeps scheduling. */
@OptIn(InternalCoroutinesApi::class)
private class LabellingWithDelay(lane: CoroutineDispatcher, label: ThreadLabel) :
    Labelling(lane, label), Delay by (lane as Delay)

@OptIn(InternalCoroutinesApi::class)
private fun labelling(lane: CoroutineDispatcher, label: ThreadLabel): CoroutineDispatcher = when (lane) {
    is Delay -> LabellingWithDelay(lane, label)
    else -> Labelling(lane, label)
}
