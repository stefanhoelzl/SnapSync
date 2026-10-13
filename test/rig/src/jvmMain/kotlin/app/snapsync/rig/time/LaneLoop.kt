@file:OptIn(InternalCoroutinesApi::class, ExperimentalCoroutinesApi::class)

package app.snapsync.rig.time

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Delay
import kotlinx.coroutines.DisposableHandle
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import java.util.PriorityQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.coroutines.CoroutineContext
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant
import kotlin.time.TimeSource

/**
 * **The JVM host's lanes** (`docs/testing.md`, "How a test waits"): every dispatcher the app and the rig run on, as one
 * queue of tasks and one thread per lane — so a task of the composition lane runs on the composition lane's thread,
 * as on the phone — under one rule for whose turn it is ([Interleaving]) and one source of time ([LoopTime]).
 *
 * What it knows that no thread pool does is the one thing a test waiting on events needs: **when the app is settled**
 * — no task running, none runnable at the loop's now, and no outside work counted ([outside]) — which it reports once
 * per quiet spell, with the next timer, to [onSettled]. Under [LoopTime.MOCKED] time moves only when [advanceTo] moves
 * it, firing each timer at its own instant and settling between them, so a five-second grace costs no wall time.
 *
 * It also holds the lane rules a review otherwise holds: work dispatched onto an app lane from a thread the loop does not
 * own, while no outside work is counted, is reported to [onViolation] — a resumption the loop could not have known
 * of, which is exactly the work that would make a `Settled` lie.
 */
