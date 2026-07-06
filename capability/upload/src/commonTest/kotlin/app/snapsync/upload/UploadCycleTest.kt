package app.snapsync.upload

import app.snapsync.engine.LedgerState
import app.snapsync.engine.LedgerWriter
import app.snapsync.engine.Resource
import app.snapsync.engine.SyncEngine
import app.snapsync.engine.UploadError
import app.snapsync.engine.UploadRequest
import app.snapsync.engine.UploadRequestProvider
import app.snapsync.gallery.RESOURCE_META_CREATION_DATE
import app.snapsync.gallery.normalizeAssetId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UploadCycleTest {

    /** A no-network provider returning a throwaway destination — the cycle never inspects the URL. */
    private class StubUploadRequestProvider : UploadRequestProvider {
        override suspend fun provide(resource: Resource): UploadRequest =
            UploadRequest(url = "https://stub.invalid/${resource.filename}", headers = emptyMap(), resource = resource)
    }

    /** Records what the cycle asked the platform to do; serves canned discovered/returned jobs. */
    private class FakePlatform(
        private val discovered: List<Resource> = emptyList(),
        private val retryJobs: List<PlatformUploadJob> = emptyList(),
        private val ackJobs: List<PlatformUploadJob> = emptyList(),
        private val nextToken: ByteArray = byteArrayOf(9),
        private val limitAfter: Int = Int.MAX_VALUE,
        private val failCreate: Boolean = false,
        private val removedAssetIds: List<String> = emptyList(),
        private val fullEnumeration: Boolean = false,
    ) : UploadJobPlatform {
        val created = mutableListOf<Resource>()
        val retried = mutableListOf<PlatformUploadJob>()
        val acknowledged = mutableListOf<PlatformUploadJob>()
        var discoverTokenArg: ByteArray? = null
        private var creates = 0

        override suspend fun fetchRetryJobs() = retryJobs
        override suspend fun fetchAckJobs() = ackJobs
        override suspend fun retryJob(job: PlatformUploadJob, request: UploadRequest) { retried += job }
        override suspend fun acknowledge(job: PlatformUploadJob) { acknowledged += job }
        override suspend fun discoverResources(sinceToken: ByteArray?): Discovery {
            discoverTokenArg = sinceToken
            return Discovery(discovered, nextToken, removedAssetIds, fullEnumeration)
        }
        override suspend fun createJob(request: UploadRequest, resource: Resource): CreateResult {
            if (failCreate) return CreateResult.FAILED
            if (creates >= limitAfter) return CreateResult.LIMIT_EXCEEDED
            creates++
            created += resource
            return CreateResult.CREATED
        }
    }

    private class FakeStore(private val token: ByteArray? = null) : DiscoveryStore {
        var saved: ByteArray? = null
        var cleared = false
        override fun loadToken(): ByteArray? = token
        override fun saveToken(token: ByteArray) { saved = token }
        override fun clearToken() { cleared = true }
    }

    private fun resource(name: String, assetId: String = name) =
        Resource(filename = name, assetId = assetId, contentType = "image/jpeg", metadata = emptyMap(), data = Unit)

    private fun platformJob(key: String, state: PlatformJobState, error: UploadError? = null) =
        PlatformUploadJob(key = key, contentType = "image/jpeg", state = state, error = error, data = Unit, handle = Unit)

    private fun cycleOver(
        backend: InMemoryLedgerBackend,
        platform: FakePlatform,
        store: DiscoveryStore = FakeStore(),
    ): UploadCycle {
        val ledger = LedgerWriter(backend)
        return UploadCycle(SyncEngine(StubUploadRequestProvider(), ledger), ledger, platform, store)
    }

    @Test
    fun discovery_creates_a_job_per_new_resource_and_records_requested_after_create() = runTest {
        val backend = InMemoryLedgerBackend()
        val platform = FakePlatform(discovered = listOf(resource("a"), resource("b")))
        val store = FakeStore()

        val result = cycleOver(backend, platform, store).run()

        assertEquals(CycleResult.COMPLETED, result)
        assertEquals(listOf("a", "b"), platform.created.map { it.filename })
        assertEquals(LedgerState.REQUESTED, backend.get("a")?.state)
        assertEquals(LedgerState.REQUESTED, backend.get("b")?.state)
        assertContentEquals(byteArrayOf(9), store.saved) // cursor advanced on a fully-drained cycle
    }

    @Test
    fun discovery_skips_in_flight_and_completed_resources() = runTest {
        val backend = InMemoryLedgerBackend()
        LedgerWriter(backend).recordRequested("a", assetId = "a", attempt = 0) // in flight
        LedgerWriter(backend).recordCompleted("b", assetId = "b", attempt = 0) // done
        val platform = FakePlatform(discovered = listOf(resource("a"), resource("b")))

        cycleOver(backend, platform).run()

        assertTrue(platform.created.isEmpty(), "in-flight (REQUESTED) and COMPLETED keys must be skipped")
    }

    @Test
    fun discovery_does_not_re_upload_a_completed_key() = runTest {
        // Uploaded resources are immutable: a COMPLETED key is never re-uploaded, even when the same
        // asset is re-discovered (e.g. after a metadata-only change).
        val backend = InMemoryLedgerBackend()
        LedgerWriter(backend).recordCompleted("a", assetId = "a", attempt = 0)
        val platform = FakePlatform(discovered = listOf(resource("a")))

        cycleOver(backend, platform).run()

        assertTrue(platform.created.isEmpty(), "a COMPLETED key must never be re-uploaded")
    }

    @Test
    fun suppressed_downloaded_assets_create_no_job_and_are_pruned_from_retain() = runTest {
        // FOREIGN is an asset this device downloaded + imported (in the suppression set). A stale
        // COMPLETED row stands in for a pre-suppression echo; it must be pruned and never re-uploaded.
        val backend = InMemoryLedgerBackend()
        LedgerWriter(backend).recordCompleted("FOREIGN-primary.heic", assetId = "FOREIGN", attempt = 0)
        val platform = FakePlatform(
            discovered = listOf(resource("FOREIGN-primary.heic", "FOREIGN"), resource("MINE-primary.heic", "MINE")),
            fullEnumeration = true,
        )
        val ledger = LedgerWriter(backend)
        val cycle = UploadCycle(
            SyncEngine(StubUploadRequestProvider(), ledger),
            ledger,
            platform,
            FakeStore(),
            suppressedAssetIds = { setOf("FOREIGN") },
        )

        cycle.run()

        assertEquals(listOf("MINE-primary.heic"), platform.created.map { it.filename }) // FOREIGN suppressed
        assertNull(backend.get("FOREIGN-primary.heic"), "suppressed asset's stale row pruned by retainAssets")
    }

    @Test
    fun suppression_matches_on_the_normalized_assetid() = runTest {
        // Both sides normalize the raw PHAsset localIdentifier '/'→'_': discovery via the gallery
        // enumerator's `normalizeAssetId`, the download importer before storing `createdLocalId`. A raw
        // id "ABC/L0/001" must therefore suppress as "ABC_L0_001" — the §7.6 load-bearing contract.
        val backend = InMemoryLedgerBackend()
        val normalized = normalizeAssetId("ABC/L0/001") // "ABC_L0_001" — the discovery-side transform
        val platform = FakePlatform(discovered = listOf(resource("$normalized-primary.heic", normalized)))
        val ledger = LedgerWriter(backend)
        val cycle = UploadCycle(
            SyncEngine(StubUploadRequestProvider(), ledger),
            ledger,
            platform,
            FakeStore(),
            // The importer stored the '/'→'_' createdLocalId — the same normalized string.
            suppressedAssetIds = { setOf("ABC_L0_001") },
        )

        cycle.run()

        assertTrue(platform.created.isEmpty(), "a downloaded asset must be suppressed on its normalized id")
    }

    @Test
    fun discovery_passes_the_loaded_cursor_to_the_platform() = runTest {
        val backend = InMemoryLedgerBackend()
        val platform = FakePlatform()
        val store = FakeStore(token = byteArrayOf(7))

        cycleOver(backend, platform, store).run()

        assertContentEquals(byteArrayOf(7), platform.discoverTokenArg)
    }

    @Test
    fun succeeded_job_records_completed_and_is_acknowledged() = runTest {
        val backend = InMemoryLedgerBackend()
        LedgerWriter(backend).recordRequested("a", assetId = "a", attempt = 0)
        val job = platformJob("a", PlatformJobState.SUCCEEDED)
        val platform = FakePlatform(ackJobs = listOf(job))

        cycleOver(backend, platform).run()

        assertEquals(LedgerState.COMPLETED, backend.get("a")?.state)
        assertEquals(listOf(job), platform.acknowledged)
        assertTrue(platform.created.isEmpty())
    }

    @Test
    fun succeeded_job_with_a_pruned_ledger_row_completes_with_the_key_derived_assetid() = runTest {
        // The ledger row was pruned (a mid-upload deletion, or a full-enumeration retain) before the OS
        // handed back the succeeded job — so there is no entry. reconstruct must derive the assetId from
        // the key, not write a phantom assetId="" COMPLETED row (the §7.2 bug).
        val backend = InMemoryLedgerBackend()
        val job = platformJob("L-primary.jpg", PlatformJobState.SUCCEEDED)
        val platform = FakePlatform(ackJobs = listOf(job))

        cycleOver(backend, platform).run()

        val entry = backend.get("L-primary.jpg")
        assertEquals(LedgerState.COMPLETED, entry?.state)
        assertEquals("L", entry?.assetId, "assetId is derived from the key, never a phantom empty string")
        assertEquals(listOf(job), platform.acknowledged)
    }

    @Test
    fun a_blank_key_succeeded_job_is_acknowledged_but_records_no_row() = runTest {
        // An unrecoverable key (e.g. a malformed destination URL) must never produce a phantom row, but
        // the job is still acknowledged (an un-acknowledged presented job errors the system 50008).
        val backend = InMemoryLedgerBackend()
        val job = platformJob("", PlatformJobState.SUCCEEDED)
        val platform = FakePlatform(ackJobs = listOf(job))

        cycleOver(backend, platform).run()

        assertNull(backend.get(""), "no phantom row for an unrecoverable key")
        assertEquals(listOf(job), platform.acknowledged)
    }

    @Test
    fun first_failure_retries_with_a_fresh_url_and_records_requested() = runTest {
        val backend = InMemoryLedgerBackend()
        LedgerWriter(backend).recordRequested("a", assetId = "a", attempt = 0)
        val job = platformJob("a", PlatformJobState.FAILED, UploadError.Network)
        val platform = FakePlatform(retryJobs = listOf(job))

        cycleOver(backend, platform).run()

        assertEquals(listOf(job), platform.retried)
        val entry = backend.get("a")
        assertEquals(LedgerState.REQUESTED, entry?.state) // UploadStarted recorded the retry
        assertEquals(1, entry?.attempt)
        assertTrue(platform.created.isEmpty(), "a free retry re-points, it does not create")
    }

    @Test
    fun retry_spent_failure_re_creates_from_the_job_resource_and_is_acknowledged() = runTest {
        val backend = InMemoryLedgerBackend()
        LedgerWriter(backend).recordRequested("a", assetId = "a", attempt = 0)
        val job = platformJob("a", PlatformJobState.FAILED, UploadError.Network)
        val platform = FakePlatform(ackJobs = listOf(job))

        val result = cycleOver(backend, platform).run()

        assertEquals(CycleResult.COMPLETED, result)
        assertEquals(listOf("a"), platform.created.map { it.filename }) // re-created
        assertEquals(listOf(job), platform.acknowledged) // acked only after the re-create succeeded
        assertEquals(LedgerState.REQUESTED, backend.get("a")?.state)
        assertEquals(1, backend.get("a")?.attempt)
    }

    @Test
    fun create_failure_records_no_requested_and_does_not_cap() = runTest {
        val backend = InMemoryLedgerBackend()
        val platform = FakePlatform(discovered = listOf(resource("a")), failCreate = true)
        val store = FakeStore()

        val result = cycleOver(backend, platform, store).run()

        assertEquals(CycleResult.COMPLETED, result) // a create FAILURE is not the cap
        assertNull(backend.get("a")) // no REQUESTED recorded for a job that was never created
        assertContentEquals(byteArrayOf(9), store.saved) // cursor still advances (no cap)
    }

    @Test
    fun already_completed_re_handed_job_is_a_noop_acknowledge() = runTest {
        val backend = InMemoryLedgerBackend()
        LedgerWriter(backend).recordCompleted("a", assetId = "a", attempt = 0)
        val job = platformJob("a", PlatformJobState.FAILED, UploadError.Network)
        val platform = FakePlatform(ackJobs = listOf(job))

        cycleOver(backend, platform).run()

        assertEquals(listOf(job), platform.acknowledged)
        assertTrue(platform.created.isEmpty(), "an already-COMPLETED key is not re-created")
        assertEquals(LedgerState.COMPLETED, backend.get("a")?.state)
    }

    @Test
    fun cap_during_discovery_does_not_advance_the_cursor_and_returns_processing() = runTest {
        val backend = InMemoryLedgerBackend()
        val platform = FakePlatform(
            discovered = listOf(resource("a"), resource("b"), resource("c")),
            limitAfter = 2,
        )
        val store = FakeStore()

        val result = cycleOver(backend, platform, store).run()

        assertEquals(CycleResult.PROCESSING, result)
        assertEquals(listOf("a", "b"), platform.created.map { it.filename })
        assertNull(store.saved, "cursor must NOT advance on a cap-truncated cycle")
    }

    @Test
    fun removed_asset_rows_are_pruned_incrementally_by_assetId() = runTest {
        val backend = InMemoryLedgerBackend()
        LedgerWriter(backend).recordCompleted("A_1-photo.jpg", assetId = "A_1", attempt = 0)
        LedgerWriter(backend).recordRequested("A_1-video.mov", assetId = "A_1", attempt = 0)
        LedgerWriter(backend).recordCompleted("B-photo.jpg", assetId = "B", attempt = 0)
        val platform = FakePlatform(removedAssetIds = listOf("A_1"))

        cycleOver(backend, platform).run()

        assertNull(backend.get("A_1-photo.jpg"), "deleted asset's rows are pruned")
        assertNull(backend.get("A_1-video.mov"))
        assertEquals(LedgerState.COMPLETED, backend.get("B-photo.jpg")?.state, "other assets untouched")
    }

    @Test
    fun mid_upload_deletion_clears_the_stuck_pending_row() = runTest {
        val backend = InMemoryLedgerBackend()
        // A photo deleted before its upload finished: a REQUESTED row discovery never revisits.
        LedgerWriter(backend).recordRequested("gone-photo.jpg", assetId = "gone", attempt = 0)
        assertEquals(1, backend.aggregates().pending)
        val platform = FakePlatform(removedAssetIds = listOf("gone"))

        val result = cycleOver(backend, platform).run()

        assertEquals(CycleResult.COMPLETED, result)
        assertNull(backend.get("gone-photo.jpg"))
        assertEquals(0, backend.aggregates().pending, "no phantom pending pins the extension awake")
    }

    @Test
    fun full_enumeration_reconciles_the_ledger_against_the_live_library() = runTest {
        val backend = InMemoryLedgerBackend()
        LedgerWriter(backend).recordCompleted("old-photo.jpg", assetId = "old", attempt = 0) // absent now
        val platform = FakePlatform(discovered = listOf(resource("a-photo.jpg")), fullEnumeration = true)
        val store = FakeStore()

        val result = cycleOver(backend, platform, store).run()

        assertEquals(CycleResult.COMPLETED, result)
        assertNull(backend.get("old-photo.jpg"), "row for an asset no longer present is reconciled away")
        assertEquals(LedgerState.REQUESTED, backend.get("a-photo.jpg")?.state, "live resource kept/uploaded")
    }

    @Test
    fun reconcile_is_skipped_on_a_cap_truncated_full_enumeration() = runTest {
        val backend = InMemoryLedgerBackend()
        LedgerWriter(backend).recordCompleted("old-photo.jpg", assetId = "old", attempt = 0)
        val platform = FakePlatform(
            discovered = listOf(resource("a-photo.jpg"), resource("b-photo.jpg")),
            fullEnumeration = true,
            limitAfter = 1, // cap mid-create → PROCESSING before reconcile
        )
        val store = FakeStore()

        val result = cycleOver(backend, platform, store).run()

        assertEquals(CycleResult.PROCESSING, result)
        assertEquals(LedgerState.COMPLETED, backend.get("old-photo.jpg")?.state, "no reconcile on a cap-truncated cycle")
        assertNull(store.saved, "cursor must NOT advance")
    }

    @Test
    fun reconcile_does_not_run_on_an_incremental_cycle() = runTest {
        val backend = InMemoryLedgerBackend()
        LedgerWriter(backend).recordCompleted("untouched-photo.jpg", assetId = "untouched", attempt = 0)
        // Incremental (fullEnumeration = false): `discovered` is only the changed subset, never the
        // live asset set, so retainAssets must NOT run or it would wipe everything not just-changed.
        val platform = FakePlatform(discovered = listOf(resource("a-photo.jpg")), fullEnumeration = false)

        cycleOver(backend, platform).run()

        assertEquals(LedgerState.COMPLETED, backend.get("untouched-photo.jpg")?.state)
    }

    @Test
    fun pruned_then_rediscovered_asset_is_uploaded_fresh() = runTest {
        val backend = InMemoryLedgerBackend()
        // Was backed up, then deleted (pruned), then recovered from "Recently Deleted" (re-appears).
        LedgerWriter(backend).recordCompleted("x-photo.jpg", assetId = "x", attempt = 0)
        val platform = FakePlatform(
            discovered = listOf(resource("x-photo.jpg")),
            removedAssetIds = listOf("x"),
        )

        cycleOver(backend, platform).run()

        // Prune dropped the COMPLETED proof, so the re-discovered key is fresh work, not AlreadyUploaded.
        assertEquals(listOf("x-photo.jpg"), platform.created.map { it.filename })
        assertEquals(LedgerState.REQUESTED, backend.get("x-photo.jpg")?.state)
    }

    @Test
    fun cap_during_re_create_still_acknowledges_and_returns_processing() = runTest {
        val backend = InMemoryLedgerBackend()
        LedgerWriter(backend).recordRequested("a", assetId = "a", attempt = 0)
        val job = platformJob("a", PlatformJobState.FAILED, UploadError.Network)
        val platform = FakePlatform(ackJobs = listOf(job), limitAfter = 0)
        val store = FakeStore()

        val result = cycleOver(backend, platform, store).run()

        assertEquals(CycleResult.PROCESSING, result)
        // Every presented job is acknowledged (else the system errors 50008); rediscovery retries it.
        assertEquals(listOf(job), platform.acknowledged)
        assertNull(store.saved, "cursor must NOT advance on a cap-truncated cycle")
    }

    // ── Notify hook (capability `upload-completion-notify`) ──────────────────────────────────────────

    /** Build a cycle recording the order its best-effort hooks fire, so the manifest→notify order is asserted. */
    private fun cycleWithHooks(
        backend: InMemoryLedgerBackend,
        platform: FakePlatform,
        order: MutableList<String>,
        store: DiscoveryStore = FakeStore(),
        notifyThrows: Boolean = false,
    ): UploadCycle {
        val ledger = LedgerWriter(backend)
        return UploadCycle(
            SyncEngine(StubUploadRequestProvider(), ledger), ledger, platform, store,
            onDiscovery = { order += "manifest" },
            onBatchUploaded = {
                order += "notify"
                if (notifyThrows) error("notify boom")
            },
        )
    }

    @Test
    fun drained_cycle_with_a_completion_notifies_once_after_the_manifest_write() = runTest {
        val backend = InMemoryLedgerBackend()
        // A succeeded job (a real completion) and nothing new to discover → drains COMPLETED.
        val platform = FakePlatform(ackJobs = listOf(platformJob("a-primary.jpg", PlatformJobState.SUCCEEDED)))
        val order = mutableListOf<String>()

        val result = cycleWithHooks(backend, platform, order).run()

        assertEquals(CycleResult.COMPLETED, result)
        assertEquals(listOf("manifest", "notify"), order) // fires once, AFTER the device-manifest PUT
    }

    @Test
    fun cap_truncated_cycle_does_not_notify_even_with_a_completion() = runTest {
        val backend = InMemoryLedgerBackend()
        // A completion in Phase 2, but Phase 3 discovery hits the cap → PROCESSING before the notify point.
        val platform = FakePlatform(
            ackJobs = listOf(platformJob("done-primary.jpg", PlatformJobState.SUCCEEDED)),
            discovered = listOf(resource("a"), resource("b"), resource("c")),
            limitAfter = 2,
        )
        val order = mutableListOf<String>()

        val result = cycleWithHooks(backend, platform, order).run()

        assertEquals(CycleResult.PROCESSING, result)
        assertTrue(order.isEmpty(), "a cap-truncated cycle refreshes no manifest and fires no notify")
    }

    @Test
    fun drained_cycle_with_no_completion_does_not_notify() = runTest {
        val backend = InMemoryLedgerBackend()
        // New work discovered and created, but nothing COMPLETED this cycle.
        val platform = FakePlatform(discovered = listOf(resource("a")))
        val order = mutableListOf<String>()

        val result = cycleWithHooks(backend, platform, order).run()

        assertEquals(CycleResult.COMPLETED, result)
        assertEquals(listOf("manifest"), order) // manifest PUT ran; notify did not
    }

    @Test
    fun a_throwing_notify_does_not_fail_the_cycle() = runTest {
        val backend = InMemoryLedgerBackend()
        val platform = FakePlatform(ackJobs = listOf(platformJob("a-primary.jpg", PlatformJobState.SUCCEEDED)))
        val store = FakeStore()
        val order = mutableListOf<String>()

        val result = cycleWithHooks(backend, platform, order, store, notifyThrows = true).run()

        assertEquals(CycleResult.COMPLETED, result) // best-effort: the failure is absorbed
        assertEquals(listOf("manifest", "notify"), order)
        assertContentEquals(byteArrayOf(9), store.saved) // cursor still advanced despite the notify failure
    }

    @Test
    fun a_duplicate_succeeded_on_an_already_completed_key_does_not_notify() = runTest {
        val backend = InMemoryLedgerBackend()
        // The key is already COMPLETED; the OS re-hands a SUCCEEDED job (at-least-once delivery). This
        // duplicate is not new work — it must not fire a spurious notify.
        LedgerWriter(backend).recordCompleted("a-primary.jpg", assetId = "a", attempt = 0)
        val platform = FakePlatform(ackJobs = listOf(platformJob("a-primary.jpg", PlatformJobState.SUCCEEDED)))
        val order = mutableListOf<String>()

        val result = cycleWithHooks(backend, platform, order).run()

        assertEquals(CycleResult.COMPLETED, result)
        assertEquals(listOf("manifest"), order) // manifest re-PUT, but no completion counted → no notify
    }

    @Test
    fun a_pure_re_ack_failed_job_on_a_completed_key_does_not_notify() = runTest {
        val backend = InMemoryLedgerBackend()
        LedgerWriter(backend).recordCompleted("a-primary.jpg", assetId = "a", attempt = 0)
        // A FAILED job whose key is already COMPLETED → the re-ack arm (no UploadCompleted, no count).
        val platform = FakePlatform(
            ackJobs = listOf(platformJob("a-primary.jpg", PlatformJobState.FAILED, UploadError.Network)),
        )
        val order = mutableListOf<String>()

        val result = cycleWithHooks(backend, platform, order).run()

        assertEquals(CycleResult.COMPLETED, result)
        assertEquals(listOf("manifest"), order) // re-ack is not a completion → no notify
    }

    // ── Capture-date cutoff (capability `photo-date-cutoff`) ──────────────────────────────────────────

    private fun datedResource(name: String, creationDate: String, assetId: String = name) =
        Resource(
            filename = name, assetId = assetId, contentType = "image/jpeg",
            metadata = mapOf(RESOURCE_META_CREATION_DATE to creationDate), data = Unit,
        )

    private fun cycleWithCutoff(
        backend: InMemoryLedgerBackend,
        platform: FakePlatform,
        cutoff: String?,
    ): UploadCycle {
        val ledger = LedgerWriter(backend)
        return UploadCycle(
            SyncEngine(StubUploadRequestProvider(), ledger), ledger, platform, FakeStore(),
            photoCutoff = { cutoff },
        )
    }

    @Test
    fun cutoff_excludes_pre_cutoff_resources_from_upload() = runTest {
        val backend = InMemoryLedgerBackend()
        val platform = FakePlatform(
            discovered = listOf(
                datedResource("old-primary.jpg", "2026-07-01T00:00:00Z", "old"),
                datedResource("new-primary.jpg", "2026-07-10T00:00:00Z", "new"),
            ),
        )

        cycleWithCutoff(backend, platform, "2026-07-06T00:00:00Z").run()

        assertEquals(listOf("new-primary.jpg"), platform.created.map { it.filename })
        assertNull(backend.get("old-primary.jpg"), "a pre-cutoff asset creates no ledger row")
    }

    @Test
    fun cutoff_applies_on_the_incremental_walk_too() = runTest {
        val backend = InMemoryLedgerBackend()
        val platform = FakePlatform(
            discovered = listOf(datedResource("old-primary.jpg", "2026-07-01T00:00:00Z", "old")),
            fullEnumeration = false,
        )

        cycleWithCutoff(backend, platform, "2026-07-06T00:00:00Z").run()

        assertTrue(platform.created.isEmpty(), "a pre-cutoff changed asset is excluded on the incremental walk")
    }

    @Test
    fun a_null_cutoff_uploads_the_whole_library() = runTest {
        val backend = InMemoryLedgerBackend()
        val platform = FakePlatform(
            discovered = listOf(datedResource("old-primary.jpg", "2000-01-01T00:00:00Z", "old")),
        )

        cycleWithCutoff(backend, platform, null).run()

        assertEquals(listOf("old-primary.jpg"), platform.created.map { it.filename }, "null cutoff = whole-library")
    }

    @Test
    fun an_undated_asset_is_excluded_under_a_cutoff() = runTest {
        val backend = InMemoryLedgerBackend()
        // No creationDate metadata → empty string, which sorts before any non-empty cutoff.
        val platform = FakePlatform(discovered = listOf(resource("undated-primary.jpg", "undated")))

        cycleWithCutoff(backend, platform, "2026-07-06T00:00:00Z").run()

        assertTrue(platform.created.isEmpty(), "an asset with no creationDate is out of scope under a cutoff")
    }
}
