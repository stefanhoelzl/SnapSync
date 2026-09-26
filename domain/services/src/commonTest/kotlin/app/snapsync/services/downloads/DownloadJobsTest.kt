package app.snapsync.services.downloads

import app.snapsync.model.AssetId
import app.snapsync.model.StartResult
import app.snapsync.ports.Download
import app.snapsync.services.staging.DOWNLOAD_STAGING_DIR
import app.snapsync.services.staging.StagingService
import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.model.FileTail
import app.snapsync.ports.Files
import app.snapsync.ports.DownloadHandlers
import app.snapsync.model.TransferOutcome

import app.snapsync.model.AssetRef
import app.snapsync.model.PendingDownload
import app.snapsync.model.PlannedResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.yield
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import kotlin.time.Duration.Companion.seconds

/**
 * The download client's orchestration, exercised without an iOS runtime (capability `receiving-photos`):
 * the bounded in-flight window, the transfer-description codec, the URL guard, and — the reason this
 * suite exists — the **cancellation lifecycle**. Cancelling must cancel *tasks*, never the transport;
 * destroying the transport is what aborted the app in production.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloadJobsTest {

    /** An ordinary healthy transfer: `200`, no declared length — what most of this suite assumes. */
    private companion object {
        val OK = TransferOutcome(statusCode = 200, expectedBytes = -1L, receivedBytes = 1_024L)
        const val REGISTRATIONS = 500
    }

    /**
     * A [Download] that behaves like the real background `URLSession` port: it starts transfers, tells the jobs what
     * the platform would — through the calls the composition's handlers make — and cancels what it holds. It never
     * destroys its session; the system may invalidate one ([systemInvalidates]), after which the port runs on a fresh
     * one ([sessions] counts them).
     */
    private class FakeDownload : Download {
        lateinit var jobs: DownloadJobs

        class Started(val url: String, val description: String) {
            var cancelled = false
        }

        val started = mutableListOf<Started>()

        /** How many sessions the port has run on — one, until the system invalidates it. */
        var sessions = 1
            private set

        /** The staged paths the jobs moved finished bodies to. */
        val stagedTemps = mutableListOf<String>()

        override fun listen(handlers: DownloadHandlers) = Unit

        override fun start(url: String, tag: String): StartResult {
            started += Started(url, tag)
            return StartResult.Started
        }

        override suspend fun cancelAll() {
            started.filterNot { it.cancelled }.forEach {
                it.cancelled = true
                jobs.onCompleted(it.description, "cancelled")
            }
        }

        /** Only the finish half of [finish]: what the delegate queue does for one finished transfer's bytes. */
        fun stage(description: String) = jobs.onFinished(description, OK, "temp:/$description")

        /** The session delivered every event it had — `URLSessionDidFinishEventsForBackgroundURLSession`. */
        fun eventsFinished() = jobs.onBackgroundEventsFinished()

        /** The system invalidated the session — the only way one ever dies. */
        fun systemInvalidates() {
            sessions++
            started.forEach { it.cancelled = true }
            jobs.onInvalidated()
        }

        /**
         * Exactly what the real delegate does on finish: hand the facts and the temporary file, then complete — a
         * download's completion callback follows its finish callback whether or not anything went wrong, which is
         * what frees the window slot. The default outcome is a plain `200` with no declared length.
         */
        fun finish(description: String, outcome: TransferOutcome = OK) {
            jobs.onFinished(description, outcome, "temp:/$description")
            jobs.onCompleted(description, null)
        }
    }

    private class Harness(scope: CoroutineScope) {
        val transport = FakeDownload()
        val staged = mutableListOf<Triple<AssetRef, String, String>>()

        /** What a staged resource is delivered to; a test swaps it to model a slow or stalled import. */
        var deliver: suspend (AssetRef, String, String) -> Unit = { ref, key, path -> staged += Triple(ref, key, path) }

        val jobs = DownloadJobs(
            scope = scope,
            staging = StagingService(AcceptingFiles),
            download = transport,
            onStaged = { ref, key, path -> deliver(ref, key, path) },
        ).also { transport.jobs = it }
    }

    private fun pending(assetId: String, key: String, url: String = "https://cdn.example/$assetId/$key") =
        PendingDownload(
            ref = AssetRef("DEVICE-A", AssetId(assetId)),
            resource = PlannedResource(key, url, "primary", "image/heic", "IMG.HEIC"),
        )

    // ---- the imports a wake announces are all awaited, whichever thread registered them -----------------

    /**
     * Registrations race the drain on real threads (law "State reached from OS callbacks is confined"). The
     * transport's delegate queue registers each import while a coroutine takes the list to await it; a plain list
     * shared between them dropped registrations under contention, so a drain could return — and the OS handler be
     * released — before an import it had announced.
     */
    @Test
    fun registrations_racing_a_drain_are_all_awaited() = runTest {
        val finished = MutableStateFlow(0)
        withContext(Dispatchers.Default) {
            val threads = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val h = Harness(threads)
            h.deliver = { _, _, _ ->
                yield()
                finished.update { it + 1 }
            }
            h.jobs.enqueue(listOf(pending("A", "a-primary.heic")))
            val description = h.transport.started.single().description

            val registrations = List(REGISTRATIONS) { launch(Dispatchers.Default) { h.transport.stage(description) } }
            val drains = launch(Dispatchers.Default) { repeat(50) { h.jobs.awaitOutstandingStagings() } }
            registrations.joinAll()
            drains.join()
            h.jobs.awaitOutstandingStagings()

            assertEquals(REGISTRATIONS, finished.value, "every announced import is awaited before the drain returns")
            threads.cancel()
        }
    }

    // ---- transfer integrity: a finished transfer is not a good transfer ----------------------------

    /**
     * The defect this guards: `URLSession` hands an HTTP error to the *finish* callback as a successful
     * transfer of the error body, with no completion error. Staged, that body is the store's truth — the
     * import fails against it forever and the download never re-runs, because the resource is recorded as
     * staged. The photo never arrives, and nothing says so.
     */
    @Test
    fun a_non_2xx_response_is_never_staged() = runTest {
        val h = Harness(this)
        h.jobs.enqueue(listOf(pending("A", "a-primary.heic")))
        advanceUntilIdle()

        h.transport.finish(h.transport.started.single().description, TransferOutcome(502, -1L, 137L))
        advanceUntilIdle()

        assertTrue(h.staged.isEmpty(), "a 502 error body must never reach staging")
    }

    @Test
    fun a_short_read_is_never_staged() = runTest {
        val h = Harness(this)
        h.jobs.enqueue(listOf(pending("A", "a-primary.heic")))
        advanceUntilIdle()

        h.transport.finish(h.transport.started.single().description, TransferOutcome(200, 5_000L, 1_200L))
        advanceUntilIdle()

        assertTrue(h.staged.isEmpty(), "a body shorter than its Content-Length must never reach staging")
    }

    /**
     * The rejection must free the window slot, or one bad transfer stalls every download behind it — a
     * second way to lose photos silently. The completion callback fires after the finish callback whether
     * or not the bytes were accepted, which is what makes this hold.
     */
    @Test
    fun a_rejected_transfer_frees_its_slot_so_the_queue_refills() = runTest {
        val h = Harness(this)
        h.jobs.enqueue((1..MAX_IN_FLIGHT + 1).map { pending("A", "key-$it") })
        advanceUntilIdle()
        assertEquals(MAX_IN_FLIGHT, h.transport.started.size, "the window starts full")

        h.transport.finish(h.transport.started.first().description, TransferOutcome(502, -1L, 90L))
        advanceUntilIdle()

        assertEquals(MAX_IN_FLIGHT + 1, h.transport.started.size, "the queued transfer must take the freed slot")
        assertTrue(h.staged.isEmpty(), "and the rejected body is still not staged")
    }

    /**
     * The other half of the contract, and the one where getting it wrong is worse than the defect: reject
     * only on positive evidence. A server that omits `Content-Length` omits it on every retry, so
     * rejecting an unknown length would loop forever and the photo would never arrive.
     */
    @Test
    fun a_healthy_transfer_is_staged_whatever_the_length_evidence() = runTest {
        val cases = listOf(
            "unknown length" to TransferOutcome(200, -1L, 4_096L),
            "exact length" to TransferOutcome(200, 4_096L, 4_096L),
            "over-long body" to TransferOutcome(200, 4_096L, 5_000L),
            "unknown status" to TransferOutcome(null, 4_096L, 4_096L),
            "204 no content" to TransferOutcome(204, -1L, 0L),
        )
        for ((label, outcome) in cases) {
            val h = Harness(this)
            h.jobs.enqueue(listOf(pending("A", "a-primary.heic")))
            advanceUntilIdle()

            h.transport.finish(h.transport.started.single().description, outcome)
            advanceUntilIdle()

            assertEquals(1, h.staged.size, "$label must be staged — rejecting it would never resolve on retry")
        }
    }

    /** The predicate itself, stated as the contract reads it. */
    @Test
    fun may_be_staged_rejects_only_on_positive_evidence() {
        assertFalse(TransferOutcome(502, -1L, 10L).mayBeStaged(), "non-2xx")
        assertFalse(TransferOutcome(404, -1L, 10L).mayBeStaged(), "non-2xx")
        assertFalse(TransferOutcome(200, 100L, 99L).mayBeStaged(), "known length, short by one")
        assertTrue(TransferOutcome(200, 100L, 100L).mayBeStaged(), "known length, exact")
        assertTrue(TransferOutcome(200, 100L, 101L).mayBeStaged(), "over-long is not a truncation")
        assertTrue(TransferOutcome(200, -1L, 0L).mayBeStaged(), "unknown length is not a short read")
        assertTrue(TransferOutcome(null, -1L, 10L).mayBeStaged(), "unknown status is not a failure")
        assertTrue(TransferOutcome(299, -1L, 10L).mayBeStaged(), "2xx boundary")
    }

    // ---- the regression: cancellation must not destroy the transport -------------------------------

    /**
     * Leave/switch, then download again. Before this change `cancelAll()` invalidated the background
     * session, so the next enqueue created a task on a dead session → uncatchable `NSException` →
     * `SIGABRT`. The fake reproduces that: if the implementation destroyed the transport, `start` raises.
     */
    @Test
    fun cancelling_transfers_leaves_the_transport_usable() = runTest {
        val h = Harness(this)
        h.jobs.enqueue(listOf(pending("A", "a-primary.heic")))
        advanceUntilIdle()
        assertEquals(1, h.transport.started.size)

        h.jobs.cancelAll()
        advanceUntilIdle()

        // The TASK was cancelled — the session survived it.
        assertTrue(h.transport.started.single().cancelled, "the in-flight task should be cancelled")

        // The crash scenario: a later reconcile enqueues again. This must not raise.
        h.jobs.enqueue(listOf(pending("B", "b-primary.heic")))
        advanceUntilIdle()

        assertEquals(2, h.transport.started.size, "a download after a cancel must still start")
        assertEquals(1, h.transport.sessions, "the session is a singleton — cancelling never ends it")
    }

    @Test
    fun cancelling_empties_the_queue_so_nothing_starts_afterwards() = runTest {
        val h = Harness(this)
        h.jobs.enqueue((1..40).map { pending("A", "key-$it") }) // 40 > MAX_IN_FLIGHT: 16 stay queued
        advanceUntilIdle()
        assertEquals(MAX_IN_FLIGHT, h.transport.started.size)

        h.jobs.cancelAll()
        advanceUntilIdle()

        // The queued remainder is dropped: the cancelled tasks' completions must not pump it out.
        assertEquals(MAX_IN_FLIGHT, h.transport.started.size, "cancelAll must drop the pending queue")
        assertTrue(h.transport.started.all { it.cancelled })
    }

    // ---- bounded in-flight window ------------------------------------------------------------------

    @Test
    fun window_is_bounded_and_refills_as_transfers_complete() = runTest {
        val h = Harness(this)
        h.jobs.enqueue((1..30).map { pending("A", "key-$it") })
        advanceUntilIdle()

        assertEquals(MAX_IN_FLIGHT, h.transport.started.size, "at most MAX_IN_FLIGHT transfers run at once")

        // Three complete → three more are drawn from the queue.
        h.transport.started.take(3).forEach { h.transport.finish(it.description) }
        advanceUntilIdle()

        assertEquals(MAX_IN_FLIGHT + 3, h.transport.started.size, "the window refills on completion")
    }

    @Test
    fun re_enqueuing_an_in_flight_resource_does_not_start_it_twice() = runTest {
        val h = Harness(this)
        h.jobs.enqueue(listOf(pending("A", "a-primary.heic")))
        advanceUntilIdle()
        h.jobs.enqueue(listOf(pending("A", "a-primary.heic"))) // idempotent re-enqueue
        advanceUntilIdle()

        assertEquals(1, h.transport.started.size)
    }

    /**
     * A re-enqueued key that is still waiting for a slot keeps its place in line but takes the newer entry:
     * a re-plan may have re-presigned its url, and the older one may have expired by the time a slot frees.
     */
    @Test
    fun re_enqueuing_a_queued_resource_keeps_its_place_and_takes_the_fresher_url() = runTest {
        val h = Harness(this)
        h.jobs.enqueue((1..MAX_IN_FLIGHT).map { pending("A$it", "k-$it") })
        h.jobs.enqueue(listOf(pending("Q1", "q", url = "https://cdn.example/stale"), pending("Q2", "q")))
        advanceUntilIdle()
        h.jobs.enqueue(listOf(pending("Q2", "q"), pending("Q1", "q", url = "https://cdn.example/fresh")))
        advanceUntilIdle()

        h.transport.started.take(2).forEach { h.transport.finish(it.description) }
        advanceUntilIdle()

        val drawn = h.transport.started.drop(MAX_IN_FLIGHT)
        assertEquals(listOf("https://cdn.example/fresh", "https://cdn.example/Q2/q"), drawn.map { it.url })
    }

    /**
     * Every reconcile hands the jobs its WHOLE pending snapshot, so a second reconcile while a backlog is
     * still queued re-enqueues resources that are queued (not yet started) or running. Neither may transfer
     * twice: the second copy of a queued key used to start after the first finished, re-downloading a
     * resource whose asset was already imported (measured on the SE2: 136 transfers for 100 resources).
     */
    @Test
    fun re_enqueuing_a_backlog_transfers_each_resource_once() = runTest {
        val h = Harness(this)
        val backlog = (1..100).map { pending("A$it", "k-$it") }
        h.jobs.enqueue(backlog)
        advanceUntilIdle()
        // A first wake finishes the first window …
        h.transport.started.take(MAX_IN_FLIGHT).forEach { h.transport.finish(it.description) }
        advanceUntilIdle()
        // … then the next foreground's reconcile re-sends everything not yet staged.
        h.jobs.enqueue(backlog.drop(MAX_IN_FLIGHT))
        advanceUntilIdle()

        finishEverything(h, mutableSetOf())

        val perKey = h.transport.started.groupingBy { it.description }.eachCount()
        assertEquals(100, perKey.size)
        assertEquals(emptyMap(), perKey.filterValues { it > 1 }, "no resource is transferred twice")
    }

    /**
     * The field shape (S2 bench bk1, 2026-09-24): a re-enqueued backlog left stale duplicates in the queue
     * and the window, so the NEXT reconcile's new photos were queued behind them in memory only — the
     * enqueue started nothing. A SIGKILL then lost them; the OS finished only the stale duplicates, whose
     * rows were long imported, and the relaunch staged 24 and imported 0.
     */
    @Test
    fun a_new_batch_after_a_re_enqueued_backlog_is_handed_to_the_os() = runTest {
        val h = Harness(this)
        val old = (1..100).map { pending("OLD$it", "o-$it") }
        h.jobs.enqueue(old)
        advanceUntilIdle()
        h.transport.started.take(MAX_IN_FLIGHT).forEach { h.transport.finish(it.description) }
        advanceUntilIdle()
        h.jobs.enqueue(old.drop(MAX_IN_FLIGHT))
        advanceUntilIdle()
        // Every OLD resource has now finished once — the store would call the backlog drained.
        val done = mutableSetOf<FakeDownload.Started>()
        val finished = mutableSetOf<String>()
        while (finished.size < old.size) {
            val next = h.transport.started.first { !it.cancelled && it.description !in finished && it !in done }
            done += next
            finished += next.description
            h.transport.finish(next.description)
            advanceUntilIdle()
        }

        val fresh = (1..100).map { pending("NEW$it", "n-$it") }
        h.jobs.enqueue(fresh)
        advanceUntilIdle()

        val runningNew = h.transport.started.filter { it !in done && it.description.contains("NEW") }
        assertEquals(MAX_IN_FLIGHT, runningNew.size, "the new batch fills the window — nothing stale occupies it")
    }

    /** Finish every started transfer, including any the completions start, until nothing is running. */
    private fun TestScope.finishEverything(h: Harness, done: MutableSet<FakeDownload.Started>) {
        while (true) {
            val next = h.transport.started.firstOrNull { it !in done } ?: return
            done += next
            h.transport.finish(next.description)
            advanceUntilIdle()
        }
    }

    // ---- transfer-description codec ----------------------------------------------------------------

    @Test
    fun description_round_trips_the_asset_ref_and_resource_key() {
        val ref = AssetRef("DEVICE-A", AssetId("ASSET-1"))
        val tag = decodeTag(encodeTag(ref, "a-primary.heic"))

        assertNotNull(tag)
        assertEquals(ref, tag.ref)
        assertEquals("a-primary.heic", tag.resourceKey)
    }

    @Test
    fun a_malformed_description_decodes_to_null_and_stages_nothing() = runTest {
        assertNull(decodeTag("only-one-field"))
        assertNull(decodeTag("two\nfields"))

        val h = Harness(this)
        h.jobs.enqueue(listOf(pending("A", "a-primary.heic")))
        advanceUntilIdle()

        h.transport.finish("garbage-description") // a completion we cannot attribute
        advanceUntilIdle()

        assertTrue(h.staged.isEmpty(), "an unattributable completion must stage nothing")
    }

    /**
     * The destination is derived from the description alone — the property that lets a completion arriving
     * after a background **relaunch**, for a transfer this process never started, still be staged.
     */
    @Test
    fun a_completed_transfer_is_staged_under_its_asset_and_key() = runTest {
        val h = Harness(this)
        h.jobs.enqueue(listOf(pending("A", "a-primary.heic")))
        advanceUntilIdle()

        h.transport.finish(h.transport.started.single().description)
        advanceUntilIdle()

        assertEquals(
            listOf(Triple(AssetRef("DEVICE-A", AssetId("A")), "a-primary.heic", "$DOWNLOAD_STAGING_DIR/DEVICE-A/a-primary.heic")),
            h.staged,
        )
    }

    /**
     * The OS's background-events handler reports on the wake's own work — the STAGINGS the session's events caused
     * — not on the events themselves (capability `receiving-photos`, "The download session's OS handler is released
     * after staging"). Releasing it when the session drained announced work that had only been queued, and iOS
     * suspended the process on the strength of it (SNAPSYNC-6). Nor is it held for the imports any more: those are
     * the process tail's, run after the release under the app's own background time (`changes/own-work-per-wake`).
     */
    @Test
    fun the_os_handler_is_released_only_after_the_stagings_its_events_caused() = runTest {
        val h = Harness(this)
        var released = false
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val importsStarted = mutableListOf<String>()
        h.deliver = { _, key, _ ->
            importsStarted += key
            gate.await() // a slow staging record — the store write the delivery callback makes
        }
        h.jobs.adoptBackgroundEvents(bareCompletion { released = true })

        // Two transfers land, then the session reports every event delivered.
        h.transport.finish(encodeTag(AssetRef("DEVICE-A", AssetId("A")), "a-primary.heic"))
        h.transport.finish(encodeTag(AssetRef("DEVICE-A", AssetId("B")), "b-primary.heic"))
        h.transport.eventsFinished()
        // `runCurrent`, NOT `advanceUntilIdle`: the handler is now bounded, and advancing virtual time
        // freely would jump past that deadline and release it — proving nothing about the imports.
        runCurrent()

        assertEquals(listOf("a-primary.heic", "b-primary.heic"), importsStarted, "both imports started")
        assertFalse(released, "the OS handler must NOT be released while its imports are still running")

        gate.complete(Unit)
        runCurrent()
        assertTrue(released, "released once the imports it announced actually finished")
    }

    /**
     * The other half of the same guarantee: awaiting the stagings is correct, awaiting them **unboundedly** is not —
     * an unanswered handler costs the app the very download wakes this capability runs on (capability
     * `sync-status`). No clock of ours bounds it: the operating system's expiry does, through the handover the
     * wake's owner was given, and it releases at once without cancelling the work.
     */
    @Test
    fun a_stalled_staging_does_not_strand_the_os_handler_past_the_operating_systems_expiry() = runTest {
        // `backgroundScope`, because this test deliberately parks a staging that never finishes: the expiry must
        // release the handler and leave that work running, so it is still alive when the test body ends.
        val h = Harness(backgroundScope)
        var released = false
        val neverStages = kotlinx.coroutines.CompletableDeferred<Unit>()
        var stagingFinished = false
        h.deliver = { _, _, _ -> neverStages.await(); stagingFinished = true }
        val handover = h.jobs.adoptBackgroundEvents(bareCompletion { released = true })

        h.transport.finish(encodeTag(AssetRef("DEVICE-A", AssetId("A")), "a-primary.heic"))
        h.transport.eventsFinished()
        advanceTimeBy(3_600.seconds)
        assertFalse(released, "no clock of ours releases it")

        handover.releaseOnExpiry("test expiry")
        assertTrue(released, "the operating system's expiry releases it at once")
        assertFalse(stagingFinished, "and never cancels or awaits the work")
    }

    @Test
    fun a_relaunch_completion_for_an_unknown_transfer_is_still_staged() = runTest {
        val h = Harness(this)
        h.jobs.adoptBackgroundEvents(bareCompletion { }) // relaunched by the OS: nothing was enqueued in THIS process

        // The OS hands us a transfer the previous process started.
        h.transport.finish(encodeTag(AssetRef("DEVICE-A", AssetId("A")), "a-primary.heic"))
        advanceUntilIdle()

        assertEquals(
            listOf(Triple(AssetRef("DEVICE-A", AssetId("A")), "a-primary.heic", "$DOWNLOAD_STAGING_DIR/DEVICE-A/a-primary.heic")),
            h.staged,
            "a completion from a previous process must still find its staging path",
        )
    }

    @Test
    fun staging_path_sanitizes_slashes_in_the_device_id_and_key() {
        val path = stagingPath("root", AssetRef("DEV/ICE", AssetId("A")), "a/b.heic")
        assertEquals("root/DEV_ICE/a_b.heic", path)
    }

    // ---- URL guard ---------------------------------------------------------------------------------

    @Test
    fun an_unfetchable_url_is_skipped_and_the_rest_still_enqueue() = runTest {
        val h = Harness(this)
        h.jobs.enqueue(
            listOf(
                pending("A", "hostless", url = "https:///no-host/x"),
                pending("B", "wrong-scheme", url = "file:///tmp/x.heic"),
                pending("C", "unparseable", url = "not-a-url"),
                pending("D", "good", url = "https://cdn.example/d"),
            ),
        )
        advanceUntilIdle()

        assertEquals(1, h.transport.started.size, "only the fetchable url starts a transfer")
        assertEquals("https://cdn.example/d", h.transport.started.single().url)
    }

    @Test
    fun url_guard_accepts_http_and_https_with_a_host_and_rejects_the_rest() {
        assertTrue(isFetchableUrl("https://cdn.example/x?sig=1"))
        assertTrue(isFetchableUrl("http://cdn.example:8080/x"))
        assertTrue(isFetchableUrl("https://user@cdn.example/x"))

        assertFalse(isFetchableUrl("https:///x"))
        assertFalse(isFetchableUrl("file:///tmp/x"))
        assertFalse(isFetchableUrl("ftp://cdn.example/x"))
        assertFalse(isFetchableUrl("not-a-url"))
        assertFalse(isFetchableUrl(""))
    }

    // ---- self-heal on a system-invalidated transport ------------------------------------------------

    /**
     * We never destroy the session — but iOS can. When it does, its transfers are gone: the window empties and
     * refills, and the next transfer runs on the fresh session the port builds.
     */
    @Test
    fun a_system_invalidated_session_empties_the_window_and_the_next_transfer_runs() = runTest {
        val h = Harness(this)
        h.jobs.enqueue(listOf(pending("A", "a-primary.heic")))
        advanceUntilIdle()

        h.transport.systemInvalidates()
        advanceUntilIdle()
        h.jobs.enqueue(listOf(pending("B", "b-primary.heic")))
        advanceUntilIdle()

        assertEquals(2, h.transport.sessions, "the port runs on a fresh session after a system invalidation")
        assertEquals(listOf(AssetId("A"), AssetId("B")), h.transport.started.map { decodeTag(it.description)?.ref?.sourceAssetId }, "B starts")
    }

    /**
     * The shared area as the jobs' staging sees it: every take-over of a finished body succeeds, and every other
     * operation finds nothing. It holds no state, so the race test may adopt from many threads at once; where the bytes
     * land is `StagingService`'s own test's subject (`StagingServiceStageTest`). Its [locate] answers a path under
     * `/abs/`, so a relative staged path is told apart from a platform one.
     */
    private object AcceptingFiles : Files {
        override fun read(area: FileArea, path: String): FileResult<ByteArray> = FileResult.NotFound
        override fun readTail(area: FileArea, path: String, maxBytes: Int): FileResult<FileTail> = FileResult.NotFound
        override fun write(area: FileArea, path: String, bytes: ByteArray): FileResult<Unit> = FileResult.Ok(Unit)
        override fun delete(area: FileArea, path: String): FileResult<Unit> = FileResult.NotFound
        override fun exists(area: FileArea, path: String): FileResult<Boolean> = FileResult.Ok(false)
        override fun locate(area: FileArea, path: String): FileResult<String> = FileResult.Ok("/abs/$path")
        override fun move(area: FileArea, from: String, to: String): FileResult<Unit> = FileResult.NotFound
        override fun adopt(osPath: String, area: FileArea, to: String): FileResult<Unit> = FileResult.Ok(Unit)
    }

    @Test
    fun `a finished body is staged under the relative path and the store is told it`() = runTest {
        val h = Harness(backgroundScope)
        h.jobs.enqueue(listOf(pending("A", "a-primary.heic")))
        runCurrent()
        val description = h.transport.started.single().description

        h.transport.finish(description)
        runCurrent()
        assertEquals("$DOWNLOAD_STAGING_DIR/DEVICE-A/a-primary.heic", h.staged.single().third, "no platform path reaches the store")
    }
}

/** An operating-system completion handler with no expiry signal, as a background-session relaunch hands one over. */
private fun bareCompletion(onComplete: () -> Unit): app.snapsync.ports.Completion =
    object : app.snapsync.ports.Completion {
        override fun complete() = onComplete()
        override fun onExpired(action: () -> Unit) = Unit
    }