class LaneLoop(
    private val time: LoopTime,
    private val interleaving: Interleaving,
    /** The clock every port face reads: the loop moves it under [LoopTime.MOCKED], and only reads it otherwise. */
    private val clock: LoopClock,
    private val onSettled: (Settled) -> Unit = {},
    private val onViolation: (String) -> Unit = {},
) : AutoCloseable {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val lanes = mutableListOf<Lane>()
    private val random = (interleaving as? Interleaving.Shuffled)?.let { Random(it.seed) }
    private val outsideWork = mutableMapOf<Long, String>()
    private val quietWaiters = mutableListOf<CompletableDeferred<Unit>>()
    private val realStart = TimeSource.Monotonic.markNow()
    private val realAnchor = clock.now.toEpochMilliseconds()
    private var virtualNow = clock.now.toEpochMilliseconds()
    private var sequence = 0L
    private var running = 0
    private var chosen: Task? = null
    private var generation = 0L

    // An empty loop has settled on nothing: the first report follows the first work.
    private var settledGeneration = 0L
    private var advancing = false
    private var closed = false

    /**
     * A lane: a dispatcher whose every task runs on its own thread, in its turn. [checked] lanes are the app's — a
     * dispatch onto one from outside the loop is a violation unless outside work is counted; the rig's own lanes are
     * not checked, since a server thread resuming the rig's act is the act's own reply.
     */
    fun lane(name: String, checked: Boolean): CoroutineDispatcher = lock.withLock {
        check(!closed) { "the loop is closed" }
        Lane(name, checked).also { lane ->
            lanes += lane
            lane.thread.start()
        }
    }

    /** Counts [block] as outside work — work the app waits on that no lane runs — so the loop is not settled while it runs. */
    suspend fun <T> outside(what: String, block: suspend () -> T): T {
        val token = enter(what)
        try {
            return block()
        } finally {
            exit(token)
        }
    }

    /** [outside], for a caller that is not a coroutine. */
    fun <T> outsideBlocking(what: String, block: () -> T): T {
        val token = enter(what)
        try {
            return block()
        } finally {
            exit(token)
        }
    }

    /** Whether the calling thread is one of the loop's lanes. */
    fun onLoop(): Boolean = CURRENT.get()?.loop === this

    /** Whether code on the calling thread may reach the app: on a lane, or while outside work is counted. */
    fun mayReachApp(): Boolean = onLoop() || lock.withLock { outsideWork.isNotEmpty() }

    /** Reports a violation of the lane rules on the calling thread, unless the calling thread may reach the app. */
    fun checkReach(what: String) {
        if (!mayReachApp()) onViolation("$what on ${Thread.currentThread().name}, a thread the loop does not own")
    }

    /**
     * Moves the mocked clock to [target], firing every timer due on the way at its own instant: each step waits for
     * the loop to settle before the next timer fires, so what one timer starts is done before the next one's instant.
     * No `Settled` is reported until the clock has reached [target]. A [target] before the loop's now moves the clock
     * back, and fires nothing.
     */
    suspend fun advanceTo(target: Instant) {
        check(time == LoopTime.MOCKED) { "the clock moves by itself under real time: nothing to advance" }
        val to = target.toEpochMilliseconds()
        lock.withLock { advancing = true }
        try {
            while (true) {
                awaitQuiet()
                val stepped = lock.withLock {
                    val next = earliest()
                    if (next == null || next > to) {
                        setNow(to)
                        false
                    } else {
                        setNow(maxOf(virtualNow, next))
                        true
                    }
                }
                if (!stepped) break
            }
        } finally {
            lock.withLock {
                advancing = false
                generation++
                changed.signalAll()
            }
            reportIfQuiet()
        }
    }

    /** [advanceTo] the loop's now plus [by]. */
    suspend fun advance(by: Duration) = advanceTo(Instant.fromEpochMilliseconds(lock.withLock { virtualNow }) + by)

    /** Suspends until the loop is quiet: no task running, none runnable, no outside work. */
    suspend fun awaitQuiet() {
        val waiter = lock.withLock {
            if (quiet()) return
            CompletableDeferred<Unit>().also {
                quietWaiters += it
                changed.signalAll()
            }
        }
        waiter.await()
    }

    /** Why the loop is not settled, for a test that waited too long: what runs, what is queued, what is outside. */
    fun report(): String = lock.withLock {
        buildString {
            append("now=${nowInstant()}, advancing=$advancing, ")
            append("outside=${outsideWork.values.toList()}, ")
            append(
                lanes.joinToString(prefix = "lanes=[", postfix = "]") { lane ->
                    "${lane.name}(running=${lane.running}, queued=${lane.queue.size}, " +
                        "next=${lane.queue.peek()?.at?.let { at -> instantOf(at) }})"
                },
            )
        }
    }

    override fun close() {
        val threads = lock.withLock {
            closed = true
            changed.signalAll()
            lanes.map { it.thread }
        }
        threads.filter { it !== Thread.currentThread() }.forEach { it.join(JOIN_MILLIS) }
    }

    private fun enter(what: String): Long = lock.withLock {
        val token = ++sequence
        outsideWork[token] = what
        generation++
        token
    }

    private fun exit(token: Long) {
        lock.withLock {
            outsideWork.remove(token)
            generation++
            changed.signalAll()
        }
        reportIfQuiet()
    }

    /** Runs a lane's thread: its tasks in its turns, and the settled report when it finds the loop quiet. */
    private fun work(lane: Lane) {
        CURRENT.set(lane)
        while (true) {
            val next = lock.withLock { nextFor(lane) } ?: return
            when (next) {
                is Next.Run -> run(lane, next.task)
                is Next.Report -> report(next.settled)
            }
        }
    }

    /** What [lane]'s thread does next, waiting while there is nothing; `null` once the loop closes. */
    private fun nextFor(lane: Lane): Next? {
        while (!closed) {
            take(lane)?.let { return Next.Run(it) }
            settledNow()?.let { return Next.Report(it) }
            // Finding the loop quiet resumes whoever awaited it, which may queue work on this very lane: look again
            // before sleeping, or this thread sleeps on its own signal.
            take(lane)?.let { return Next.Run(it) }
            await()
        }
        return null
    }

    private fun run(lane: Lane, task: Task) {
        try {
            task.block.run()
        } catch (failure: Throwable) {
            onViolation("a task of lane ${lane.name} threw: $failure")
        } finally {
            lock.withLock {
                lane.running = false
                running--
                generation++
                changed.signalAll()
            }
        }
    }

    private fun report(settled: Settled) {
        onSettled(settled)
    }

    private fun reportIfQuiet() {
        lock.withLock { settledNow() }?.let(::report)
    }

    /** The next task of [lane] when it is that lane's turn, marked running. */
    private fun take(lane: Lane): Task? {
        val now = nowMs()
        val task = when (interleaving) {
            Interleaving.Parallel -> lane.queue.peek()?.takeIf { !lane.running && it.at <= now }
            else -> if (running > 0) null else turn(now)?.takeIf { it.lane === lane }
        } ?: return null
        lane.queue.remove(task)
        if (chosen === task) chosen = null
        lane.running = true
        running++
        return task
    }

    /** Whose turn it is: the one task chosen among the lanes' runnable heads, kept until its lane takes it. */
    private fun turn(now: Long): Task? {
        chosen?.let { return it }
        val runnable = lanes.mapNotNull { lane -> lane.queue.peek()?.takeIf { it.at <= now } }
        if (runnable.isEmpty()) return null
        val pick = random?.let { runnable[it.nextInt(runnable.size)] } ?: runnable.minWith(TASK_ORDER)
        chosen = pick
        // The chosen lane's thread may be asleep: wake every thread, and the chosen one takes its turn.
        changed.signalAll()
        return pick
    }

    /** A settled report, once per quiet spell: no task running or runnable, no outside work, no advance under way. */
    private fun settledNow(): Settled? {
        if (!quiet()) return null
        quietWaiters.forEach { it.complete(Unit) }
        quietWaiters.clear()
        if (advancing || settledGeneration == generation) return null
        settledGeneration = generation
        return Settled(nowInstant(), earliest()?.let(::instantOf))
    }

    private fun quiet(): Boolean {
        if (running > 0 || outsideWork.isNotEmpty()) return false
        val now = nowMs()
        return lanes.none { lane -> lane.queue.peek()?.let { it.at <= now } == true }
    }

    private fun await() {
        val wait = if (time == LoopTime.REAL) earliest()?.let { (it - nowMs()).coerceAtLeast(1) } else null
        if (wait == null) changed.await() else changed.await(wait, TimeUnit.MILLISECONDS)
    }

    private fun earliest(): Long? = lanes.mapNotNull { it.queue.peek()?.at }.minOrNull()

    private fun setNow(ms: Long) {
        virtualNow = ms
        clock.now = Instant.fromEpochMilliseconds(ms)
        generation++
        changed.signalAll()
    }

    private fun nowMs(): Long = when (time) {
        LoopTime.MOCKED -> virtualNow
        LoopTime.REAL -> realAnchor + realStart.elapsedNow().inWholeMilliseconds
    }

    private fun nowInstant(): Instant = when (time) {
        LoopTime.MOCKED -> Instant.fromEpochMilliseconds(virtualNow)
        LoopTime.REAL -> clock.now
    }

    /** A queue time as the clock reads it: the instant itself when mocked, so far ahead of the clock when real. */
    private fun instantOf(at: Long): Instant = when (time) {
        LoopTime.MOCKED -> Instant.fromEpochMilliseconds(at)
        LoopTime.REAL -> clock.now + (at - nowMs()).milliseconds
    }

    /** Queues [block] on [lane] after [delayMillis]; after [close] nothing is queued, since nothing would run it. */
    private fun enqueue(lane: Lane, delayMillis: Long, block: Runnable): Task = lock.withLock {
        val at = nowMs().let { now -> if (delayMillis >= Long.MAX_VALUE - now) Long.MAX_VALUE else now + delayMillis }
        Task(at, ++sequence, lane, block).also {
            if (closed) return@also
            lane.queue += it
            generation++
            changed.signalAll()
        }
    }

    private fun cancel(task: Task) {
        lock.withLock {
            if (task.lane.queue.remove(task)) {
                if (chosen === task) chosen = null
                generation++
                changed.signalAll()
            }
        }
        reportIfQuiet()
    }

    private inner class Lane(val name: String, val checked: Boolean) : CoroutineDispatcher(), Delay {
        val loop: LaneLoop get() = this@LaneLoop
        val queue = PriorityQueue(TASK_ORDER)
        var running = false
        val thread = Thread({ work(this) }, "loop-$name").apply { isDaemon = true }

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (checked) checkReach("work dispatched onto lane $name")
            enqueue(this, 0, block)
        }

        override fun scheduleResumeAfterDelay(timeMillis: Long, continuation: CancellableContinuation<Unit>) {
            val task = enqueue(this, timeMillis.coerceAtLeast(0)) { with(continuation) { resumeUndispatched(Unit) } }
            continuation.invokeOnCancellation { cancel(task) }
        }

        override fun invokeOnTimeout(timeMillis: Long, block: Runnable, context: CoroutineContext): DisposableHandle {
            val task = enqueue(this, timeMillis.coerceAtLeast(0), block)
            return DisposableHandle { cancel(task) }
        }

        override fun toString() = "loop-$name"
    }

    private class Task(val at: Long, val seq: Long, val lane: Lane, val block: Runnable)

    private sealed interface Next {
        class Run(val task: Task) : Next

        class Report(val settled: Settled) : Next
    }

    private companion object {
        val CURRENT = ThreadLocal<Lane?>()
        val TASK_ORDER: Comparator<Task> = compareBy<Task>({ it.at }, { it.seq })
        const val JOIN_MILLIS = 2_000L
    }
}

