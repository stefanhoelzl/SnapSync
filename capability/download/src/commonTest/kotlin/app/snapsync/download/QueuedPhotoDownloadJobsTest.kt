package app.snapsync.download

import app.snapsync.downloadstore.AssetRef
import app.snapsync.downloadstore.PendingDownload
import app.snapsync.downloadstore.PlannedResource
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

        /** The system invalidated the session — the only way a transport ever dies. */
        fun systemInvalidates() {
            destroyed = true
            host.onInvalidated()
        }

        /** Exactly what the real delegate does on finish: ask where the bytes go, move them, report. */
        fun finish(description: String) {
            host.destinationFor(description)?.let { host.onStaged(description, it) }
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
