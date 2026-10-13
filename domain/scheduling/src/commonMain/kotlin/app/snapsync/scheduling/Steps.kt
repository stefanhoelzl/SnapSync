package app.snapsync.scheduling

import app.snapsync.inbound.Log
import app.snapsync.inbound.Trigger
import app.snapsync.inbound.of
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs [block] as a list of steps (`docs/patterns.md`, "Steps"), under the [Runtime] of the run it is part of. Each
 * step block is written inline and makes exactly one call (`OneCallPerStep`); what a step does is that call's.
 */
suspend fun <T> steps(block: suspend Steps.() -> T): T {
    val context = currentCoroutineContext()
    val trigger = checkNotNull(context[Trigger]) { "steps run for a trigger: inside an entry point or a sequence" }
    val runtime = checkNotNull(context[RuntimeElement]) { "steps run under the process's runtime" }.runtime
    val steps = Steps(runtime, trigger)
    try {
        return steps.block()
    } finally {
        steps.ensured()
    }
}

/**
 * Awaits [block], run in the process scope: if the caller stops waiting — its time is up, or the pass it belongs to
 * yielded — [block] runs on to its end. For work the platform cannot stop once started, like a photo-library import,
 * whose own report records what it did.
 */
suspend fun <T> outliving(block: suspend () -> T): T {
    val runtime = checkNotNull(currentCoroutineContext()[RuntimeElement]) {
        "outliving work runs under the process's runtime"
    }.runtime
    val trigger = currentCoroutineContext()[Trigger]
    return runtime.scope.async(trigger ?: Trigger.of("outliving")) { block() }.await()
}

/** The steps of one run. */
class Steps internal constructor(private val runtime: Runtime, private val trigger: Trigger) {
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
     * hold, labelled with this run's trigger.
     */
    fun detached(block: suspend () -> Unit) = runtime.detached(trigger, block)

    /** A pass of [sequence], single-flight across the process. */
    suspend fun <R> run(sequence: Sequence<R>): Pass<R> = runtime.flights.run(sequence)

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
