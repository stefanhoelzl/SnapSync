@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.scheduling

import app.snapsync.domain.program.InvalidProgramState
import app.snapsync.inbound.Completion
import app.snapsync.inbound.Log
import app.snapsync.inbound.Running
import app.snapsync.inbound.Trigger
import app.snapsync.inbound.of
import app.snapsync.ports.BackgroundTime
import app.snapsync.ports.BackgroundTimeHold
import app.snapsync.ports.EntryEvents
import app.snapsync.ports.EntryOutcome
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
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.handleCoroutineException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
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
internal enum class OsTime {
    HELD,
    GRANTED,

    /** A person's work, started while the app is in front — which keeps it running: no hold of its own. */
    IN_FRONT,
}

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
    /** What the runtime reaches outside the process through. */
    ports: RuntimePorts,
    private val grace: Duration = EXPIRY_GRACE,
    /**
     * Where an [Answered][app.snapsync.inbound.Answered] delivery's work runs while its delivering thread waits for the
     * answer: `null` — every phone — runs it on that thread, in the blocking call's own loop. A host that must see
     * every task of the process (the control channel's JVM host, whose timers run on mocked time) names its lane here.
     */
    private val answerOn: CoroutineDispatcher? = null,
) {
    private val log = Log("Runtime")
    private val backgroundTime = ports.backgroundTime
    private val threadLabel = ports.threadLabel
    private val entryEvents = ports.entryEvents

    /**
     * The process scope every run is launched in: supervised, on the composition lane, labelling each task with its
     * run's trigger. Public only for the old zones' own launches, which phase 4 moves under entry points and sequences;
     * no new code launches in it (`CoroutinesInScheduling`).
     */
    val scope = CoroutineScope(
        SupervisorJob(parent) + labelling(lane, threadLabel) +
            CoroutineExceptionHandler { context, failure ->
                when (failure) {
                    is InvalidProgramState -> endProcess(failure)
                    else -> log.unlabelled.e("unawaited work failed", "trigger" of context[Trigger], cause = failure)
                }
            },
    )

    internal val flights = SingleFlight(this)

    /**
     * Starts [body] as the run of the delivery [trigger], answering [completion] when it ends. The run holds background
     * time when [time] says so. Answers the [Running] the delivering adapter expires.
     */
    internal fun start(trigger: Trigger, time: OsTime, completion: Completion, body: suspend (Run) -> Unit): Running =
        launch(trigger, time, delivered(trigger.label), completion) { body(Run(this, trigger)) }

    /**
     * Work nobody awaits, started from inside a run of [work] — or, with none, for no delivery: it runs in the process
     * scope under its own background-time hold, labelled [trigger] — the caller's, so the lines of what a trigger
     * started still name it — and belongs to [work], whose `done` waits for it.
     */
    internal fun detached(
        trigger: Trigger,
        work: EntryWork?,
        body: suspend () -> Unit,
        time: OsTime = OsTime.HELD,
    ): Job {
        val owner = if (work == null) EntryWork.started(trigger.label, entryEvents, log) else work.opened()
        return launch(trigger, time, owner, completion = null, body).job
    }

    /**
     * Starts [body] detached for a caller outside any entry point's steps — a membership transition arming its uploads,
     * a staged download's import — labelled as the entry point [name]. Inside a run it is that run's work; outside any,
     * its own.
     */
    suspend fun detached(name: String, body: suspend (Run) -> Unit) {
        val trigger = Trigger.of(name)
        detached(trigger, currentCoroutineContext()[WorkElement]?.work, { body(Run(this, trigger)) })
    }

    /**
     * Awaits [block], run in the process scope: if the caller stops waiting — its time is up, or the pass it belongs to
     * yielded — [block] runs on to its end. For work the platform cannot stop once started, like a photo-library
     * import, whose own report records what it did. Labelled with the caller's trigger, when it has one; the entry
     * point's work it outlives its caller for is still that work's: its `done` waits for it.
     */
    suspend fun <T> outliving(block: suspend () -> T): T {
        val trigger = currentCoroutineContext()[Trigger]
        val joined = currentCoroutineContext()[WorkElement]
        val work = if (joined == null) null else joined.work.opened()
        val outlived = scope.async((trigger ?: Trigger.of("outliving")) + (joined ?: EmptyCoroutineContext)) { block() }
        work?.let { outlived.invokeOnCompletion { cause -> it.closed(endedAs(cause)) } }
        return outlived.await()
    }

    /**
     * Starts [body] without awaiting it and without a background-time hold of its own — a person's command, which the
     * app being in front keeps running — labelled [name]. Inside an entry point's work (a tap's) it is that work's.
     */
    suspend fun alongside(name: String, body: suspend () -> Unit) {
        detached(Trigger.of(name), currentCoroutineContext()[WorkElement]?.work, body, OsTime.IN_FRONT)
    }

    /**
     * Runs [body] on the delivering thread, labelled [name], contained; what it starts is the delivery's work. [about]
     * says which of its kind the delivery is — a tap's intent — on its `delivered`.
     */
    internal fun inline(name: String, about: String?, body: (Delivery) -> Unit) {
        val work = delivered(name, about)
        val replaced = threadLabel.swap(name)
        val failed = try {
            body(Delivery(this, Trigger.of(name), work))
            null
        } catch (fatal: InvalidProgramState) {
            endProcess(fatal)
        } catch (failure: Throwable) {
            log.unlabelled.e("delivery failed", "trigger" of name, cause = failure)
            failure
        } finally {
            threadLabel.swap(replaced)
        }
        work.answered()
        work.closed(failedAs(failed))
    }

    /**
     * Blocks the delivering thread until [body] answers. Any throwable answers [onFailure] — a cancellation included:
     * the caller is the platform's synchronous call, an Objective-C boundary any Kotlin throwable aborts the process
     * across, and nothing above it could act on a cancellation.
     */
    internal fun <R> blocking(name: String, onFailure: R, body: suspend (Run) -> R): R {
        val work = delivered(name)
        val replaced = threadLabel.swap(name)
        var failed: Throwable? = null
        val answer = try {
            val on: CoroutineContext = if (answerOn == null) EmptyCoroutineContext else labelling(answerOn, threadLabel)
            val trigger = Trigger.of(name)
            runBlocking(on + trigger + WorkElement(work)) { body(Run(this@Runtime, trigger)) }
        } catch (fatal: InvalidProgramState) {
            endProcess(fatal)
        } catch (failure: Throwable) {
            log.unlabelled.e("delivery failed", "trigger" of name, cause = failure)
            failed = failure
            onFailure
        } finally {
            threadLabel.swap(replaced)
        }
        work.answered()
        work.closed(failedAs(failed))
        return answer
    }

    /** The operating system handed [entry] over. */
    private fun delivered(entry: String, about: String? = null): EntryWork =
        EntryWork.delivered(entry, about, entryEvents, log)

    /**
     * Launches one run of [work]: the delivery's own when it answers a [completion], whose answer is told `answered`;
     * a run it detached when there is none.
     */
    private fun launch(
        trigger: Trigger,
        time: OsTime,
        work: EntryWork,
        completion: Completion?,
        body: suspend () -> Unit,
    ): Expirable {
        val run = Expirable(trigger.label, completion, work)
        val job = scope.launch(trigger + WorkElement(work), start = CoroutineStart.LAZY) { run.contained { body() } }
        // The hold is begun on the delivering thread, before it returns: the operating system may suspend the process
        // the moment its callback returns, so time asked for later, from the lane, may never be asked for at all.
        run.attach(job)
        if (time == OsTime.HELD) backgroundTime?.let { run.hold(it.begin(trigger.label, run)) }
        job.start()
        return run
    }

    /**
     * One run as its delivering adapter sees it: expired by the operating system, answered exactly once — its hold,
     * when it took one, ended with the answer.
     */
    private inner class Expirable(
        private val label: String,
        /** What the operating system is answered — `null` for a run a delivery detached, which answers no one. */
        private val completion: Completion?,
        private val work: EntryWork,
    ) : Running {
        private val answered = AtomicBoolean(false)
        private val failed = AtomicBoolean(false)
        lateinit var job: Job
        private val held = AtomicReference<BackgroundTimeHold?>(null)

        /** When the operating system expired the run — what its unwind is measured from (`EXPIRY_GRACE`'s evidence). */
        private val expiredAt = AtomicReference<TimeMark?>(null)

        /** The first of the three containment sites: a run's failure is reported, never rethrown. */
        suspend fun contained(body: suspend () -> Unit) {
            try {
                body()
            } catch (failure: CancellationException) {
                throw failure
            } catch (fatal: InvalidProgramState) {
                throw fatal
            } catch (failure: Throwable) {
                failed.store(true)
                log.e("run failed", "trigger" of label, cause = failure)
            }
        }

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
            job.invokeOnCompletion { cause ->
                expiredAt.load()?.let {
                    log.unlabelled.i("expired run unwound", "trigger" of label, "after" of it.elapsedNow())
                }
                answer()
                work.closed(if (failed.load()) EntryOutcome.FAILED else endedAs(cause))
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
            if (completion == null) return
            completion.complete()
            work.answered()
        }
    }
}

