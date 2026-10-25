package app.snapsync.scheduling

import app.snapsync.domain.program.InvalidProgramState
import app.snapsync.inbound.Trigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The process's single-flight runner of [Sequence]s (`docs/patterns.md`, "Sequences"). Owned by the [Runtime]: a
 * pass runs in the process scope, never in a waiter's, so a waiter whose time is up withdraws without cancelling
 * work others still wait for. A pass is cancelled only once **every** waiter of it has withdrawn — and a waiter
 * then gets its own cancellation, never another waiter's.
 */
internal class SingleFlight(private val runtime: Runtime) {
    private val lock = Mutex()
    private val flights = HashMap<String, Flight>()

    /** Requests a pass of [sequence] and awaits the one that covers this request. */
    suspend fun <R> run(sequence: Sequence<R>): Pass<R> {
        val trigger = currentCoroutineContext()[Trigger]
        val slot = lock.withLock { admit(sequence, trigger) }
        try {
            @Suppress("UNCHECKED_CAST")
            return slot.outcome.await() as Pass<R>
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { lock.withLock { slot.live().withdraw() } }
            throw cancelled
        }
    }

    /** Under [lock]: the slot this request waits on — the pass after the running one. */
    private fun admit(sequence: Sequence<*>, trigger: Trigger?): Slot {
        val flight = flights.getOrPut(sequence.key) { Flight(sequence).also { drive(it) } }
        val slot = flight.waiting()
        if (sequence.yieldsToJoin) flight.yieldTo(slot)
        slot.join(trigger)
        return slot
    }

    /** The driver of one flight: its passes one after the other, until none is asked for. */
    private fun drive(flight: Flight) = runtime.scope.launch {
        try {
            while (true) {
                val slot = lock.withLock { flight.take() } ?: break
                slot.pass.start()
                slot.pass.join()
                val outcome = outcomeOf(slot.pass)
                lock.withLock { flight.ended(slot) }
                slot.finish(outcome)
            }
        } finally {
            // The process scope is going away: no waiter is left waiting on a pass nothing will run.
            withContext(NonCancellable) { lock.withLock { flight.close() } }
        }
    }

    /** One key's state: the pass running and the one asked for after it. Read and written under [lock]. */
    private inner class Flight(val sequence: Sequence<*>) {
        private var running: Slot? = null
        var next: Slot? = null

        /** The slot a request now waits on: the pass after the running one. */
        fun waiting(): Slot = next ?: Slot(this).also { next = it }

        /** The running pass gives way to [slot]: cancelled, its waiters answered by [slot]'s. */
        fun yieldTo(slot: Slot) {
            val yielding = running ?: return
            running = null
            slot.absorb(yielding)
            yielding.pass.cancel(CancellationException("a request joined ${sequence.key}, which yields to it"))
        }

        /** The next pass to run — now the running one — or `null`, and then the flight is over. */
        fun take(): Slot? {
            val slot = next
            next = null
            running = slot
            if (slot == null) flights.remove(sequence.key)
            return slot
        }

        fun ended(slot: Slot) {
            if (running === slot) running = null
        }

        fun close() {
            listOfNotNull(running, next).forEach { it.settle(Pass.Cut) }
            if (flights[sequence.key] === this) flights.remove(sequence.key)
        }
    }

    /** One pass, as its waiters see it. Its work is created with it, and started only when its turn comes. */
    private inner class Slot(private val flight: Flight) {
        private val triggers = LinkedHashSet<String>()
        private var waiters = 0
        val outcome = CompletableDeferred<Pass<Any?>>()
        val pass: Deferred<Any?> = runtime.scope.async(start = CoroutineStart.LAZY) {
            val trigger = triggers.map(Trigger::of).reduceOrNull(Trigger::plus) ?: Trigger.of(flight.sequence.key)
            withContext(trigger) { flight.sequence.pass(Run(runtime, trigger)) }
        }

        /** Slots whose pass yielded to this one: answered with this one's outcome. */
        private val absorbed = mutableListOf<Slot>()
        private var forward: Slot? = null

        /** The slot that now answers this one's waiters. */
        fun live(): Slot = when (val successor = forward) {
            null -> this
            else -> successor.live()
        }

        fun join(trigger: Trigger?) {
            waiters++
            trigger?.let { triggers += it.names }
        }

        fun absorb(yielded: Slot) {
            yielded.forward = this
            absorbed += yielded
            waiters += yielded.waiters
            triggers += yielded.triggers
        }

        /** One waiter stopped waiting. A pass nobody waits for any more is not run, or stops. */
        fun withdraw() {
            waiters--
            if (waiters > 0) return
            if (flight.next === this) {
                flight.next = null
                settle(Pass.Cut)
            }
            pass.cancel(CancellationException("every waiter of ${flight.sequence.key} has stopped waiting"))
        }

        /** The pass ended with [result]: a value, a failure, or a cancellation. */
        fun finish(result: Result<Any?>) {
            if (forward != null) return
            result.fold(onSuccess = { settle(Pass.Completed(it)) }, onFailure = { fail(it) })
        }

        /** Answers this slot's waiters and, through the chain of yields, every slot that gave way to it. */
        fun settle(pass: Pass<Any?>) {
            outcome.complete(pass)
            absorbed.forEach { it.settle(pass) }
        }

        private fun fail(failure: Throwable) {
            if (failure is CancellationException) return settle(Pass.Cut)
            outcome.completeExceptionally(failure)
            absorbed.forEach { it.fail(failure) }
        }
    }
}

/** A finished pass's result as a value: its answer, its failure, or its cancellation. */
private suspend fun outcomeOf(pass: Deferred<Any?>): Result<Any?> = try {
    Result.success(pass.await())
} catch (fatal: InvalidProgramState) {
    throw fatal
} catch (failure: Throwable) {
    Result.failure(failure)
}