/** The clock a loop moves, or reads: the clock mock's operator face, as the host hands it over. */
interface LoopClock {
    var now: Instant
}

/** Where the loop's time comes from. */
enum class LoopTime {
    /** The clock mock's: a timer fires when [LaneLoop.advanceTo] reaches its instant, and never by waiting. */
    MOCKED,

    /** The wall clock's, as before the loop: a timer fires after its real duration, and the clock mock stands still. */
    REAL,
}

/** How the lanes take turns. */
sealed interface Interleaving {
    /** One task at a time, the earliest first (by time, then by when it was queued): the same order on every run. */
    data object Turns : Interleaving

    /** One task at a time, each drawn among the lanes' runnable heads from [seed]: a failing order replays from its seed. */
    data class Shuffled(val seed: Long) : Interleaving

    /** Every lane on its own thread at once — the phone's concurrency, to hunt a race. Not deterministic. */
    data object Parallel : Interleaving

    companion object {
        /** `turns` (the default) · `shuffled:<seed>` · `parallel`. */
        fun parse(text: String?): Interleaving = when {
            text.isNullOrBlank() || text == "turns" -> Turns
            text == "parallel" -> Parallel
            text.startsWith("shuffled:") -> Shuffled(text.removePrefix("shuffled:").toLong())
            else -> throw IllegalArgumentException("interleaving must be turns|shuffled:<seed>|parallel, was '$text'")
        }
    }
}

/** The loop is settled: nothing runs or is runnable at [now], and the next timer, if any, is due at [nextTimer]. */
data class Settled(val now: Instant, val nextTimer: Instant?)