/** What the [Runtime] reaches outside the process through — the process's own ports. */
class RuntimePorts(
    /**
     * The process's background time, which every held run begins a hold on — `null` in a process that has none to hold
     * (the upload extension: the operating system keeps it alive for exactly the one delivery it answers).
     */
    val backgroundTime: BackgroundTime?,
    /** The per-thread label each task's thread carries — its run's trigger. */
    val threadLabel: ThreadLabel,
    /** Where each entry point's `delivered`, `answered` and `done` is told, beside its log line. */
    val entryEvents: EntryEvents,
)

/** How a delivery made on its own thread ended: a throwable is a failure, whatever it is. */
private fun failedAs(failure: Throwable?): EntryOutcome =
    if (failure == null) EntryOutcome.COMPLETED else EntryOutcome.FAILED

/**
 * One inline delivery, as its entry point's run sees it: what it starts and does not await is its work, which its
 * `done` waits for.
 */
class Delivery internal constructor(
    private val runtime: Runtime,
    private val trigger: Trigger,
    private val work: EntryWork,
) {
    /**
     * Work this delivery starts and does not await: it continues in the process scope, under its own background-time
     * hold, labelled with this delivery's trigger. Answers its job, for a caller that must be able to wait for it.
     */
    fun detached(block: suspend () -> Unit): Job = runtime.detached(trigger, work, block)

    /** [detached] work that is a list of steps (`docs/patterns.md`, "Steps"), as a run of this delivery's trigger. */
    fun detachedSteps(block: suspend Steps.() -> Unit): Job =
        runtime.detached(trigger, work, { Run(runtime, trigger).steps(block) })

    /**
     * Where work this delivery's handling starts elsewhere runs — a tap's intents, on the screen's container: labelled
     * with this delivery's trigger, and what it starts in turn is this delivery's work.
     */
    val context: CoroutineContext get() = trigger + WorkElement(work)

    /** Counts [job] — started by this delivery's handling, elsewhere — as this delivery's work until it ends. */
    fun joining(job: Job) {
        work.opened()
        job.invokeOnCompletion { work.closed(endedAs(it)) }
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

/**
 * Ends the process with [state] as its crash (`docs/patterns.md`, "Invalid program state"): the coroutines library's
 * last resort, on the calling thread — the thread's uncaught handler on the JVM and Android, Kotlin/Native's
 * unhandled-exception hook on iOS, whose crash report carries [state]'s guarantee and stack (measured on the
 * simulator, 2026-10-25). Never a throw into the caller: on iOS that caller is Objective-C or Swift, where a Kotlin
 * throwable terminates without the hook and the report says only that something threw. [state] is thrown after, for a
 * runtime whose last resort returns (a JVM host's thread).
 */
@OptIn(InternalCoroutinesApi::class)
internal fun endProcess(state: InvalidProgramState): Nothing {
    handleCoroutineException(EmptyCoroutineContext, state)
    throw state
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
