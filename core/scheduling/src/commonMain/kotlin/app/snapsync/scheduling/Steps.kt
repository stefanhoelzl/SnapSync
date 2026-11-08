package app.snapsync.scheduling

import app.snapsync.domain.program.InvalidProgramState
import app.snapsync.inbound.Field.Companion.of
import app.snapsync.inbound.Log
import app.snapsync.inbound.Trigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.reflect.KClass

/** The steps of one run. */
class Steps internal constructor(
    private val runtime: Runtime,
    private val trigger: Trigger,
    /** The entry point's work this run is part of, which what it detaches joins. */
    private val work: EntryWork?,
) {
    private val log = Log("Steps")
    private val ensured = mutableListOf<Pair<String, suspend () -> Unit>>()

    /** A step the run cannot go on without: a failure ends the run. */
    suspend fun <T> required(block: suspend () -> T): T = block()

    /** A step whose failure is reported and does not end the run — the second containment site. */
    suspend fun isolated(name: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (fatal: InvalidProgramState) {
            throw fatal
        } catch (failure: Throwable) {
            log.e("step failed", "step" of name, cause = failure)
        }
    }

    /** Steps that run at the same time, each isolated; this step ends when all have. */
    suspend fun concurrently(build: Concurrent.() -> Unit) {
        val children = Concurrent().apply(build).children
        coroutineScope { children.forEach { (name, block) -> launch { isolated(name, block) } } }
    }

    /**
     * Work this run starts and does not await: it continues in the process scope, under its own background-time
     * hold, labelled with this run's trigger — and its entry point's `done` waits for it.
     */
    fun detached(block: suspend (Run) -> Unit) {
        runtime.detached(trigger, work, { block(Run(runtime, trigger)) })
    }

    /** A pass of [sequence], single-flight across the process. */
    suspend fun <R> run(sequence: Sequence<R>): Pass<R> = runtime.flights.run(sequence)

    /** A pass of [sequence], single-flight across the process, asking it [ask]: the pass that covers it gets it. */
    suspend fun <Q : Any, R> run(sequence: AskedSequence<Q, R>, ask: Q): Pass<R> = runtime.flights.run(sequence, ask)

    /**
     * Calls [next] until it answers `false` — a drain, one item at a time. Each call OUTLIVES this run: when the run is
     * cancelled — the operating system's time is up, or its pass yielded to a join — the call in flight runs on to its
     * end in the process scope, still this entry point's work, and no further one starts. For work the platform cannot
     * stop once submitted, like a photo-library import, whose own report records what it did.
     */
    suspend fun drain(next: suspend () -> Boolean) {
        do {
            val more = runtime.outliving(next)
        } while (more)
    }

    /**
     * Awaits the work the inline deliveries of [entry] started and did not await, since the last time it was awaited —
     * what a delivery the platform makes once persisted only partly before it returned (a staged download's record
     * write), and whose end a later delivery answers for (the session's drain report).
     */
    suspend fun awaitDetachedOf(entry: KClass<*>) = runtime.unawaited.await(entry.simpleName.toString())

    /**
     * A step that runs when the others are done **however they ended** — failed, or cancelled because time is up —
     * before the run answers the operating system: what a wake owes the next one, like re-arming it.
     */
    fun ensure(name: String, block: suspend () -> Unit) {
        ensured += name to block
    }

    internal suspend fun ensured() = withContext(NonCancellable) {
        ensured.forEach { (name, block) -> isolated(name, block) }
    }
}

/** The children of one [Steps.concurrently] step. */
class Concurrent internal constructor() {
    internal val children = mutableListOf<Pair<String, suspend () -> Unit>>()

    fun child(name: String, block: suspend () -> Unit) {
        children += name to block
    }
}
