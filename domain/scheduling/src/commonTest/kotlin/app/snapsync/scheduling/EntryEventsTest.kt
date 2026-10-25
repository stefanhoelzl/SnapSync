@file:OptIn(ExperimentalCoroutinesApi::class)

package app.snapsync.scheduling

import app.snapsync.inbound.Trigger
import app.snapsync.ports.EntryEvent
import co.touchlab.kermit.Severity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * What the runtime tells of each entry point's work (`docs/patterns.md`, "Entry events"): `delivered` as the operating
 * system hands it over, `answered` with its answer, and `done` once its run and everything it started have ended.
 */
class EntryEventsTest {
    private class OnWork(world: World, val body: suspend Steps.() -> Unit) : BackgroundEntryPoint<Unit>(world.runtime) {
        override suspend fun run(arg: Unit, on: Run) = on.steps { body() }
    }

    private class OnWake(world: World, val body: suspend () -> Unit) : GrantedEntryPoint<Unit>(world.runtime) {
        override suspend fun run(arg: Unit, on: Run) = body()
    }

    private class OnReport(world: World, val body: Delivery.() -> Unit) : InboundEntryPoint<Unit>(world.runtime) {
        override fun Delivery.run(arg: Unit) = body()
    }

    private class OnAsk(world: World, val body: suspend () -> String) :
        AnsweredEntryPoint<Unit, String>(world.runtime, onFailure = "FAILED") {
        override suspend fun run(arg: Unit, on: Run) = body()
    }

    @Test
    fun `a delivery is told delivered - answered and done - each beside its log line`() = runTest {
        val world = World(this)
        OnWork(world) {}.handle(Unit, CountingCompletion())
        runCurrent()
        assertEquals(listOf("delivered OnWork", "answered OnWork", "done OnWork COMPLETED"), world.entries.seen())
        assertEquals(listOf("delivered", "answered", "done"), world.log.events())
        val runs = world.entries.told.map { it.run }.toSet()
        assertEquals(1, runs.size, "one delivery is one run")
        assertTrue(world.log.lines.all { line -> line.fields.any { it.name == "entry" && it.value == "OnWork" } })
    }

    @Test
    fun `a delivery is told before it holds background time`() = runTest {
        val world = World(this)
        OnWork(world) {}.handle(Unit, CountingCompletion())
        assertEquals(
            1,
            world.backgroundTime.holds.single().toldBefore,
            "the hold was begun before the delivery was told",
        )
    }

    @Test
    fun `done waits for the work a run detached - and for what that detached in turn`() = runTest {
        val world = World(this)
        val inner = CompletableDeferred<Unit>()
        OnWork(world) {
            detached { on ->
                on.steps { detached { inner.await() } }
            }
        }.handle(Unit, CountingCompletion())
        runCurrent()
        assertEquals(listOf("delivered OnWork", "answered OnWork"), world.entries.seen(), "done before its work ended")
        inner.complete(Unit)
        runCurrent()
        assertEquals("done OnWork COMPLETED", world.entries.seen().last())
        assertEquals(1, world.entries.told.count { it is EntryEvent.Done }, "a detached run has no done of its own")
    }

    @Test
    fun `a failure anywhere in the work makes its done a failure - told as a warning`() = runTest {
        val world = World(this)
        OnWork(world) { detached { error("boom") } }.handle(Unit, CountingCompletion())
        runCurrent()
        assertEquals("done OnWork FAILED", world.entries.seen().last())
        assertEquals(Severity.Warn, world.log.lines.last().severity)
        OnWork(world) { error("boom") }.handle(Unit, CountingCompletion())
        runCurrent()
        assertEquals("done OnWork FAILED", world.entries.seen().last())
    }

