package app.snapsync.feature.upload

import app.snapsync.model.ScheduleResult
import app.snapsync.model.WakeId
import app.snapsync.model.WakeTrigger
import app.snapsync.ports.Wake
import app.snapsync.ports.WakeHandlers
import app.snapsync.services.wake.Heartbeat
import app.snapsync.model.CycleResult
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
 * The tail runner's rules (capability `sync-status`, "Each OS wake does its own work, then hands the rest to one
 * opportunistic tail", "Expiry stops work cooperatively at the next boundary", "The discovery walk is atomic under a
 * stop"; `background-upload`, "The tail runner reimplements the OS scheduler", "A wake that joins a running tail
 * keeps its obligations"), over injected units that record what ran.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class) // runCurrent on the test scheduler
class TailRunnerTest {

    /** Counts the heartbeat wakes the runner's re-arm requests, through the real heartbeat service. */
    private class Scheduler {
        var scheduled = 0
        val heartbeat = Heartbeat(
            object : Wake {
                override fun listen(handlers: WakeHandlers) = Unit
                override fun schedule(id: WakeId, trigger: WakeTrigger): ScheduleResult {
                    if (id == WakeId.Heartbeat) scheduled++
                    return ScheduleResult.Scheduled
                }

                override fun cancel(id: WakeId) = Unit
            },
        )
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

        /** Whether staged downloads are left for a later import — what the re-arm asks after a declining tail. */
        var importsLeft = false
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
                if (stop.stopRequested()) break
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
        heartbeat = scheduler.heartbeat,
        importsRemain = { units.importsLeft },
        leftover = { "" },
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

    // ---- staged imports left over (declared in phase 11f) ------------------------------------------------

    @Test
    fun `staged imports left over re-arm a tail whose uploads declined`() = runTest {
        // A membership that only receives: its upload units decline, and a save iOS cut short is waiting.
        val units = Units().apply {
            topUp = { CycleResult.SKIPPED }
            walk = { WalkOutcome.Walked(CycleResult.SKIPPED, addedRows = false) }
            importsLeft = true
        }
        val scheduler = Scheduler()
        runner(units, scheduler).request(TailTrigger.SILENT_PUSH)
        assertEquals(1, scheduler.scheduled, "the imports still waiting are work remaining")
    }

    @Test
    fun `staged imports left over re-arm a relaunch whose uploads drained`() = runTest {
        val units = Units().apply { importsLeft = true }
        val scheduler = Scheduler()
        runner(units, scheduler).request(TailTrigger.DOWNLOAD_SESSION_EVENTS)
        assertEquals(1, scheduler.scheduled, "a relaunch re-arms on remaining work, and imports are work")
    }

    @Test
    fun `staged imports left over never re-arm a trigger that never re-arms`() = runTest {
        val units = Units().apply { importsLeft = true }
        val scheduler = Scheduler()
        val tail = runner(units, scheduler)
        tail.request(TailTrigger.DOWNLOAD_STAGED)
        tail.request(TailTrigger.UPLOAD_COMPLETED)
        assertEquals(0, scheduler.scheduled, "the wake or the foreground they arrived in owns the re-arm")
    }

