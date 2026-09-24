package app.snapsync.feature.upload

import app.snapsync.ports.BackgroundScheduler
import app.snapsync.ports.CycleResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The tail runner's rules (capability `ios-app-shell`, "Each OS wake does its own work, then hands the rest to one
 * opportunistic tail", "Expiry stops work cooperatively at the next boundary", "The discovery walk is atomic under a
 * stop"; `ios-url-session-upload`, "The tail runner reimplements the OS scheduler", "A wake that joins a running tail
 * keeps its obligations"), over injected units that record what ran.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // runCurrent on the test scheduler
class TailRunnerTest {

    private class Scheduler : BackgroundScheduler {
        var scheduled = 0
        override fun scheduleNext() {
            scheduled++
        }

        override fun cancel() = Unit
    }

    /** The units, recording the order they ran in; each may be parked on a gate or scripted. */
    private class Units {
        val ran = mutableListOf<String>()
        var topUp: () -> CycleResult = { CycleResult.COMPLETED }
        var walk: () -> WalkOutcome = { WalkOutcome.Walked(CycleResult.COMPLETED, addedRows = false) }
        var importGate: CompletableDeferred<Unit>? = null
        var walkGate: CompletableDeferred<Unit>? = null
        var topUpGate: CompletableDeferred<Unit>? = null

        /** Staged photos the import drains one at a time, stopping between two when asked. */
        var staged = 0
        var imported = 0
        var walkAbandoned = false
        var refreshes = 0
        var fullGrant = true
        var mayCreate = true
        var foreground = false
        var active = 0
        var maxActive = 0

        suspend fun enter(name: String, gate: CompletableDeferred<Unit>?) {
            ran += name
            active++
            maxActive = maxOf(maxActive, active)
            gate?.await()
        }

        fun leave() {
            active--
        }
    }

    private fun runner(units: Units, scheduler: Scheduler = Scheduler()) = TailRunner(
        importStaged = { stop ->
            units.enter("import", null)
            // One photo at a time: the commit in flight completes, and the stop is checked before the next starts.
            while (units.imported < units.staged) {
                units.importGate?.await()
                units.imported++
                if (stop()) break
            }
            units.leave()
        },
        topUp = { _ ->
            units.enter("topUp", units.topUpGate)
            units.leave()
            units.topUp()
        },
        walkAndPublish = { stop ->
            units.enter("walk", units.walkGate)
            units.leave()
            // The walk's contract: a stop that arrived while it enumerated abandons it before its decide stage.
            if (stop()) {
                units.walkAbandoned = true
                WalkOutcome.Abandoned
            } else {
                units.walk()
            }
        },
        walkPermitted = { units.fullGrant },
        mayCreate = { units.mayCreate },
        foregrounded = { units.foreground },
        refreshStatus = { units.refreshes++ },
        scheduler = scheduler,
    )

    // ---- the units and their order ----------------------------------------------------------------------

    @Test
    fun `a full tail imports then tops up then walks`() = runTest {
        val units = Units()
        val outcome = runner(units).request(TailTrigger.SILENT_PUSH)
        assertEquals(listOf("import", "topUp", "walk"), units.ran)
        assertEquals(TailOutcome(CycleResult.COMPLETED, cut = false), outcome)
    }

    @Test
    fun `a walk that added rows loops back to the top-up once`() = runTest {
        val units = Units().apply { walk = { WalkOutcome.Walked(CycleResult.COMPLETED, addedRows = true) } }
        runner(units).request(TailTrigger.HEARTBEAT)
        assertEquals(listOf("import", "topUp", "walk", "topUp"), units.ran)
    }

    @Test
    fun `a partial grant skips the walk`() = runTest {
        val units = Units().apply { fullGrant = false }
        runner(units).request(TailTrigger.SILENT_PUSH)
        assertEquals(listOf("import", "topUp"), units.ran, "① and ② only; no library read")
    }

    @Test
    fun `a completion tops up and never walks or imports`() = runTest {
        val units = Units()
        runner(units).request(TailTrigger.UPLOAD_COMPLETED)
        assertEquals(listOf("topUp"), units.ran)
    }

    @Test
    fun `a completion the app may not create for requests nothing`() = runTest {
        val units = Units().apply { mayCreate = false }
        val scheduler = Scheduler()
        assertNull(runner(units, scheduler).request(TailTrigger.UPLOAD_COMPLETED))
        assertTrue(units.ran.isEmpty(), "recorded by the transport, and no top-up requested")
        assertEquals(0, scheduler.scheduled)
    }

