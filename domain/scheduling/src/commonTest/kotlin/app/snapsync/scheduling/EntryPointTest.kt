@file:OptIn(ExperimentalCoroutinesApi::class)

package app.snapsync.scheduling

import app.snapsync.inbound.Trigger
import co.touchlab.kermit.Severity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class EntryPointTest {
    private class OnWork(world: World, val body: suspend (String) -> Unit) : EntryPoint<String>(world.runtime) {
        override suspend fun run(arg: String) = body(arg)
    }

    private class OnWake(world: World, val body: suspend (String) -> Unit) : GrantedEntryPoint<String>(world.runtime) {
        override suspend fun run(arg: String) = body(arg)
    }

    @Test
    fun `a run is labelled with its entry point - holds background time - and answers when it ends`() = runTest {
        val world = World(this)
        var trigger: String? = null
        val completion = CountingCompletion()
        OnWork(world) { trigger = currentCoroutineContext()[Trigger]?.label }.handle("x", completion)
        runCurrent()
        assertEquals("OnWork", trigger)
        assertEquals(1, completion.answers)
        assertEquals(listOf("OnWork"), world.backgroundTime.holds.map { it.label })
        assertTrue(world.backgroundTime.holds.single().ended)
    }

    @Test
    fun `a delivery that is itself a grant of time holds none`() = runTest {
        val world = World(this)
        val completion = CountingCompletion()
        OnWake(world) {}.handle("x", completion)
        runCurrent()
        assertEquals(1, completion.answers)
        assertTrue(world.backgroundTime.holds.isEmpty())
    }

    @Test
    fun `a process with no background time to hold runs and answers without one`() = runTest {
        val world = World(this, holdsTime = false)
        val completion = CountingCompletion()
        OnWork(world) {}.handle("x", completion)
        runCurrent()
        assertEquals(1, completion.answers)
        assertTrue(world.backgroundTime.holds.isEmpty())
    }

    @Test
    fun `a failing run is contained - reported as an error - and still answered`() = runTest {
        val world = World(this)
        val completion = CountingCompletion()
        OnWork(world) { error("boom") }.handle("x", completion)
        runCurrent()
        assertEquals(1, completion.answers)
        val line = world.log.lines.single { it.event == "run failed" }
        assertEquals(Severity.Error, line.severity)
    }

    @Test
    fun `an expiry cancels the run - and answers once the cancellation has unwound`() = runTest {
        val world = World(this)
        val completion = CountingCompletion()
        val unwound = CompletableDeferred<Unit>()
        val running = OnWake(world) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { delay(100.milliseconds) }
                unwound.complete(Unit)
            }
        }.handle("x", completion)
        runCurrent()
        running.expire()
        runCurrent()
        assertEquals(0, completion.answers, "answered before the unwind ended")
        advanceTimeBy(101.milliseconds)
        assertTrue(unwound.isCompleted)
        assertEquals(1, completion.answers)
        assertTrue("expired run unwound" in world.log.events(), "the unwind is measured from the expiry")
        advanceTimeBy(2.seconds)
        assertEquals(1, completion.answers, "answered twice")
        running.expire()
        assertEquals(1, completion.answers, "an expiry after the answer did something")
    }

    @Test
    fun `an unwind longer than the grace is answered at the grace - regardless`() = runTest {
        val world = World(this, grace = 1.seconds)
        val completion = CountingCompletion()
        val running = OnWake(world) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { delay(10.seconds) }
            }
        }.handle("x", completion)
        runCurrent()
        running.expire()
        running.expire()
        advanceTimeBy(1001.milliseconds)
        assertEquals(1, completion.answers)
        assertTrue("expiry grace ran out: answering before the run unwound" in world.log.events())
        advanceTimeBy(10.seconds)
        assertEquals(1, completion.answers)
    }

    @Test
    fun `a hold whose time is up on arrival is ended - and its run answered without running`() = runTest {
        val world = World(this)
        world.backgroundTime.upOnArrival = true
        val completion = CountingCompletion()
        var ran = false
        OnWork(world) { ran = true }.handle("x", completion)
        settle()
        assertEquals(1, completion.answers)
        assertTrue(world.backgroundTime.holds.single().ended)
        assertEquals(false, ran)
    }

    @Test
    fun `the end of the background time expires the run it was held for`() = runTest {
        val world = World(this)
        val completion = CountingCompletion()
        OnWork(world) { awaitCancellation() }.handle("x", completion)
        runCurrent()
        world.backgroundTime.holds.single().expiry.expire()
        runCurrent()
        assertEquals(1, completion.answers)
        assertTrue(world.backgroundTime.holds.single().ended)
    }

    @Test
    fun `an inline delivery runs on the delivering thread - labelled - and is contained`() = runTest {
        val world = World(this)
        var seen: String? = null
        class OnDelivery : InlineEntryPoint<Int>(world.runtime) {
            override fun run(arg: Int) {
                seen = world.label.current()
                check(arg > 0) { "refused" }
            }
        }
        val entry = OnDelivery()
        world.label.label = "outer"
        entry.deliver(1)
        assertEquals("OnDelivery", seen)
        entry.deliver(0)
        assertEquals("outer", world.label.current(), "the thread's own label was not restored")
        assertEquals("delivery failed", world.log.lines.single().event)
    }

    @Test
    fun `an answered delivery blocks for its answer - and a failure answers the declared one`() {
        kotlinx.coroutines.test.runTest {
            val world = World(this)
            class OnAsk(val fail: Boolean) : AnsweredEntryPoint<Int, String>(world.runtime, onFailure = "FAILED") {
                override suspend fun run(arg: Int): String {
                    check(!fail) { "refused" }
                    return "${currentCoroutineContext()[Trigger]}:${arg * 2}"
                }
            }
            assertEquals("OnAsk:4", OnAsk(fail = false).answer(2))
            assertEquals("FAILED", OnAsk(fail = true).answer(2))
            assertEquals(Severity.Error, world.log.lines.single().severity)
        }
    }
}