    @Test
    fun `a declining tail with nothing left to import arms nothing`() = runTest {
        val units = Units().apply {
            topUp = { CycleResult.SKIPPED }
            walk = { WalkOutcome.Walked(CycleResult.SKIPPED, addedRows = false) }
        }
        val scheduler = Scheduler()
        runner(units, scheduler).request(TailTrigger.HEARTBEAT)
        assertEquals(0, scheduler.scheduled)
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
            for (result in CycleResult.all) {
                val units = Units().apply {
                    topUp = { result }
                    walk = { WalkOutcome.Walked(result, addedRows = false) }
                }
                val scheduler = Scheduler()
                runner(units, scheduler).request(trigger)
                val expected = when {
                    result == CycleResult.SKIPPED -> 0
                    trigger in always -> 1
                    trigger in whenWorkRemains && (result == CycleResult.PROCESSING || result is CycleResult.Paused) -> 1
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
            heartbeat = Scheduler().heartbeat,
            importsRemain = { false },
            leftover = { "" },
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
            heartbeat = scheduler.heartbeat,
            importsRemain = { false },
            leftover = { "" },
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

    // ---- the import-only request (a download staged in a running process) -----------------------------------

    @Test
    fun `a staged download imports and neither tops up nor walks nor re-arms`() = runTest {
        val units = Units()
        val scheduler = Scheduler()
        val outcome = runner(units, scheduler).request(TailTrigger.DOWNLOAD_STAGED)
        assertEquals(listOf("import"), units.ran)
        assertEquals(TailOutcome(CycleResult.COMPLETED, cut = false), outcome)
        assertEquals(0, scheduler.scheduled)
    }

    @Test
    fun `a staging and a completion joining together need the import and the top-up but no walk`() = runTest {
        val units = Units().apply { topUpGate = CompletableDeferred() }
        val tail = runner(units)
        val first = async { tail.request(TailTrigger.UPLOAD_COMPLETED) }
        runCurrent()
        val staged = async { tail.request(TailTrigger.DOWNLOAD_STAGED) }
        val completed = async { tail.request(TailTrigger.UPLOAD_COMPLETED) }
        runCurrent()
        units.topUpGate!!.complete(Unit)
        units.topUpGate = null
        listOf(first, staged, completed).forEach { it.await() }
        assertEquals(listOf("topUp", "import", "topUp"), units.ran, "one more pass covering both joiners, and no walk")
        assertEquals(TailScope.IMPORT_AND_TOP_UP, TailScope.IMPORT + TailScope.TOP_UP)
        assertEquals(TailScope.FULL, TailScope.IMPORT + TailScope.FULL)
    }

    @Test
    fun `an import-only last pass keeps the upload outcome a joiner re-arms on`() = runTest {
        val units = Units().apply { topUp = { CycleResult.PROCESSING }; walkGate = CompletableDeferred() }
        val scheduler = Scheduler()
        val tail = runner(units, scheduler)
        val relaunch = async { tail.request(TailTrigger.UPLOAD_SESSION_EVENTS) }
        runCurrent()
        val staged = async { tail.request(TailTrigger.DOWNLOAD_STAGED) }
        runCurrent()
        units.walkGate!!.complete(Unit)
        units.walkGate = null
        assertEquals(CycleResult.PROCESSING, relaunch.await()?.result, "the import pass says nothing about uploads")
        staged.await()
        assertEquals(1, scheduler.scheduled, "so the relaunch still re-arms on the work its top-up left")
    }

    // ---- the operating-system expiry line (capability `privacy-security`) ---------------------------------

    @Test
    fun `an expiry during the walk is logged with the signal and the abandoned walk and what was left`() = runTest {
        val lines = mutableListOf<String>()
        val recorder = object : co.touchlab.kermit.LogWriter() {
            override fun log(severity: co.touchlab.kermit.Severity, message: String, tag: String, throwable: Throwable?) {
                lines += message
            }
        }
        val units = Units().apply { walkGate = CompletableDeferred() }
        val tail = TailRunner(
            importStaged = { units.ran += "import" },
            topUp = { units.ran += "topUp"; CycleResult.COMPLETED },
            walkAndPublish = { stop ->
                units.walkGate!!.await()
                if (stop()) WalkOutcome.Abandoned else WalkOutcome.Walked(CycleResult.COMPLETED, addedRows = true)
            },
            walkPermitted = { true },
            mayCreate = { true },
            foregrounded = { false },
            refreshStatus = {},
            heartbeat = Scheduler().heartbeat,
            importsRemain = { false },
            leftover = { "staged downloads not yet imported: 2" },
            log = co.touchlab.kermit.Logger(co.touchlab.kermit.loggerConfigInit(recorder), "TailRunnerTest"),
        )
        val push = async { tail.request(TailTrigger.SILENT_PUSH) }
        runCurrent()
        tail.stop("background time for onSilentPush is up")
        units.walkGate!!.complete(Unit)
        push.await()

        val signal = lines.single { it.startsWith("OS expiry") }
        assertTrue("background time for onSilentPush is up" in signal, "which signal: $signal")
        assertTrue("③ walk" in signal && "abandoned" in signal, "what was running, and its fate: $signal")
        val end = lines.single { it.startsWith("tail stopped") }
        assertTrue("the walk was abandoned" in end, "a dump tells an abandoned walk from no new photos: $end")
        assertTrue("staged downloads not yet imported: 2" in end, "what was left: $end")
    }

    // ---- an import that never reports holds no one hostage (capability `receiving-photos`) -----------------------

    private fun hungImportRunner(units: Units, scheduler: Scheduler = Scheduler()): Pair<TailRunner, CompletableDeferred<Unit>> {
        val never = CompletableDeferred<Unit>()
        val tail = TailRunner(
            importStaged = { signal ->
                units.ran += "import"
                // First pass: an import that never reports; later passes find it claimed and import nothing.
                if (units.imported++ == 0 && !signal.awaitUnlessInterrupted(never)) units.ran += "gave up waiting"
            },
            topUp = { units.ran += "topUp"; CycleResult.COMPLETED },
            walkAndPublish = { units.ran += "walk"; WalkOutcome.Walked(CycleResult.COMPLETED, addedRows = false) },
            walkPermitted = { true },
            mayCreate = { true },
            foregrounded = { false },
            refreshStatus = {},
            heartbeat = scheduler.heartbeat,
            importsRemain = { false },
            leftover = { "" },
        )
        return tail to never
    }

    @Test
    fun `a request joining a tail stuck on an import unsticks it`() = runTest {
        val units = Units()
        val (tail, _) = hungImportRunner(units)
        val staged = async { tail.request(TailTrigger.DOWNLOAD_STAGED) }
        runCurrent()
        assertFalse(staged.isCompleted, "the tail is waiting on the import")

        withTimeout(5.seconds) { tail.request(TailTrigger.HEARTBEAT) }
        staged.await()
        assertEquals(listOf("import", "gave up waiting", "import", "topUp", "walk"), units.ran)
    }

    @Test
    fun `a stop gives up waiting on an import and starts nothing further`() = runTest {
        val units = Units()
        val (tail, _) = hungImportRunner(units)
        val heartbeat = async { tail.request(TailTrigger.HEARTBEAT) }
        runCurrent()
        tail.stop("test expiry")
        assertEquals(TailOutcome(CycleResult.PROCESSING, cut = true), withTimeout(5.seconds) { heartbeat.await() })
        assertEquals(listOf("import", "gave up waiting"), units.ran)
    }

    @Test
    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    fun `a signal returns at once for a finished import and for a stopped tail`() = runTest {
        var stopped = false
        val signal = TailSignal({ stopped }, kotlin.concurrent.atomics.AtomicReference(CompletableDeferred()))
        assertTrue(signal.awaitUnlessInterrupted(CompletableDeferred(Unit)), "a finished import is simply finished")
        stopped = true
        assertTrue(signal.stopRequested())
        assertFalse(signal.awaitUnlessInterrupted(CompletableDeferred<Unit>()), "a stopped tail waits for nothing")
    }

    @Test
    fun `an unreadable leftover still ends the expiry line`() = runTest {
        val lines = mutableListOf<String>()
        val recorder = object : co.touchlab.kermit.LogWriter() {
            override fun log(severity: co.touchlab.kermit.Severity, message: String, tag: String, throwable: Throwable?) {
                lines += message
            }
        }
        val gate = CompletableDeferred<Unit>()
        val tail = TailRunner(
            importStaged = { gate.await() },
            topUp = { CycleResult.COMPLETED },
            walkAndPublish = { WalkOutcome.Walked(CycleResult.COMPLETED, addedRows = false) },
            walkPermitted = { true },
            mayCreate = { true },
            foregrounded = { false },
            refreshStatus = {},
            heartbeat = Scheduler().heartbeat,
            importsRemain = { false },
            leftover = { error("store unreadable") },
            log = co.touchlab.kermit.Logger(co.touchlab.kermit.loggerConfigInit(recorder), "TailRunnerTest"),
        )
        val heartbeat = async { tail.request(TailTrigger.HEARTBEAT) }
        runCurrent()
        tail.stop("test expiry")
        gate.complete(Unit)
        heartbeat.await()
        val end = lines.single { it.startsWith("tail stopped") }
        assertTrue("① import completed" in end && "② top-up" in end && "unreadable" in end, end)
    }

    // ---- cancellation (carried over from the pump) -----------------------------------------------------------

    @Test
    fun `a cancelled tail does not wedge the next request`() = runTest {
        // A starter whose coroutine dies mid-unit must leave no running state behind: a joiner of a deferred nothing
        // can complete would otherwise block every later trigger — foreground, push, heartbeat alike.
        val units = Units().apply { topUpGate = CompletableDeferred() }
        val scheduler = Scheduler()
        val tail = runner(units, scheduler)
        val first = launch { tail.request(TailTrigger.FOREGROUND) }
        runCurrent()
        first.cancel()
        runCurrent()
        units.topUpGate = null
        withTimeout(10.seconds) { tail.request(TailTrigger.HEARTBEAT) }
        assertEquals(2, units.ran.count { it == "import" }, "the later request ran its own tail")
        assertEquals(1, scheduler.scheduled, "and kept its own re-arm")
    }

    private fun TestScope.launchRequest(tail: TailRunner, trigger: TailTrigger) = launch { tail.request(trigger) }
}