    @Test
    fun `admission only gates completions`() = runTest {
        val units = Units().apply { mayCreate = false }
        runner(units).request(TailTrigger.FOREGROUND)
        assertEquals(listOf("import", "topUp", "walk"), units.ran, "every other trigger reaches the units' own gates")
    }

    @Test
    fun `a truncated top-up is not re-run for that reason alone`() = runTest {
        val units = Units().apply { topUp = { CycleResult.PROCESSING } }
        val outcome = runner(units).request(TailTrigger.FOREGROUND)
        assertEquals(1, units.ran.count { it == "topUp" }, "PROCESSING never busy-loops")
        assertEquals(CycleResult.PROCESSING, outcome?.result)
    }

    // ---- joining ----------------------------------------------------------------------------------------

    @Test
    fun `a completion joining during the walk gets exactly one more top-up pass`() = runTest {
        val units = Units().apply { walkGate = CompletableDeferred() }
        val scheduler = Scheduler()
        val tail = runner(units, scheduler)
        val push = async { tail.request(TailTrigger.SILENT_PUSH) }
        runCurrent()
        assertEquals(listOf("import", "topUp", "walk"), units.ran, "the tail is walking")

        val completion = async { tail.request(TailTrigger.UPLOAD_COMPLETED) }
        runCurrent()
        assertFalse(completion.isCompleted, "the joiner awaits the tail, including its own pass")
        units.walkGate!!.complete(Unit)

        assertEquals(push.await(), completion.await(), "both see the one tail's outcome")
        assertEquals(listOf("import", "topUp", "walk", "topUp"), units.ran, "one more pass, top-up only")
        assertEquals(1, units.maxActive, "never two units at once")
        assertEquals(1, scheduler.scheduled, "the push re-arms; the completion does not")
    }

    @Test
    fun `several joiners coalesce into one more pass at the largest scope`() = runTest {
        val units = Units().apply { walkGate = CompletableDeferred() }
        val scheduler = Scheduler()
        val tail = runner(units, scheduler)
        val first = async { tail.request(TailTrigger.FOREGROUND) }
        runCurrent()
        val joiners = listOf(TailTrigger.UPLOAD_COMPLETED, TailTrigger.SILENT_PUSH, TailTrigger.HEARTBEAT)
            .map { async { tail.request(it) } }
        runCurrent()
        // Release the first pass's walk; the second pass is FULL because a push and a heartbeat joined, and walks
        // ungated.
        val gate = units.walkGate!!
        units.walkGate = null
        gate.complete(Unit)
        joiners.forEach { it.await() }
        first.await()
        assertEquals(
            listOf("import", "topUp", "walk", "import", "topUp", "walk"),
            units.ran,
            "exactly one further pass, covering all three, and no second tail",
        )
        assertEquals(3, scheduler.scheduled, "foreground, push and heartbeat each re-arm; the completion does not")
    }

    @Test
    fun `a joiner applies its own re-arm to the tail's outcome`() = runTest {
        for ((result, expected) in listOf(CycleResult.PROCESSING to 1, CycleResult.COMPLETED to 0)) {
            val units = Units().apply { topUpGate = CompletableDeferred(); topUp = { result } }
            val scheduler = Scheduler()
            val tail = runner(units, scheduler)
            val completion = async { tail.request(TailTrigger.UPLOAD_COMPLETED) }
            runCurrent()
            val relaunch = async { tail.request(TailTrigger.UPLOAD_SESSION_EVENTS) }
            runCurrent()
            units.topUpGate!!.complete(Unit)
            units.topUpGate = null
            completion.await()
            relaunch.await()
            assertEquals(expected, scheduler.scheduled, "a relaunch that joined re-arms only on remaining work ($result)")
        }
    }

    @Test
    fun `a joiner against a declining membership arms nothing`() = runTest {
        val units = Units().apply { topUpGate = CompletableDeferred(); topUp = { CycleResult.SKIPPED } }
        units.walk = { WalkOutcome.Walked(CycleResult.SKIPPED, addedRows = false) }
        val scheduler = Scheduler()
        val tail = runner(units, scheduler)
        val arm = async { tail.request(TailTrigger.ARM) }
        runCurrent()
        val heartbeat = async { tail.request(TailTrigger.HEARTBEAT) }
        runCurrent()
        units.topUpGate!!.complete(Unit)
        units.topUpGate = null
        assertEquals(CycleResult.SKIPPED, heartbeat.await()?.result)
        arm.await()
        assertEquals(0, scheduler.scheduled, "SKIPPED arms nothing, whatever the triggers' own policies")
    }