    @Test
    fun `an expired run is answered at the grace - and done only once it has unwound`() = runTest {
        val world = World(this, grace = 1.seconds)
        val running = OnWake(world) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) { delay(5.seconds) }
            }
        }.handle(Unit, CountingCompletion())
        runCurrent()
        running.expire()
        advanceTimeBy(1001.milliseconds)
        assertEquals(listOf("delivered OnWake", "answered OnWake"), world.entries.seen())
        advanceTimeBy(5.seconds)
        assertEquals("done OnWake EXPIRED", world.entries.seen().last())
    }

    @Test
    fun `work started for no delivery is told done - and nothing before it`() = runTest {
        val world = World(this)
        world.runtime.detached("OnArm") {}
        runCurrent()
        assertEquals(listOf("done OnArm COMPLETED"), world.entries.seen())
    }

    @Test
    fun `work a run starts by name is that run's work`() = runTest {
        val world = World(this)
        val arm = CompletableDeferred<Unit>()
        OnWork(world) { world.runtime.detached("OnArm") { arm.await() } }.handle(Unit, CountingCompletion())
        runCurrent()
        assertEquals(listOf("delivered OnWork", "answered OnWork"), world.entries.seen())
        arm.complete(Unit)
        runCurrent()
        assertEquals(listOf("delivered OnWork", "answered OnWork", "done OnWork COMPLETED"), world.entries.seen())
    }

    @Test
    fun `an inline delivery is answered as it returns - and done once what it detached has ended`() = runTest {
        val world = World(this)
        val topUp = CompletableDeferred<Unit>()
        var job: kotlinx.coroutines.Job? = null
        OnReport(world) { job = detached { topUp.await() } }.deliver(Unit)
        assertEquals(listOf("delivered OnReport", "answered OnReport"), world.entries.seen())
        runCurrent()
        assertTrue(job?.isActive == true, "the detached work runs on, its job the caller's to wait for")
        topUp.complete(Unit)
        runCurrent()
        assertEquals("done OnReport COMPLETED", world.entries.seen().last())
    }

    @Test
    fun `steps an inline delivery detaches run as its work - labelled with its trigger`() = runTest {
        val world = World(this)
        val topUp = CompletableDeferred<Unit>()
        var ranFor: String? = null
        OnReport(world) {
            detachedSteps {
                required { ranFor = currentCoroutineContext()[Trigger]?.label }
                required { topUp.await() }
            }
        }.deliver(Unit)
        runCurrent()
        assertEquals("OnReport", ranFor)
        assertEquals(listOf("delivered OnReport", "answered OnReport"), world.entries.seen(), "not done while it runs")
        topUp.complete(Unit)
        runCurrent()
        assertEquals("done OnReport COMPLETED", world.entries.seen().last())
    }

    @Test
    fun `an answered delivery is done once what it detached has ended`() = runTest {
        val world = World(this)
        val tail = CompletableDeferred<Unit>()
        OnAsk(world) {
            world.runtime.detached("OnTail") { tail.await() }
            "ok"
        }.answer(Unit)
        assertEquals(listOf("delivered OnAsk", "answered OnAsk"), world.entries.seen())
        tail.complete(Unit)
        runCurrent()
        assertEquals("done OnAsk COMPLETED", world.entries.seen().last())
    }

    @Test
    fun `outliving work its caller stopped waiting for is still the delivery's work`() = runTest {
        val world = World(this)
        OnWork(world) {
            required {
                world.runtime.outliving { delay(5.seconds) }
            }
        }.handle(Unit, CountingCompletion())
        runCurrent()
        world.backgroundTime.holds.single().expiry.expire()
        runCurrent()
        assertEquals(
            listOf("delivered OnWork", "answered OnWork"),
            world.entries.seen(),
            "done before the import ended",
        )
        advanceTimeBy(5001.milliseconds)
        assertEquals("done OnWork EXPIRED", world.entries.seen().last())
    }

    @Test
    fun `outliving work that fails makes the delivery's done a failure`() = runTest {
        val world = World(this)
        OnWork(world) {
            isolated("import") { world.runtime.outliving { error("the library refused") } }
        }.handle(Unit, CountingCompletion())
        runCurrent()
        assertEquals("done OnWork FAILED", world.entries.seen().last())
    }

    @Test
    fun `work a sequence's pass detaches is its own - named by the pass`() = runTest {
        val world = World(this)
        val sequence = object : Sequence<Unit>("TopUp") {
            override suspend fun pass(on: Run) = on.steps { detached {} }
        }
        OnWork(world) { run(sequence) }.handle(Unit, CountingCompletion())
        runCurrent()
        val delivered = world.entries.told.filterIsInstance<EntryEvent.Delivered>().single()
        val done = world.entries.told.filterIsInstance<EntryEvent.Done>()
        assertEquals(listOf("OnWork", "OnWork"), done.map { it.entry }, "the pass is labelled by who joined it")
        assertEquals(1, done.count { it.run == delivered.run }, "the pass's own work is not the delivery's")
    }

    @Test
    fun `a delivery of a kind with several says which it is`() = runTest {
        val world = World(this)
        class OnPick : InboundEntryPoint<String>(world.runtime) {
            override fun about(arg: String) = arg

            override fun Delivery.run(arg: String) = Unit
        }
        OnPick().deliver("ConfirmCreate")
        assertEquals("ConfirmCreate", (world.entries.told.first() as EntryEvent.Delivered).about)
        val line = world.log.lines.first()
        assertEquals(listOf("entry" to "OnPick", "about" to "ConfirmCreate"), line.fields.map { it.name to it.value })
    }

    @Test
    fun `a command started alongside a delivery is its work - and holds no background time`() = runTest {
        val world = World(this)
        val mint = CompletableDeferred<Unit>()
        OnReport(world) {
            val handled = kotlinx.coroutines.Job()
            joining(handled)
            // Elsewhere — as a screen's container runs an intent — in the delivery's context.
            world.runtime.scope.launch(context) {
                world.runtime.alongside("tap.create") { mint.await() }
                handled.complete()
            }
        }.deliver(Unit)
        runCurrent()
        assertTrue(world.backgroundTime.holds.isEmpty(), "a person's command took background time")
        assertEquals(listOf("delivered OnReport", "answered OnReport"), world.entries.seen(), "done before the mint")
        mint.complete(Unit)
        runCurrent()
        assertEquals("done OnReport COMPLETED", world.entries.seen().last())
        world.runtime.alongside("tap.share") {}
        runCurrent()
        assertEquals("done tap.share COMPLETED", world.entries.seen().last(), "outside any delivery it is its own")
    }

    @Test
    fun `every delivery is a run of its own`() = runTest {
        val world = World(this)
        OnWork(world) {}.handle(Unit, CountingCompletion())
        OnWork(world) {}.handle(Unit, CountingCompletion())
        runCurrent()
        assertEquals(2, world.entries.told.filterIsInstance<EntryEvent.Delivered>().map { it.run }.toSet().size)
    }
}
