package app.snapsync.feature.download

import app.snapsync.ports.DownloadTask
import app.snapsync.ports.DownloadTransport
import app.snapsync.ports.DownloadTransportHost
import app.snapsync.ports.TransferOutcome

import app.snapsync.ports.AssetRef
import app.snapsync.ports.PendingDownload
import app.snapsync.ports.PlannedResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

/**
 * The download client's orchestration, exercised without an iOS runtime (capability `photo-download`):
 * the bounded in-flight window, the transfer-description codec, the URL guard, and — the reason this
 * suite exists — the **cancellation lifecycle**. Cancelling must cancel *tasks*, never the transport;
 * destroying the transport is what aborted the app in production.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QueuedPhotoDownloadJobsTest {

    /** An ordinary healthy transfer: `200`, no declared length — what most of this suite assumes. */
    private companion object {
        val OK = TransferOutcome(statusCode = 200, expectedBytes = -1L, receivedBytes = 1_024L)
    }

    /**
     * A [DownloadTransport] that behaves like the real background `URLSession` **including its fatal
     * edge**: once the session is gone, creating a task raises — on iOS that is an uncatchable
     * Objective-C `NSException` which aborts the process. A test that trips this `check` is reproducing
     * the production crash.
     */
    private class FakeDownloadTransport(private val host: DownloadTransportHost) : DownloadTransport {

        class Started(val url: String, val description: String) {
            var cancelled = false
        }

        val started = mutableListOf<Started>()
        var destroyed = false
            private set

        override fun start(url: String, description: String): DownloadTask? {
            check(!destroyed) { "task created on a destroyed transport — this aborts the process on iOS" }
            val s = Started(url, description)
            started += s
            return object : DownloadTask {
                override fun cancel() {
                    s.cancelled = true
                    host.onCompleted(description, "cancelled")
                }
            }
        }

        /** The session delivered every event it had — `URLSessionDidFinishEventsForBackgroundURLSession`. */
        fun eventsFinished() = host.onBackgroundEventsFinished()

        /** The system invalidated the session — the only way a transport ever dies. */
        fun systemInvalidates() {
            destroyed = true
            host.onInvalidated()
        }

        /**
         * Exactly what the real delegate does on finish: ask whether the bytes may be staged, and only
         * then ask where they go, move them, and report. The completion fires either way — a download's
         * completion callback follows its finish callback whether or not anything went wrong, which is
         * what frees the window slot.
         *
         * The default outcome is a plain `200` with no declared length: the shape of an ordinary healthy
         * transfer, so existing tests describe what they always did.
         */
        fun finish(description: String, outcome: TransferOutcome = OK) {
            if (host.accepts(description, outcome)) {
                host.destinationFor(description)?.let { host.onStaged(description, it) }
            }
            host.onCompleted(description, null)
        }
    }

    private class Harness(scope: CoroutineScope) {
        val transports = mutableListOf<FakeDownloadTransport>()
        val staged = mutableListOf<Triple<AssetRef, String, String>>()

        val jobs = QueuedPhotoDownloadJobs(
            scope = scope,
            stagingRoot = "/root",
            newTransport = { events -> FakeDownloadTransport(events).also { transports += it } },
        ).apply {
            onStaged = { ref, key, path -> staged += Triple(ref, key, path) }
        }

        /** The transport currently in use (the last one built). */
        val transport: FakeDownloadTransport get() = transports.last()
    }

    private fun pending(assetId: String, key: String, url: String = "https://cdn.example/$assetId/$key") =
        PendingDownload(
            ref = AssetRef("DEVICE-A", assetId),
            resource = PlannedResource(key, url, "primary", "image/heic", "IMG.HEIC"),
        )

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

        // The TASK was cancelled...
        assertTrue(h.transport.started.single().cancelled, "the in-flight task should be cancelled")
        // ...and the TRANSPORT survived it.
        assertFalse(h.transport.destroyed, "cancelAll must never destroy the transport")

        // The crash scenario: a later reconcile enqueues again. This must not raise.
        h.jobs.enqueue(listOf(pending("B", "b-primary.heic")))
        advanceUntilIdle()

        assertEquals(2, h.transport.started.size, "a download after a cancel must still start")
        assertEquals(1, h.transports.size, "the transport is a singleton — it was never rebuilt")
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

    // ---- transfer-description codec ----------------------------------------------------------------

    @Test
    fun description_round_trips_the_asset_ref_and_resource_key() {
        val ref = AssetRef("DEVICE-A", "ASSET-1")
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
            listOf(Triple(AssetRef("DEVICE-A", "A"), "a-primary.heic", "/root/DEVICE-A/a-primary.heic")),
            h.staged,
        )
    }

    /**
     * The change's central behaviour (capability `photo-download`): the OS's background-events handler
     * reports on the IMPORTS the session's events caused, not on the events themselves. Releasing it
     * when the session drained — which is what this did — announced work that had only been queued, and
     * iOS suspended the process on the strength of it. Field evidence (SNAPSYNC-6, 2026-08-01): five
     * resources staged at 09:02:07, four imported, the fifth still un-imported when the process died.
     */
    @Test
    fun the_os_handler_is_released_only_after_the_imports_its_events_caused() = runTest {
        val h = Harness(this)
        var released = false
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val importsStarted = mutableListOf<String>()
        h.jobs.onStaged = { _, key, _ ->
            importsStarted += key
            gate.await() // a slow import, exactly like a real PhotoKit commit
        }
        h.jobs.adoptBackgroundEvents { released = true }

        // Two transfers land, then the session reports every event delivered.
        h.transport.finish(encodeTag(AssetRef("DEVICE-A", "A"), "a-primary.heic"))
        h.transport.finish(encodeTag(AssetRef("DEVICE-A", "B"), "b-primary.heic"))
        h.transport.eventsFinished()
        advanceUntilIdle()

        assertEquals(listOf("a-primary.heic", "b-primary.heic"), importsStarted, "both imports started")
        assertFalse(released, "the OS handler must NOT be released while its imports are still running")

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(released, "released once the imports it announced actually finished")
    }

    @Test
    fun a_relaunch_completion_for_an_unknown_transfer_is_still_staged() = runTest {
        val h = Harness(this)
        h.jobs.adoptBackgroundEvents { } // relaunched by the OS: nothing was enqueued in THIS process

        // The OS hands us a transfer the previous process started.
        h.transport.finish(encodeTag(AssetRef("DEVICE-A", "A"), "a-primary.heic"))
        advanceUntilIdle()

        assertEquals(
            listOf(Triple(AssetRef("DEVICE-A", "A"), "a-primary.heic", "/root/DEVICE-A/a-primary.heic")),
            h.staged,
            "a completion from a previous process must still find its staging path",
        )
    }

    @Test
    fun staging_path_sanitizes_slashes_in_the_device_id_and_key() {
        val path = stagingPath("/root", AssetRef("DEV/ICE", "A"), "a/b.heic")
        assertEquals("/root/DEV_ICE/a_b.heic", path)
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
     * We never destroy the transport — but iOS can. When it does, the next transfer must build a fresh
     * session rather than reuse the dead one (which would abort the process).
     */
    @Test
    fun a_system_invalidated_transport_is_rebuilt_on_the_next_transfer() = runTest {
        val h = Harness(this)
        h.jobs.enqueue(listOf(pending("A", "a-primary.heic")))
        advanceUntilIdle()
        assertEquals(1, h.transports.size)

        h.transport.systemInvalidates()
        advanceUntilIdle()

        // The next enqueue must NOT touch the dead transport (its `start` would raise).
        h.jobs.enqueue(listOf(pending("B", "b-primary.heic")))
        advanceUntilIdle()

        assertEquals(2, h.transports.size, "a fresh transport is built after a system invalidation")
        assertTrue(h.transports[0].destroyed)
        assertFalse(h.transports[1].destroyed)
        assertEquals(1, h.transports[1].started.size, "the transfer runs on the fresh transport")
    }
}