    // ---- the stop ---------------------------------------------------------------------------------------

    @Test
    fun `a stop during the walk abandons it and starts nothing further`() = runTest {
        val units = Units().apply {
            walkGate = CompletableDeferred()
            walk = { WalkOutcome.Walked(CycleResult.COMPLETED, addedRows = true) }
        }
        val scheduler = Scheduler()
        val tail = runner(units, scheduler)
        val relaunch = async { tail.request(TailTrigger.UPLOAD_SESSION_EVENTS) }
        runCurrent()
        tail.stop("test expiry")
        units.walkGate!!.complete(Unit)
        val outcome = relaunch.await()
        assertTrue(units.walkAbandoned, "the walk saw the stop and decided nothing")
        assertEquals(listOf("import", "topUp", "walk"), units.ran, "no top-up after an abandoned walk")
        assertEquals(TailOutcome(CycleResult.PROCESSING, cut = true), outcome, "a stopped tail counts as work left")
        assertEquals(1, scheduler.scheduled, "so a relaunch cut short still re-arms")
    }

    @Test
    fun `a stop during the import lets the photo in flight finish and runs nothing else`() = runTest {
        val units = Units().apply { staged = 3; importGate = CompletableDeferred() }
        val tail = runner(units)
        val push = async { tail.request(TailTrigger.SILENT_PUSH) }
        runCurrent()
        tail.stop("test expiry")
        units.importGate!!.complete(Unit)
        val outcome = push.await()
        assertEquals(1, units.imported, "the photo in flight was committed, and no further one started")
        assertEquals(listOf("import"), units.ran, "no top-up and no walk start after the stop")
        assertEquals(TailOutcome(CycleResult.PROCESSING, cut = true), outcome)
    }

    @Test
    fun `a stop consumes the pending pass and its joiners get the cut outcome`() = runTest {
        val units = Units().apply { topUpGate = CompletableDeferred() }
        val tail = runner(units)
        val first = async { tail.request(TailTrigger.HEARTBEAT) }
        runCurrent()
        val joiner = async { tail.request(TailTrigger.SILENT_PUSH) }
        runCurrent()
        tail.stop("test expiry")
        units.topUpGate!!.complete(Unit)
        units.topUpGate = null
        assertEquals(TailOutcome(CycleResult.PROCESSING, cut = true), joiner.await())
        first.await()
        assertEquals(listOf("import", "topUp"), units.ran, "neither the walk nor the joiner's pass ran")

        // The next request finds no tail running and no stop left over.
        units.ran.clear()
        assertEquals(TailOutcome(CycleResult.COMPLETED, cut = false), tail.request(TailTrigger.FOREGROUND))
        assertEquals(listOf("import", "topUp", "walk"), units.ran)
    }

    @Test
    fun `a stop keeps a decline that was reached before it`() = runTest {
        val units = Units().apply { topUp = { CycleResult.SKIPPED }; walkGate = CompletableDeferred() }
        val scheduler = Scheduler()
        val tail = runner(units, scheduler)
        val heartbeat = async { tail.request(TailTrigger.HEARTBEAT) }
        runCurrent()
        tail.stop("test expiry")
        units.walkGate!!.complete(Unit)
        assertEquals(TailOutcome(CycleResult.SKIPPED, cut = true), heartbeat.await())
        assertEquals(0, scheduler.scheduled, "SKIPPED never re-arms, stopped or not")
    }

    @Test
    fun `a stop with no tail running changes nothing`() = runTest {
        val units = Units()
        val tail = runner(units)
        tail.stop("nothing to stop")
        assertEquals(TailOutcome(CycleResult.COMPLETED, cut = false), tail.request(TailTrigger.HEARTBEAT))
        assertEquals(listOf("import", "topUp", "walk"), units.ran)
    }

    // ---- failure ------------------------------------------------------------------------------------------

    @Test
    fun `a failed tail fails its waiters and consumes the pending pass`() = runTest {
        val units = Units().apply { topUpGate = CompletableDeferred() }
        var throwOnce = true
        units.topUp = {
            if (throwOnce) {
                throwOnce = false
                error("the top-up blew up")
            }
            CycleResult.COMPLETED
        }
        val scheduler = Scheduler()
        val tail = runner(units, scheduler)
        val first = async { runCatching { tail.request(TailTrigger.FOREGROUND) } }
        runCurrent()
        val joiner = async { runCatching { tail.request(TailTrigger.HEARTBEAT) } }
        runCurrent()
        units.topUpGate!!.complete(Unit)
        units.topUpGate = null
        assertTrue(first.await().isFailure, "the starter sees the failure")
        assertTrue(joiner.await().isFailure, "the joiner is failed, not left parked")
        assertEquals(0, scheduler.scheduled, "a failed tail re-arms nothing")

        units.ran.clear()
        tail.request(TailTrigger.UPLOAD_COMPLETED)
        assertEquals(listOf("topUp"), units.ran, "no phantom full pass lands on the next request")
    }

