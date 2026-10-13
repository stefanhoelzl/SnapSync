@file:OptIn(ExperimentalCoroutinesApi::class)

package app.snapsync.scheduling

import app.snapsync.inbound.Trigger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SingleFlightTest {
    /** A sequence whose passes wait for the test to [release] them, recording each one's trigger. */
    private class Gated(yields: Boolean = false) : Sequence<Int>("gated", yields) {
        val passes = mutableListOf<String?>()
        var gate = CompletableDeferred<Unit>()
        var fail = false

        override suspend fun pass(): Int {
            passes += currentCoroutineContext()[Trigger]?.label
            gate.await()
            check(!fail) { "the pass failed" }
            return passes.size
        }

        fun release() {
            gate.complete(Unit)
            gate = CompletableDeferred()
        }
    }

    /** A waiter, in a scope of its own: a failing pass fails the waiter, not the test. */
    private fun TestScope.request(world: World, name: String, sequence: Sequence<Int>) =
        CoroutineScope(coroutineContext + SupervisorJob()).async(Trigger.of(name) + RuntimeElement(world.runtime)) {
            world.runtime.flights.run(sequence)
        }

    @Test
    fun `a join during a run makes exactly one more pass - however many join`() = runTest {
        val world = World(this)
        val gated = Gated()
        val first = request(world, "A", gated)
        runCurrent()
        val joiners = listOf("B", "C", "D").map { request(world, it, gated) }
        runCurrent()
        gated.release()
        runCurrent()
        gated.release()
        runCurrent()
        assertEquals(listOf<String?>("A", "B+C+D"), gated.passes)
        assertEquals(1, (first.await() as Pass.Completed<*>).result)
        joiners.forEach { assertEquals(2, (it.await() as Pass.Completed<*>).result) }
        // The flight is over: the next request starts a fresh one.
        val later = request(world, "E", gated)
        runCurrent()
        gated.release()
        assertEquals(3, (later.await() as Pass.Completed<*>).result)
    }

    @Test
    fun `a waiter whose time is up withdraws without cancelling a pass another waits for`() = runTest {
        val world = World(this)
        val gated = Gated()
        val first = request(world, "A", gated)
        runCurrent()
        // B and C wait for the next pass; B's time is up before it runs.
        val b = request(world, "B", gated)
        val c = request(world, "C", gated)
        runCurrent()
        b.cancel()
        runCurrent()
        gated.release()
        runCurrent()
        gated.release()
        runCurrent()
        assertTrue(first.isCompleted)
        assertEquals(2, (c.await() as Pass.Completed<*>).result)
        assertTrue(b.isCancelled)
    }

    @Test
    fun `a pass every waiter has withdrawn from is cancelled - and one nobody waits for never runs`() = runTest {
        val world = World(this)
        val gated = Gated()
        val first = request(world, "A", gated)
        runCurrent()
        val second = request(world, "B", gated)
        runCurrent()
        second.cancel()
        first.cancel()
        runCurrent()
        assertEquals(listOf<String?>("A"), gated.passes, "a pass nobody waited for ran")
        val later = request(world, "C", gated)
        runCurrent()
        gated.release()
        assertEquals(2, (later.await() as Pass.Completed<*>).result)
    }

    @Test
    fun `a sequence that yields to a join answers the running pass's waiters with the next pass`() = runTest {
        val world = World(this)
        val gated = Gated(yields = true)
        val stalled = request(world, "A", gated)
        runCurrent()
        val joiner = request(world, "B", gated)
        runCurrent()
        gated.release()
        runCurrent()
        assertEquals(listOf<String?>("A", "A+B"), gated.passes)
        assertEquals(2, (stalled.await() as Pass.Completed<*>).result)
        assertEquals(2, (joiner.await() as Pass.Completed<*>).result)
    }

    @Test
    fun `a pass that yields twice answers the first pass's waiters too`() = runTest {
        val world = World(this)
        val gated = Gated(yields = true)
        val first = request(world, "A", gated)
        runCurrent()
        val second = request(world, "B", gated) // A yields to B's pass
        runCurrent()
        val third = request(world, "C", gated) // which yields in turn to C's
        runCurrent()
        gated.release()
        runCurrent()
        assertEquals(listOf<String?>("A", "A+B", "A+B+C"), gated.passes)
        listOf(first, second, third).forEach { assertEquals(3, (it.await() as Pass.Completed<*>).result) }
    }

    @Test
    fun `a failure after two yields fails the first pass's waiters too`() = runTest {
        val world = World(this)
        val gated = Gated(yields = true)
        val first = request(world, "A", gated)
        runCurrent()
        request(world, "B", gated)
        runCurrent()
        request(world, "C", gated)
        runCurrent()
        gated.fail = true
        gated.release()
        runCurrent()
        assertFailsWith<IllegalStateException> { first.await() }
    }

    @Test
    fun `a waiter of a yielded pass may withdraw too`() = runTest {
        val world = World(this)
        val gated = Gated(yields = true)
        val stalled = request(world, "A", gated)
        runCurrent()
        val joiner = request(world, "B", gated)
        runCurrent()
        stalled.cancel()
        runCurrent()
        gated.release()
        assertEquals(2, (joiner.await() as Pass.Completed<*>).result)
    }

    @Test
    fun `a failing pass fails every waiter of it`() = runTest {
        val world = World(this)
        val gated = Gated().apply { fail = true }
        val a = request(world, "A", gated)
        runCurrent()
        gated.release()
        runCurrent()
        assertFailsWith<IllegalStateException> { a.await() }
    }

    @Test
    fun `a request with no trigger runs under the sequence's own name`() = runTest {
        val world = World(this)
        val gated = Gated()
        val request = async(RuntimeElement(world.runtime)) { world.runtime.flights.run(gated) }
        runCurrent()
        gated.release()
        request.await()
        assertEquals(listOf<String?>("gated"), gated.passes)
    }

    @Test
    fun `when the process scope goes away its waiters are told the pass was cut`() = runTest {
        val world = World(this)
        val stuck = object : Sequence<Int>("stuck") {
            override suspend fun pass(): Int = awaitCancellation()
        }
        // On the test's scheduler, so both have joined the flight before the scope goes away.
        val a = request(world, "A", stuck)
        val b = request(world, "B", stuck)
        runCurrent()
        world.runtime.scope.cancel()
        runCurrent()
        assertIs<Pass.Cut>(a.await())
        assertIs<Pass.Cut>(b.await())
    }
}
