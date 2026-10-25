@file:OptIn(ExperimentalCoroutinesApi::class, CompositionOnly::class)

package app.snapsync.scheduling

import app.snapsync.inbound.CompositionOnly
import app.snapsync.inbound.Trigger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class StepsTest {
    private class OnSteps(
        world: World,
        val body: suspend Steps.() -> Unit,
    ) : BackgroundEntryPoint<Unit>(world.runtime) {
        override suspend fun run(arg: Unit, on: Run) = on.steps { body() }
    }

    @Test
    fun `isolated and concurrent steps contain their failures - and the run goes on`() = runTest {
        val world = World(this)
        val done = mutableListOf<String>()
        OnSteps(world) {
            isolated("first") { error("first failed") }
            concurrently {
                child("a") { done += "a" }
                child("b") { error("b failed") }
            }
            done += required { "after" }
        }.handle(Unit, CountingCompletion())
        settle()
        assertEquals(listOf("a", "after"), done)
        assertEquals(
            listOf("first", "b"),
            world.log.lines.filter { it.event == "step failed" }.map { it.fields[0].value },
        )
    }

    @Test
    fun `an ensured step runs however the others ended — failed or cut by expiry`() = runTest {
        val world = World(this)
        val ensured = mutableListOf<String>()
        OnSteps(world) {
            ensure("rearm") { ensured += "after failure" }
            required { error("required failed") }
        }.handle(Unit, CountingCompletion())
        OnSteps(world) {
            ensure("rearm") { ensured += "after expiry" }
            ensure("broken") { error("ensured failed") }
            required { awaitCancellation() }
        }.handle(Unit, CountingCompletion())
        runCurrent()
        world.backgroundTime.holds.last().expiry.expire() // the operating system's time is up for the second run
        settle()
        assertEquals(listOf("after failure", "after expiry"), ensured)
        assertTrue("step failed" in world.log.events())
    }

    @Test
    fun `detached work outlives the run - labelled with its trigger - under its own hold`() = runTest {
        val world = World(this)
        val release = CompletableDeferred<Unit>()
        var trigger: String? = null
        val completion = CountingCompletion()
        OnSteps(world) {
            detached {
                release.await()
                trigger = currentCoroutineContext()[Trigger]?.label
            }
        }.handle(Unit, completion)
        runCurrent()
        assertEquals(1, completion.answers, "the run waited for its detached work")
        release.complete(Unit)
        runCurrent()
        assertEquals("OnSteps", trigger)
        assertEquals(2, world.backgroundTime.holds.size)
        world.runtime.detached("OnArm") { trigger = currentCoroutineContext()[Trigger]?.label }
        runCurrent()
        assertEquals("OnArm", trigger)
    }

    @Test
    fun `a sequence runs single-flight from inside the steps`() = runTest {
        val world = World(this)
        val sequence = object : Sequence<String>("s") {
            override suspend fun pass(on: Run) = "ran"
        }
        var result: Pass<String>? = null
        OnSteps(world) { result = run(sequence) }.handle(Unit, CountingCompletion())
        settle()
        assertEquals("ran", (result as Pass.Completed<*>).result)
    }

    @Test
    fun `outliving work runs on when its caller stops waiting`() = runTest {
        val world = World(this)
        var finished: String? = null
        OnSteps(world) {
            required {
                world.runtime.outliving {
                    delay(5.seconds)
                    finished = currentCoroutineContext()[Trigger]?.label
                }
            }
        }.handle(Unit, CountingCompletion())
        runCurrent()
        world.backgroundTime.holds.single().expiry.expire() // the operating system's time is up
        settle()
        assertEquals("OnSteps", finished)
        val bare = async {
            world.runtime.outliving { currentCoroutineContext()[Trigger]?.label }
        }
        assertEquals("outliving", bare.await())
    }

    @Test
    fun `an observed flow hands each emission to its entry point - one run at a time`() = runTest {
        val world = World(this)
        val seen = mutableListOf<String>()
        class OnChange : BackgroundEntryPoint<Int>(world.runtime) {
            override suspend fun run(arg: Int, on: Run) {
                seen += "start $arg"
                yield()
                seen += "end $arg"
            }
        }
        Observation(world.runtime).follow(flowOf(1, 2), OnChange())
        settle()
        assertEquals(listOf("start 1", "end 1", "start 2", "end 2"), seen)
    }

    @Test
    fun `work nobody awaits that fails reaches the process scope's handler`() = runTest {
        val world = World(this)
        class OnChange : BackgroundEntryPoint<Int>(world.runtime) {
            override suspend fun run(arg: Int, on: Run) = Unit
        }
        Observation(world.runtime).follow(flow<Int> { error("the source failed") }, OnChange())
        settle()
        assertTrue("unawaited work failed" in world.log.events())
    }

    @Test
    fun `a task on the lane carries its run's trigger in the thread's label`() = runTest {
        val world = World(this)
        OnSteps(world) { required { yield() } }.handle(Unit, CountingCompletion())
        settle()
        assertTrue("OnSteps" in world.label.seen)
        assertEquals(null, world.label.label)
    }

    @Test
    fun `a lane that schedules no delays of its own is labelled too`() = runTest {
        val test = StandardTestDispatcher(testScheduler)
        val plain = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) = test.dispatch(context, block)
        }
        val label = FakeThreadLabel()
        val runtime =
            Runtime(
                backgroundScope.coroutineContext[kotlinx.coroutines.Job],
                plain,
                RuntimePorts(FakeBackgroundTime(), label, RecordingEntryEvents()),
                1.seconds,
            )
        val completion = CountingCompletion()
        class OnPlain : BackgroundEntryPoint<Unit>(runtime) {
            override suspend fun run(arg: Unit, on: Run) = Unit
        }
        OnPlain().handle(Unit, completion)
        runCurrent()
        assertEquals(1, completion.answers)
        assertTrue("OnPlain" in label.seen)
    }
}
