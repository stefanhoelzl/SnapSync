package app.snapsync.rig.time

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class LaneLoopTest {
    private val start = Instant.parse("2026-06-01T12:00:00Z")
    private val clock = object : LoopClock {
        @Volatile override var now: Instant = start
    }
    private val settled = LinkedBlockingQueue<Settled>()
    private val violations: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val loops = mutableListOf<LaneLoop>()

    private fun loop(time: LoopTime = LoopTime.MOCKED, interleaving: Interleaving = Interleaving.Turns) =
        LaneLoop(time, interleaving, clock, onSettled = { settled += it }, onViolation = { violations += it })
            .also { loops += it }

    /** The next settled report, which must come. */
    private fun nextSettled(): Settled = checkNotNull(settled.poll(5, TimeUnit.SECONDS)) { "the loop never settled" }

    @AfterTest
    fun close() = loops.forEach { it.close() }

    @Test
    fun a_task_runs_on_its_own_lane_s_thread() = runBlocking {
        val loop = loop()
        val composition = loop.lane("composition", checked = false)
        val reduction = loop.lane("reduction", checked = false)
        // A coroutine debug build suffixes the thread's name with the coroutine's while it runs.
        assertTrue(withContext(composition) { Thread.currentThread().name }.startsWith("loop-composition"))
        assertTrue(withContext(reduction) { Thread.currentThread().name }.startsWith("loop-reduction"))
    }

    @Test
    fun a_mocked_delay_fires_only_when_the_clock_reaches_it() = runBlocking {
        val loop = loop()
        val lane = loop.lane("composition", checked = false)
        val fired = CompletableDeferred<Instant>()
        CoroutineScope(lane).launch {
            delay(5.seconds)
            fired.complete(clock.now)
        }
        val quiet = nextSettled()
        assertEquals(Settled(start, start + 5.seconds), quiet, "settled with the timer pending")
        assertTrue(!fired.isCompleted, "the timer fired without the clock moving")

        loop.advance(10.seconds)
        assertEquals(start + 5.seconds, fired.await(), "the timer saw the clock at its own instant")
        assertEquals(start + 10.seconds, clock.now)
        assertEquals(Settled(start + 10.seconds, null), nextSettled())
    }

    @Test
    fun an_advance_settles_between_timers() = runBlocking {
        val loop = loop()
        val lane = loop.lane("composition", checked = false)
        val seen = Collections.synchronizedList(mutableListOf<String>())
        // Both timers from one task, as the rig queues an act: no settle can fall between them.
        withContext(lane) {
            CoroutineScope(lane).launch {
                delay(1.seconds)
                // What the first timer starts finishes before the second timer's instant, however long it chains.
                CoroutineScope(lane).launch { repeat(3) { seen += "first's work $it" } }
            }
            CoroutineScope(lane).launch {
                delay(2.seconds)
                seen += "second"
            }
        }
        assertEquals(start + 1.seconds, nextSettled().nextTimer)
        loop.advance(3.seconds)
        assertEquals(listOf("first's work 0", "first's work 1", "first's work 2", "second"), seen.toList())
        assertEquals(Settled(start + 3.seconds, null), nextSettled(), "one report, at the end of the advance")
        assertNull(settled.poll(100, TimeUnit.MILLISECONDS), "a settle inside the advance is not reported")
    }

    // An act advances the clock from the loop's own operator lane: finding the loop quiet resumes the act on that lane,
    // and the thread that found it must not sleep through its own work.
    @Test
    fun an_advance_made_on_a_lane_of_the_loop_finishes_under_every_interleaving() {
        listOf(Interleaving.Turns, Interleaving.Shuffled(5), Interleaving.Parallel).forEach { interleaving ->
            runBlocking {
                clock.now = start
                val loop = loop(interleaving = interleaving)
                val app = loop.lane("composition", checked = false)
                val operator = loop.lane("operator", checked = false)
                val ticks = Collections.synchronizedList(mutableListOf<Instant>())
                withContext(app) {
                    CoroutineScope(app).launch {
                        repeat(3) {
                            delay(2.seconds)
                            ticks += clock.now
                        }
                    }
                }
                // Not a child of the test's own job: an advance stuck on the loop cannot then hang the test with it.
                val advanced = CompletableDeferred<Unit>()
                CoroutineScope(operator).launch {
                    loop.advance(7.seconds)
                    advanced.complete(Unit)
                }
                withTimeoutOrNull(5.seconds) { advanced.await() }
                    ?: error("the advance never finished under $interleaving: ${loop.report()}")
                assertEquals(listOf(2, 4, 6).map { start + it.seconds }, ticks.toList(), "under $interleaving")
                loop.close()
            }
        }
    }

    @Test
    fun a_timeout_runs_on_mocked_time() = runBlocking {
        val loop = loop()
        val lane = loop.lane("composition", checked = false)
        val answer = CompletableDeferred<String?>()
        CoroutineScope(lane).launch { answer.complete(withTimeoutOrNull(15.seconds) { CompletableDeferred<String>().await() }) }
        assertEquals(start + 15.seconds, nextSettled().nextTimer, "the timeout is the next timer")
        loop.advance(15.seconds)
        assertNull(answer.await())
    }

    @Test
    fun a_cancelled_delay_leaves_no_timer() = runBlocking {
        val loop = loop()
        val lane = loop.lane("composition", checked = false)
        val job: Job = CoroutineScope(lane).launch { delay(1.seconds) }
        assertEquals(start + 1.seconds, nextSettled().nextTimer)
        job.cancel()
        assertEquals(Settled(start, null), nextSettled())
    }

    @Test
    fun outside_work_keeps_the_loop_unsettled() = runBlocking {
        val loop = loop()
        val lane = loop.lane("composition", checked = true)
        loop.outside("the test reaching the app") { withContext(lane) {} }
        nextSettled()
        val release = CompletableDeferred<Unit>()
        val call = launch(start = CoroutineStart.UNDISPATCHED) { loop.outside("a request the backend answers") { release.await() } }
        assertNull(settled.poll(100, TimeUnit.MILLISECONDS), "settled while outside work ran")
        assertTrue("a request the backend answers" in loop.report())
        release.complete(Unit)
        call.join()
        nextSettled()
        assertTrue(violations.isEmpty(), "$violations")
    }

    @Test
    fun a_dispatch_from_a_thread_the_loop_does_not_own_is_a_violation_unless_counted() = runBlocking {
        val loop = loop()
        val app = loop.lane("composition", checked = true)
        val rig = loop.lane("operator", checked = false)
        withContext(rig) {}
        assertTrue(violations.isEmpty(), "the rig's own lane is not checked: $violations")
        loop.outside("the extension's process()") { withContext(app) {} }
        assertTrue(violations.isEmpty(), "counted outside work may reach the app: $violations")
        withContext(app) {}
        assertEquals(1, violations.size, "$violations")
        assertTrue("lane composition" in violations.single())
    }

    @Test
    fun turns_run_one_task_at_a_time_in_the_order_they_were_queued() = runBlocking {
        val loop = loop()
        val a = loop.lane("a", checked = false)
        val b = loop.lane("b", checked = false)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val gate = loop.lane("gate", checked = false)
        withContext(gate) {
            // Queued from one task, so all are queued before any runs.
            repeat(3) { i ->
                CoroutineScope(a).launch { order += "a$i" }
                CoroutineScope(b).launch { order += "b$i" }
            }
        }
        nextSettled()
        assertEquals(listOf("a0", "b0", "a1", "b1", "a2", "b2"), order.toList())
    }

    @Test
    fun a_shuffled_order_replays_from_its_seed() {
        fun orderOf(seed: Long): List<String> = runBlocking {
            settled.clear()
            val loop = loop(interleaving = Interleaving.Shuffled(seed))
            val lanes = listOf("a", "b", "c").map { loop.lane(it, checked = false) to it }
            val order = Collections.synchronizedList(mutableListOf<String>())
            withContext(loop.lane("gate", checked = false)) {
                repeat(4) { i -> lanes.forEach { (lane, name) -> CoroutineScope(lane).launch { order += "$name$i" } } }
            }
            nextSettled()
            loop.close()
            order.toList()
        }
        assertEquals(orderOf(7), orderOf(7))
        assertTrue((1L..20L).map(::orderOf).toSet().size > 1, "every seed gave one order: nothing was shuffled")
    }

    @Test
    fun parallel_lanes_run_at_the_same_time() = runBlocking {
        val loop = loop(interleaving = Interleaving.Parallel)
        val both = CountDownLatch(2)
        val a = CoroutineScope(loop.lane("a", checked = false)).launch { both.countDown(); both.await(5, TimeUnit.SECONDS) }
        val b = CoroutineScope(loop.lane("b", checked = false)).launch { both.countDown(); both.await(5, TimeUnit.SECONDS) }
        a.join()
        b.join()
        assertEquals(0, both.count, "the two lanes never ran at once")
    }

    @Test
    fun real_time_fires_a_timer_after_its_duration_and_leaves_the_clock() = runBlocking {
        val loop = loop(time = LoopTime.REAL)
        val lane = loop.lane("composition", checked = false)
        val fired = CompletableDeferred<Unit>()
        CoroutineScope(lane).launch {
            delay(50.milliseconds)
            fired.complete(Unit)
        }
        withTimeoutOrNull(5.seconds) { fired.await() } ?: error("the real-time timer never fired")
        assertEquals(start, clock.now, "real time moves no mocked clock")
    }

    @Test
    fun the_interleaving_parses_from_its_name() {
        assertEquals(Interleaving.Turns, Interleaving.parse(null))
        assertEquals(Interleaving.Turns, Interleaving.parse("turns"))
        assertEquals(Interleaving.Parallel, Interleaving.parse("parallel"))
        assertEquals(Interleaving.Shuffled(42), Interleaving.parse("shuffled:42"))
    }
}