    // ---- re-arm ---------------------------------------------------------------------------------------------

    @Test
    fun `each trigger re-arms per its row of the table`() = runTest {
        val always = setOf(
            TailTrigger.ARM, TailTrigger.FOREGROUND, TailTrigger.SILENT_PUSH, TailTrigger.SELECTION_CHANGE,
            TailTrigger.HEARTBEAT,
        )
        val whenWorkRemains = setOf(TailTrigger.UPLOAD_SESSION_EVENTS, TailTrigger.DOWNLOAD_SESSION_EVENTS)
        for (trigger in TailTrigger.entries) {
            for (result in CycleResult.entries) {
                val units = Units().apply {
                    topUp = { result }
                    walk = { WalkOutcome.Walked(result, addedRows = false) }
                }
                val scheduler = Scheduler()
                runner(units, scheduler).request(trigger)
                val expected = when {
                    result == CycleResult.SKIPPED -> 0
                    trigger in always -> 1
                    trigger in whenWorkRemains && result == CycleResult.PROCESSING -> 1
                    else -> 0
                }
                assertEquals(expected, scheduler.scheduled, "$trigger after $result")
            }
        }
    }

    // ---- self-join and status ---------------------------------------------------------------------------------

    @Test
    fun `a unit that requests its own tail is refused rather than deadlocked`() = runTest {
        lateinit var tail: TailRunner
        tail = TailRunner(
            importStaged = { tail.request(TailTrigger.UPLOAD_COMPLETED) },
            topUp = { CycleResult.COMPLETED },
            walkAndPublish = { WalkOutcome.Walked(CycleResult.COMPLETED, addedRows = false) },
            walkPermitted = { true },
            mayCreate = { true },
            foregrounded = { false },
            refreshStatus = {},
            scheduler = Scheduler(),
        )
        val failure = withTimeout(5.seconds) { assertFailsWith<IllegalStateException> { tail.request(TailTrigger.HEARTBEAT) } }
        assertTrue("wait on itself" in failure.message.orEmpty())
        // And the runner is usable afterwards: the failed tail cleared itself.
        withTimeout(5.seconds) { tail.request(TailTrigger.UPLOAD_COMPLETED) }
    }

    @Test
    fun `status refreshes after each unit only while foregrounded`() = runTest {
        val units = Units().apply { foreground = true }
        runner(units).request(TailTrigger.FOREGROUND)
        assertEquals(3, units.refreshes, "after ①, ② and ③")

        val background = Units()
        runner(background).request(TailTrigger.HEARTBEAT)
        assertEquals(0, background.refreshes, "a background tail refreshes nothing")
    }

    @Test
    fun `a failing refresh does not disturb the tail`() = runTest {
        val units = Units().apply { foreground = true }
        val scheduler = Scheduler()
        val tail = TailRunner(
            importStaged = { units.ran += "import" },
            topUp = { units.ran += "topUp"; CycleResult.PROCESSING },
            walkAndPublish = { units.ran += "walk"; WalkOutcome.Walked(CycleResult.COMPLETED, addedRows = false) },
            walkPermitted = { true },
            mayCreate = { true },
            foregrounded = { true },
            refreshStatus = { error("counts unreadable") },
            scheduler = scheduler,
        )
        val outcome = tail.request(TailTrigger.FOREGROUND)
        assertEquals(listOf("import", "topUp", "walk"), units.ran)
        assertEquals(CycleResult.PROCESSING, outcome?.result)
        assertEquals(1, scheduler.scheduled)
    }

    @Test
    fun `concurrent starters never run two tails`() = runTest {
        val units = Units().apply { topUpGate = CompletableDeferred() }
        val tail = runner(units)
        val requests = TailTrigger.entries.map { launchRequest(tail, it) }
        runCurrent()
        units.topUpGate!!.complete(Unit)
        units.topUpGate = null
        requests.forEach { it.join() }
        assertEquals(1, units.maxActive, "one unit at a time, across every trigger at once")
        assertEquals(2, units.ran.count { it == "import" }, "the first pass and exactly one more")
    }

    private fun TestScope.launchRequest(tail: TailRunner, trigger: TailTrigger) = launch { tail.request(trigger) }
}
