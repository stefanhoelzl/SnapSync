package app.snapsync.feature.upload

import app.snapsync.ports.CreateResult
import app.snapsync.ports.CycleResult
import app.snapsync.ports.Discovery
import app.snapsync.ports.DiscoveryStore
import app.snapsync.ports.PlatformJobState
import app.snapsync.ports.PlatformUploadJob
import app.snapsync.ports.BackgroundTransfer

import app.snapsync.model.candidatesFromResources
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.feature.upload.LedgerWriter
import app.snapsync.model.Resource
import app.snapsync.feature.upload.SyncEngine
import app.snapsync.model.UploadError
import app.snapsync.model.UploadRequest
import app.snapsync.model.UploadRequestProvider
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.captureCutoff
import app.snapsync.model.RESOURCE_META_CREATION_DATE
import app.snapsync.model.RESOURCE_META_IS_EDITED
import app.snapsync.model.RESOURCE_META_IS_SCREENSHOT
import app.snapsync.model.RESOURCE_META_IS_SCREEN_RECORDING
import app.snapsync.model.RESOURCE_META_IS_VIDEO
import app.snapsync.model.RESOURCE_META_PIXEL_AREA
import app.snapsync.model.RESOURCE_META_MIME
import app.snapsync.model.normalizeAssetId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UploadCycleTest {

    /**
     * A permissive test cutoff, and a capture date after it. Every membership carries a cutoff (capability
     * `photo-selection-policy`), so a cycle cannot be built without one; these keep the non-cutoff tests
     * exercising what they mean to.
     */
    private companion object {
        const val TEST_CUTOFF = "2026-01-01T00:00:00Z"

        /** An admitting policy over [cutoff], unbounded above — the shape every cycle fixture wants. */
        fun admitting(cutoff: String): SelectionPolicy =
            SelectionPolicy.from(includesUpload = true, cutoff = captureCutoff(cutoff), ceiling = null)
        const val IN_SCOPE_DATE = "2026-06-01T10:00:00Z"
        const val TEST_HOST = "https://edge.example"
        const val TEST_EVENT = "event-1"
    }

    /** A no-network provider returning a throwaway destination — the cycle never inspects the URL. */
    private class StubUploadRequestProvider : UploadRequestProvider {
        override suspend fun provide(resource: Resource): UploadRequest =
            UploadRequest(url = "https://stub.invalid/${resource.filename}", headers = emptyMap(), resource = resource)
    }

    /** Records what the cycle asked the platform to do; serves canned discovered/returned jobs. */
    private class FakePlatform(
        val discovered: List<Resource> = emptyList(),
        private val retryJobs: List<PlatformUploadJob> = emptyList(),
        private val ackJobs: List<PlatformUploadJob> = emptyList(),
        private val nextToken: ByteArray = byteArrayOf(9),
        private val limitAfter: Int = Int.MAX_VALUE,
        private val failCreate: Boolean = false,
        private val removedAssetIds: List<String> = emptyList(),
        private val fullEnumeration: Boolean = false,
    ) : BackgroundTransfer {
        val created = mutableListOf<Resource>()
        val retried = mutableListOf<PlatformUploadJob>()
        val acknowledged = mutableListOf<PlatformUploadJob>()
        var discoverTokenArg: ByteArray? = null
        var discoverPolicyArg: SelectionPolicy? = null
        private var creates = 0

        override suspend fun fetchRetryJobs() = retryJobs
        override suspend fun fetchAckJobs() = ackJobs
        override suspend fun retryJob(job: PlatformUploadJob, request: UploadRequest) { retried += job }
        override suspend fun acknowledge(job: PlatformUploadJob) { acknowledged += job }
        override suspend fun discoverResources(sinceToken: ByteArray?, policy: SelectionPolicy): Discovery {
            discoverTokenArg = sinceToken
            discoverPolicyArg = policy
            // The fake returns HELD candidates: it stands in for a platform whose discovery already
            // carried resources, which is the honest shape for an in-memory fixture. It deliberately
            // does NOT narrow by the policy — a fake that mirrored the real fetch predicate would hide
            // an admission relying on the fetch to have already excluded something.
            return Discovery(candidatesFromResources(discovered), nextToken, removedAssetIds, fullEnumeration)
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

    // Dated by default: every membership carries a cutoff (capability `photo-selection-policy`), and an asset
    // with no `creationDate` sorts before any cutoff, so an undated resource is always out of scope.
    private fun resource(name: String, assetId: String = name) =
        Resource(
            filename = name, assetId = assetId, contentType = "image/jpeg",
            metadata = mapOf(RESOURCE_META_CREATION_DATE to IN_SCOPE_DATE), data = Unit,
        )

    private fun platformJob(key: String, state: PlatformJobState, error: UploadError? = null) =
        PlatformUploadJob(key = key, contentType = "image/jpeg", state = state, error = error, data = Unit, handle = Unit)

    /**
     * The one place a cycle is built for these tests, so each test states only what it is about.
     *
     * The defaults live HERE, once and visibly, rather than on `UploadCycle`'s own parameters — that is the
     * distinction the required-ports rule draws (capability `upload-lifecycle`). A default on the class
     * lets a *composition root* inherit an unstated policy, which is how the app-driven tier shipped
     * without a re-join reconciler and how it nearly shipped without an album denylist. A default in a test
     * helper is an answer stated once, in the file that reads it.
     *
     * [readGate] defaults to a joined membership on [TEST_EVENT]: nearly every test here is about the
     * phases, not the entry gate, and the gate's own three outcomes are covered in [CycleGateTest] and in
     * the entry-gate tests below.
     */
    private fun cycle(
        backend: InMemoryLedgerStore,
        platform: FakePlatform,
        store: DiscoveryStore = FakeStore(),
        policy: SelectionPolicy = admitting(TEST_CUTOFF),
        saveToAlbum: Boolean = true,
        readGate: (() -> CycleGate)? = null,
        reconcile: suspend (String?) -> Boolean = { true }, // a settled join unless a test says otherwise
        onDiscovery: suspend (String, SelectionPolicy) -> Unit = { _, _ -> },
        suppressedAssetIds: suspend () -> Set<String> = { emptySet() },
        albumExcludedAssetIds: suspend (CaptureCutoff) -> Set<String> = { emptySet() },
        onBatchUploaded: suspend (String) -> Unit = {},
        placeInAlbum: suspend (String, Set<String>) -> Unit = { _, _ -> },
    ): UploadCycle {
        val ledger = LedgerWriter(backend)
        return UploadCycle(
            readGate = readGate ?: {
                CycleGate.Run(
                    UploadConfig(host = TEST_HOST, eventId = TEST_EVENT),
                    JoinedMembership(eventId = TEST_EVENT, policy = policy, saveToAlbum = saveToAlbum),
                )
            },
            engineFor = { config -> SyncEngine(StubUploadRequestProvider(), ledger, config.eventId) },
            ledger = ledger,
            platform = platform,
            store = store,
            reconcile = reconcile,
            onDiscovery = onDiscovery,
            suppressedAssetIds = suppressedAssetIds,
            albumExcludedAssetIds = albumExcludedAssetIds,
            onBatchUploaded = onBatchUploaded,
            placeInAlbum = placeInAlbum,
        )
    }

    private fun cycleOver(
        backend: InMemoryLedgerStore,
        platform: FakePlatform,
        store: DiscoveryStore = FakeStore(),
    ): UploadCycle = cycle(backend, platform, store)

    // ---- The entry gate (capability `upload-lifecycle`) -----------------------------------------------
    // The three-state membership read, decided HERE rather than in each composition root. A root reaches
    // this decision only for the tiers its author enumerated: the OS-invoked tier gated on `cycleGate`, and
    // the app-driven tier read a two-state `StateFlow` that cannot express "unreadable" — so a failed
    // Keychain read arrived as a leave and cleared the join marker of a device that never left.

    @Test
    fun an_unreadable_membership_touches_nothing() = runTest {
        val backend = InMemoryLedgerStore()
        // A cursor that must not advance, and a library full of admissible work: the ONLY reason nothing
        // happens is that the membership could not be read.
        val store = FakeStore(token = "cursor-before".encodeToByteArray())
        val touched = mutableListOf<String>()
        val platform = FakePlatform(
            discovered = listOf(resource("A-primary.heic"), resource("B-primary.heic")),
            fullEnumeration = true,
        )

        val result = cycle(
            backend, platform, store,
            readGate = { CycleGate.Skip("config status=-25308, deviceId readable=false") },
            reconcile = { touched += "reconcile"; true },
            onDiscovery = { _, _ -> touched += "discovery" },
            onBatchUploaded = { touched += "notify" },
        ).run()

        assertEquals(CycleResult.COMPLETED, result, "an unreadable read is a clean no-op, never a failure")
        assertEquals(emptyList<String>(), touched, "unreadable ≠ left: no reconcile, no marker clear, no hooks")
        assertEquals(emptyList<String>(), platform.created.map { it.filename }, "no upload job")
        assertNull(store.saved, "the discovery cursor must not advance")
    }

    // THE regression this gate exists for, at the choke point: the reconciler's `null` call is what clears
    // the persisted joinedEventId marker, and an unreadable config must never reach it.
    @Test
    fun an_unreadable_membership_never_reaches_the_leave_side_reconcile() = runTest {
        val backend = InMemoryLedgerStore()
        var reconciledWith: List<String?> = emptyList()

        cycle(
            backend, FakePlatform(), FakeStore(),
            readGate = { CycleGate.Skip("protected data unavailable") },
            reconcile = { eventId -> reconciledWith = reconciledWith + eventId; true },
        ).run()

        assertEquals(emptyList<String?>(), reconciledWith, "the marker of a device that never left must survive")
    }

    @Test
    fun a_definitively_absent_membership_reconciles_the_leave_side_and_uploads_nothing() = runTest {
        val backend = InMemoryLedgerStore()
        val platform = FakePlatform(discovered = listOf(resource("A-primary.heic")))
        var reconciledWith: List<String?> = listOf("unset")

        val result = cycle(
            backend, platform, FakeStore(),
            readGate = { CycleGate.NotJoined },
            reconcile = { eventId -> reconciledWith = listOf(eventId); true },
        ).run()

        assertEquals(CycleResult.COMPLETED, result)
        assertEquals(listOf<String?>(null), reconciledWith, "a real leave still clears the join marker")
        assertEquals(emptyList<String>(), platform.created.map { it.filename }, "a leave creates no upload job")
    }

    @Test
    fun a_failing_leave_side_reconcile_still_completes_cleanly() = runTest {
        val result = cycle(
            InMemoryLedgerStore(), FakePlatform(), FakeStore(),
            readGate = { CycleGate.NotJoined },
            reconcile = { error("marker clear boom") },
        ).run()

        assertEquals(CycleResult.COMPLETED, result, "a failed marker clear is a warning, never a FAILED cycle")
    }

    // ---- The provenance backfill (spec `sync-ledger`, migration 4.sqm) --------------------------------
    // The 4.sqm migration leaves every pre-existing row with the `eventId = ''` sentinel (the true value
    // lives in config, unreachable from SQL). The single writer's cycle — the one seat that runs on BOTH
    // tiers and never in a reader — sweeps the sentinel to the live event id once per settled entry.

    @Test
    fun the_cycle_backfills_pre_provenance_rows_and_new_records_carry_the_live_event() = runTest {
        val backend = InMemoryLedgerStore()
        // Two rows as 4.sqm leaves them (sentinel), one row already carrying provenance.
        backend.put(LedgerEntry("A-photo.jpg", "A", LedgerState.COMPLETED, 0, eventId = ""))
        backend.put(LedgerEntry("B-photo.jpg", "B", LedgerState.FAILED, 1, eventId = ""))
        backend.put(LedgerEntry("C-photo.jpg", "C", LedgerState.COMPLETED, 0, eventId = "OLD"))
        val platform = FakePlatform(discovered = listOf(resource("N-primary.heic", "N")))

        cycleOver(backend, platform).run()

        assertEquals(TEST_EVENT, backend.get("A-photo.jpg")?.eventId, "sentinel rows sweep to the live event")
        assertEquals(LedgerState.COMPLETED, backend.get("A-photo.jpg")?.state, "the sweep changes provenance only")
        assertEquals("OLD", backend.get("C-photo.jpg")?.eventId, "rows with provenance are never rewritten")
        // The FAILED sentinel row is swept too (not rediscovered this cycle, so its state stands).
        assertEquals(TEST_EVENT, backend.get("B-photo.jpg")?.eventId)
        assertEquals(LedgerState.FAILED, backend.get("B-photo.jpg")?.state)
        assertEquals(TEST_EVENT, backend.get("N-primary.heic")?.eventId, "new records carry the live event id")
        assertEquals(LedgerState.REQUESTED, backend.get("N-primary.heic")?.state)
    }

    @Test
    fun a_deferred_reconcile_defers_the_backfill_too() = runTest {
        // The sweep sits AFTER the reconcile gate: an unsettled membership (deferred listing) labels
        // nothing this cycle — the sentinel survives for the next, settled cycle to sweep.
        val backend = InMemoryLedgerStore()
        backend.put(LedgerEntry("A-photo.jpg", "A", LedgerState.COMPLETED, 0, eventId = ""))

        cycle(backend, FakePlatform(), FakeStore(), reconcile = { false }).run()

        assertEquals("", backend.get("A-photo.jpg")?.eventId)
    }

    @Test
    fun the_gate_is_re_read_every_cycle_so_a_leave_takes_effect_without_a_relaunch() = runTest {
        // The cycle is long-lived: a tier whose process survives across cycles must see a membership
        // change on the next run, not on the next launch.
        val backend = InMemoryLedgerStore()
        val platform = FakePlatform(discovered = listOf(resource("A-primary.heic")))
        var joined = true

        val c = cycle(
            backend, platform, FakeStore(),
            readGate = {
                if (joined) {
                    CycleGate.Run(
                        UploadConfig(TEST_HOST, TEST_EVENT),
                        JoinedMembership(TEST_EVENT, admitting(TEST_CUTOFF), saveToAlbum = false),
                    )
                } else {
                    CycleGate.NotJoined
                }
            },
        )

        c.run()
        assertEquals(1, platform.created.size, "joined: the cycle uploads")

        joined = false
        c.run()
        assertEquals(1, platform.created.size, "left: the SAME cycle instance creates nothing more")
    }

    // ---- The direction gate (capability `upload-lifecycle`) -------------------------------------------
    // It sits at the CHOKE POINT — this function, which every trigger on every tier funnels through — and
    // NOT at the arm's invoker. An invoker-gate is only as sound as its enumeration of invokers, and a new
    // tier invalidates that enumeration silently: D3 of `2026-07-07-add-join-direction-mode` reasoned "the
    // producer is never enabled, so the OS never invokes the extension", three days after a tier shipped in
    // which the APP invokes the cycle. A download-only membership then uploaded the member's camera roll on
    // every foreground, while the join gate promised "you won't share yours".

    /** Builds a cycle for a membership that contributes nothing, recording anything it dares to do. */
    private fun decliningCycle(
        backend: InMemoryLedgerStore,
        platform: FakePlatform,
        store: DiscoveryStore,
        order: MutableList<String> = mutableListOf(),
    ): UploadCycle = cycle(
        backend, platform, store,
        policy = SelectionPolicy.None,
        onDiscovery = { _, _ -> order += "discovery" },
        onBatchUploaded = { order += "notify" },
        reconcile = { order += "reconcile"; true },
    )

    @Test
    fun a_non_contributing_membership_creates_no_job_and_lists_nothing() = runTest {
        val store = FakeStore()
        // A library full of perfectly admissible work: in scope, no origin exclusion, nothing in flight.
        // The ONLY reason nothing happens is the membership's direction.
        val platform = FakePlatform(
            discovered = listOf(resource("a"), resource("b")),
            ackJobs = listOf(platformJob("c-primary.heic", PlatformJobState.SUCCEEDED)),
        )
        val order = mutableListOf<String>()

        val result = decliningCycle(InMemoryLedgerStore(), platform, store, order).run()

        assertEquals(CycleResult.SKIPPED, result, "declined, and distinguishable from a drained cycle")
        assertTrue(platform.created.isEmpty(), "no upload job for a membership that contributes nothing")
        // The union-leak half, and the worse one: a manifest listing the member's assets offers them to
        // every other member as bytes that were never uploaded (capability `photo-selection-policy`).
        assertTrue("discovery" !in order, "no device manifest is written")
        assertTrue("notify" !in order, "no event notify is fired")
    }

    /**
     * The gate precedes EVERYTHING — including the reconcile. A non-contributor must not walk its library to
     * discover it contributes nothing: the walk costs one PhotoKit round-trip per asset (~110 ms on an SE2),
     * so a per-asset answer would spend minutes arriving at the empty set.
     */
    @Test
    fun the_gate_precedes_the_reconcile_and_the_walk() = runTest {
        val store = FakeStore()
        val platform = FakePlatform(discovered = listOf(resource("a")))
        val order = mutableListOf<String>()

        decliningCycle(InMemoryLedgerStore(), platform, store, order).run()

        assertEquals(emptyList(), order, "nothing runs — not even the reconcile")
        assertNull(platform.discoverPolicyArg, "the library is never enumerated")
    }

    /** A declined cycle discovered nothing, so it advances nothing: the cursor stays exactly where it was. */
    @Test
    fun a_declined_cycle_does_not_advance_or_clear_the_discovery_cursor() = runTest {
        val store = FakeStore()
        val platform = FakePlatform(discovered = listOf(resource("a")))

        decliningCycle(InMemoryLedgerStore(), platform, store).run()

        assertNull(store.saved, "the cursor must not advance")
        assertTrue(!store.cleared, "nor be cleared — this membership's state is untouched, not reset")
    }

    // ---- Phase 0: the re-join reconciliation gate (capability `event-rejoin-reconciliation`) ----------
    // The gate lives in the CYCLE, not in each tier's composition root, because the cycle is the only
    // thing that runs on every route to a divergent ledger. Root-wired reconciliation is exactly how the
    // app-driven tier shipped with none, re-uploading the whole post-cutoff library after a reinstall.

    /** Builds a cycle whose reconcile gate is [gate], recording call order into [order]. */
    private fun gatedCycle(
        backend: InMemoryLedgerStore,
        platform: FakePlatform,
        store: DiscoveryStore = FakeStore(),
        order: MutableList<String> = mutableListOf(),
        gate: suspend () -> Boolean,
    ): UploadCycle = cycle(
        backend, platform, store,
        onDiscovery = { _, _ -> order += "discovery" },
        reconcile = { order += "reconcile"; gate() },
    )

    @Test
    fun reconcile_runs_before_any_upload_job_is_created() = runTest {
        val order = mutableListOf<String>()
        val platform = FakePlatform(discovered = listOf(resource("a")))
        val cycle = gatedCycle(InMemoryLedgerStore(), platform, order = order) { true }

        assertEquals(CycleResult.COMPLETED, cycle.run())

        assertEquals("reconcile", order.first(), "the seed must precede everything the cycle does")
        assertEquals(listOf("a"), platform.created.map { it.filename })
    }

    @Test
    fun a_deferred_reconcile_creates_no_jobs_and_reports_a_clean_completed() = runTest {
        val store = FakeStore()
        val platform = FakePlatform(
            discovered = listOf(resource("a")),
            ackJobs = listOf(platformJob("b-primary.heic", PlatformJobState.SUCCEEDED)),
        )
        // A failed/timed-out device listing: the reconciler returns false rather than seeding.
        val cycle = gatedCycle(InMemoryLedgerStore(), platform, store) { false }

        // COMPLETED, never FAILED: a deferral is a clean no-op so the tier's scheduler simply retries.
        assertEquals(CycleResult.COMPLETED, cycle.run())

        assertTrue(platform.created.isEmpty(), "a deferred cycle must create no upload jobs")
        assertTrue(platform.acknowledged.isEmpty(), "a deferred cycle must not adjudicate jobs either")
        assertNull(platform.discoverPolicyArg, "a deferred cycle must not even walk the library")
        assertNull(store.saved, "the cursor must not advance on a deferred cycle")
        assertTrue(!store.cleared, "a deferral leaves the cursor untouched so the next cycle retries")
    }

    @Test
    fun a_throwing_reconcile_defers_rather_than_failing_the_cycle() = runTest {
        val platform = FakePlatform(discovered = listOf(resource("a")))
        val cycle = gatedCycle(InMemoryLedgerStore(), platform) { error("listing boom") }

        // The roots previously wrapped reconcile in their own runCatching; moving the gate into the cycle
        // must not turn a throwing reconcile into a FAILED cycle.
        assertEquals(CycleResult.COMPLETED, cycle.run())
        assertTrue(platform.created.isEmpty(), "a throwing reconcile must not upload anything")
    }

    @Test
    fun discovery_creates_a_job_per_new_resource_and_records_requested_after_create() = runTest {
        val backend = InMemoryLedgerStore()
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
        val backend = InMemoryLedgerStore()
        LedgerWriter(backend).recordRequested(resource("a", "a"), attempt = 0, eventId = TEST_EVENT) // in flight
        LedgerWriter(backend).recordCompleted(resource("b", "b"), attempt = 0, eventId = TEST_EVENT) // done
        val platform = FakePlatform(discovered = listOf(resource("a"), resource("b")))

        cycleOver(backend, platform).run()

        assertTrue(platform.created.isEmpty(), "in-flight (REQUESTED) and COMPLETED keys must be skipped")
    }

    @Test
    fun discovery_does_not_re_upload_a_completed_key() = runTest {
        // Uploaded resources are immutable: a COMPLETED key is never re-uploaded, even when the same
        // asset is re-discovered (e.g. after a metadata-only change).
        val backend = InMemoryLedgerStore()
        LedgerWriter(backend).recordCompleted(resource("a", "a"), attempt = 0, eventId = TEST_EVENT)
        val platform = FakePlatform(discovered = listOf(resource("a")))

        cycleOver(backend, platform).run()

        assertTrue(platform.created.isEmpty(), "a COMPLETED key must never be re-uploaded")
    }

    @Test
    fun suppressed_downloaded_assets_create_no_job_and_are_pruned_from_retain() = runTest {
        // FOREIGN is an asset this device downloaded + imported (in the suppression set). A stale
        // COMPLETED row stands in for a pre-suppression echo; it must be pruned and never re-uploaded.
        val backend = InMemoryLedgerStore()
        LedgerWriter(backend).recordCompleted(resource("FOREIGN-primary.heic", "FOREIGN"), attempt = 0, eventId = TEST_EVENT)
        val platform = FakePlatform(
            discovered = listOf(resource("FOREIGN-primary.heic", "FOREIGN"), resource("MINE-primary.heic", "MINE")),
            fullEnumeration = true,
        )
        val cycle = cycle(backend, platform, suppressedAssetIds = { setOf("FOREIGN") })

        cycle.run()

        assertEquals(listOf("MINE-primary.heic"), platform.created.map { it.filename }) // FOREIGN suppressed
        assertNull(backend.get("FOREIGN-primary.heic"), "suppressed asset's stale row pruned by retainAssets")
    }

    @Test
    fun suppression_matches_on_the_normalized_assetid() = runTest {
        // Both sides normalize the raw PHAsset localIdentifier '/'→'_': discovery via the gallery
        // enumerator's `normalizeAssetId`, the download importer before storing `createdLocalId`. A raw
        // id "ABC/L0/001" must therefore suppress as "ABC_L0_001" — the §7.6 load-bearing contract.
        val backend = InMemoryLedgerStore()
        val normalized = normalizeAssetId("ABC/L0/001") // "ABC_L0_001" — the discovery-side transform
        val platform = FakePlatform(discovered = listOf(resource("$normalized-primary.heic", normalized)))
        val cycle = cycle(
            backend, platform,
            // The importer stored the '/'→'_' createdLocalId — the same normalized string.
            suppressedAssetIds = { setOf("ABC_L0_001") },
        )

        cycle.run()

        assertTrue(platform.created.isEmpty(), "a downloaded asset must be suppressed on its normalized id")
    }

    @Test
    fun discovery_passes_the_loaded_cursor_to_the_platform() = runTest {
        val backend = InMemoryLedgerStore()
        val platform = FakePlatform()
        val store = FakeStore(token = byteArrayOf(7))

        cycleOver(backend, platform, store).run()

        assertContentEquals(byteArrayOf(7), platform.discoverTokenArg)
    }

    @Test
    fun succeeded_job_records_completed_and_is_acknowledged() = runTest {
        val backend = InMemoryLedgerStore()
        LedgerWriter(backend).recordRequested(resource("a", "a"), attempt = 0, eventId = TEST_EVENT)
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
        val backend = InMemoryLedgerStore()
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
        val backend = InMemoryLedgerStore()
        val job = platformJob("", PlatformJobState.SUCCEEDED)
        val platform = FakePlatform(ackJobs = listOf(job))

        cycleOver(backend, platform).run()

        assertNull(backend.get(""), "no phantom row for an unrecoverable key")
        assertEquals(listOf(job), platform.acknowledged)
    }

    @Test
    fun first_failure_retries_with_a_fresh_url_and_records_requested() = runTest {
        val backend = InMemoryLedgerStore()
        LedgerWriter(backend).recordRequested(resource("a", "a"), attempt = 0, eventId = TEST_EVENT)
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
        val backend = InMemoryLedgerStore()
        LedgerWriter(backend).recordRequested(resource("a", "a"), attempt = 0, eventId = TEST_EVENT)
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
        val backend = InMemoryLedgerStore()
        val platform = FakePlatform(discovered = listOf(resource("a")), failCreate = true)
        val store = FakeStore()

        val result = cycleOver(backend, platform, store).run()

        assertEquals(CycleResult.COMPLETED, result) // a create FAILURE is not the cap
        assertNull(backend.get("a")) // no REQUESTED recorded for a job that was never created
        assertContentEquals(byteArrayOf(9), store.saved) // cursor still advances (no cap)
    }

    @Test
    fun already_completed_re_handed_job_is_a_noop_acknowledge() = runTest {
        val backend = InMemoryLedgerStore()
        LedgerWriter(backend).recordCompleted(resource("a", "a"), attempt = 0, eventId = TEST_EVENT)
        val job = platformJob("a", PlatformJobState.FAILED, UploadError.Network)
        val platform = FakePlatform(ackJobs = listOf(job))

        cycleOver(backend, platform).run()

        assertEquals(listOf(job), platform.acknowledged)
        assertTrue(platform.created.isEmpty(), "an already-COMPLETED key is not re-created")
        assertEquals(LedgerState.COMPLETED, backend.get("a")?.state)
    }

    @Test
    fun cap_during_discovery_does_not_advance_the_cursor_and_returns_processing() = runTest {
        val backend = InMemoryLedgerStore()
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
        val backend = InMemoryLedgerStore()
        LedgerWriter(backend).recordCompleted(resource("A_1-photo.jpg", "A_1"), attempt = 0, eventId = TEST_EVENT)
        LedgerWriter(backend).recordRequested(resource("A_1-video.mov", "A_1"), attempt = 0, eventId = TEST_EVENT)
        LedgerWriter(backend).recordCompleted(resource("B-photo.jpg", "B"), attempt = 0, eventId = TEST_EVENT)
        val platform = FakePlatform(removedAssetIds = listOf("A_1"))

        cycleOver(backend, platform).run()

        assertNull(backend.get("A_1-photo.jpg"), "deleted asset's rows are pruned")
        assertNull(backend.get("A_1-video.mov"))
        assertEquals(LedgerState.COMPLETED, backend.get("B-photo.jpg")?.state, "other assets untouched")
    }

    @Test
    fun mid_upload_deletion_clears_the_stuck_pending_row() = runTest {
        val backend = InMemoryLedgerStore()
        // A photo deleted before its upload finished: a REQUESTED row discovery never revisits.
        LedgerWriter(backend).recordRequested(resource("gone-photo.jpg", "gone"), attempt = 0, eventId = TEST_EVENT)
        assertEquals(1, backend.aggregates().pending)
        val platform = FakePlatform(removedAssetIds = listOf("gone"))

        val result = cycleOver(backend, platform).run()

        assertEquals(CycleResult.COMPLETED, result)
        assertNull(backend.get("gone-photo.jpg"))
        assertEquals(0, backend.aggregates().pending, "no phantom pending pins the extension awake")
    }

    @Test
    fun full_enumeration_reconciles_the_ledger_against_the_live_library() = runTest {
        val backend = InMemoryLedgerStore()
        LedgerWriter(backend).recordCompleted(resource("old-photo.jpg", "old"), attempt = 0, eventId = TEST_EVENT) // absent now
        val platform = FakePlatform(discovered = listOf(resource("a-photo.jpg")), fullEnumeration = true)
        val store = FakeStore()

        val result = cycleOver(backend, platform, store).run()

        assertEquals(CycleResult.COMPLETED, result)
        assertNull(backend.get("old-photo.jpg"), "row for an asset no longer present is reconciled away")
        assertEquals(LedgerState.REQUESTED, backend.get("a-photo.jpg")?.state, "live resource kept/uploaded")
    }

    @Test
    fun reconcile_is_skipped_on_a_cap_truncated_full_enumeration() = runTest {
        val backend = InMemoryLedgerStore()
        LedgerWriter(backend).recordCompleted(resource("old-photo.jpg", "old"), attempt = 0, eventId = TEST_EVENT)
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
        val backend = InMemoryLedgerStore()
        LedgerWriter(backend).recordCompleted(resource("untouched-photo.jpg", "untouched"), attempt = 0, eventId = TEST_EVENT)
        // Incremental (fullEnumeration = false): `discovered` is only the changed subset, never the
        // live asset set, so retainAssets must NOT run or it would wipe everything not just-changed.
        val platform = FakePlatform(discovered = listOf(resource("a-photo.jpg")), fullEnumeration = false)

        cycleOver(backend, platform).run()

        assertEquals(LedgerState.COMPLETED, backend.get("untouched-photo.jpg")?.state)
    }

    @Test
    fun pruned_then_rediscovered_asset_is_uploaded_fresh() = runTest {
        val backend = InMemoryLedgerStore()
        // Was uploaded, then deleted (pruned), then recovered from "Recently Deleted" (re-appears).
        LedgerWriter(backend).recordCompleted(resource("x-photo.jpg", "x"), attempt = 0, eventId = TEST_EVENT)
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
        val backend = InMemoryLedgerStore()
        LedgerWriter(backend).recordRequested(resource("a", "a"), attempt = 0, eventId = TEST_EVENT)
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
        backend: InMemoryLedgerStore,
        platform: FakePlatform,
        order: MutableList<String>,
        store: DiscoveryStore = FakeStore(),
        notifyThrows: Boolean = false,
    ): UploadCycle = cycle(
        backend, platform, store,
        onDiscovery = { _, _ -> order += "manifest" },
        onBatchUploaded = {
            order += "notify"
            if (notifyThrows) error("notify boom")
        },
    )

    @Test
    fun drained_cycle_with_a_completion_notifies_once_after_the_manifest_write() = runTest {
        val backend = InMemoryLedgerStore()
        // A succeeded job (a real completion) and nothing new to discover → drains COMPLETED.
        val platform = FakePlatform(ackJobs = listOf(platformJob("a-primary.jpg", PlatformJobState.SUCCEEDED)))
        val order = mutableListOf<String>()

        val result = cycleWithHooks(backend, platform, order).run()

        assertEquals(CycleResult.COMPLETED, result)
        assertEquals(listOf("manifest", "notify"), order) // fires once, AFTER the device-manifest PUT
    }

    @Test
    fun cap_truncated_cycle_does_not_notify_even_with_a_completion() = runTest {
        val backend = InMemoryLedgerStore()
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
        val backend = InMemoryLedgerStore()
        // New work discovered and created, but nothing COMPLETED this cycle.
        val platform = FakePlatform(discovered = listOf(resource("a")))
        val order = mutableListOf<String>()

        val result = cycleWithHooks(backend, platform, order).run()

        assertEquals(CycleResult.COMPLETED, result)
        assertEquals(listOf("manifest"), order) // manifest PUT ran; notify did not
    }

    @Test
    fun a_throwing_notify_does_not_fail_the_cycle() = runTest {
        val backend = InMemoryLedgerStore()
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
        val backend = InMemoryLedgerStore()
        // The key is already COMPLETED; the OS re-hands a SUCCEEDED job (at-least-once delivery). This
        // duplicate is not new work — it must not fire a spurious notify.
        LedgerWriter(backend).recordCompleted(resource("a-primary.jpg", "a"), attempt = 0, eventId = TEST_EVENT)
        val platform = FakePlatform(ackJobs = listOf(platformJob("a-primary.jpg", PlatformJobState.SUCCEEDED)))
        val order = mutableListOf<String>()

        val result = cycleWithHooks(backend, platform, order).run()

        assertEquals(CycleResult.COMPLETED, result)
        assertEquals(listOf("manifest"), order) // manifest re-PUT, but no completion counted → no notify
    }

    @Test
    fun a_pure_re_ack_failed_job_on_a_completed_key_does_not_notify() = runTest {
        val backend = InMemoryLedgerStore()
        LedgerWriter(backend).recordCompleted(resource("a-primary.jpg", "a"), attempt = 0, eventId = TEST_EVENT)
        // A FAILED job whose key is already COMPLETED → the re-ack arm (no UploadCompleted, no count).
        val platform = FakePlatform(
            ackJobs = listOf(platformJob("a-primary.jpg", PlatformJobState.FAILED, UploadError.Network)),
        )
        val order = mutableListOf<String>()

        val result = cycleWithHooks(backend, platform, order).run()

        assertEquals(CycleResult.COMPLETED, result)
        assertEquals(listOf("manifest"), order) // re-ack is not a completion → no notify
    }

    // ── Capture-date cutoff (capability `photo-selection-policy`) ──────────────────────────────────────────

    private fun datedResource(name: String, creationDate: String, assetId: String = name) =
        Resource(
            filename = name, assetId = assetId, contentType = "image/jpeg",
            metadata = mapOf(RESOURCE_META_CREATION_DATE to creationDate), data = Unit,
        )

    private fun cycleWithCutoff(
        backend: InMemoryLedgerStore,
        platform: FakePlatform,
        cutoff: String,
    ): UploadCycle = cycle(backend, platform, policy = admitting(cutoff))

    @Test
    fun cutoff_excludes_pre_cutoff_resources_from_upload() = runTest {
        val backend = InMemoryLedgerStore()
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
        val backend = InMemoryLedgerStore()
        val platform = FakePlatform(
            discovered = listOf(datedResource("old-primary.jpg", "2026-07-01T00:00:00Z", "old")),
            fullEnumeration = false,
        )

        cycleWithCutoff(backend, platform, "2026-07-06T00:00:00Z").run()

        assertTrue(platform.created.isEmpty(), "a pre-cutoff changed asset is excluded on the incremental walk")
    }

    @Test
    fun the_cutoff_is_passed_to_the_platform_as_a_walk_bound() = runTest {
        // The cutoff scopes the platform's own fetch, so a full enumeration does not walk the whole
        // library (capability `photo-selection-policy`). The cycle's filter below stays authoritative.
        val backend = InMemoryLedgerStore()
        val platform = FakePlatform(discovered = emptyList())

        cycleWithCutoff(backend, platform, "2026-07-06T14:32:11Z").run()

        // The POLICY reaches the platform, not a bound flattened out of it — which is what lets the
        // fetch predicate be derived by translating the rules (capability `photo-selection-policy`).
        assertEquals(
            captureCutoff("2026-07-06T14:32:11Z"),
            platform.discoverPolicyArg?.walkFloor,
            "the platform receives the membership's policy, carrying its capture floor",
        )
    }

    @Test
    fun a_pre_cutoff_resource_is_dropped_even_when_the_platform_over_returns_it() = runTest {
        // The platform's date predicate is deliberately widened, so it MAY hand back assets before the
        // cutoff. The cycle's filter is what makes that safe — the admitted set must be unchanged.
        val backend = InMemoryLedgerStore()
        val platform = FakePlatform(
            discovered = listOf(datedResource("old-primary.jpg", "2000-01-01T00:00:00Z", "old")),
        )

        cycleWithCutoff(backend, platform, "2026-07-06T14:32:11Z").run()

        assertTrue(platform.created.isEmpty(), "an over-returned pre-cutoff resource must still be dropped")
    }

    @Test
    fun an_undated_asset_is_excluded_under_a_cutoff() = runTest {
        val backend = InMemoryLedgerStore()
        // No creationDate metadata → empty string, which sorts before any non-empty cutoff.
        val platform = FakePlatform(discovered = listOf(resource("undated-primary.jpg", "undated")))

        cycleWithCutoff(backend, platform, "2026-07-06T00:00:00Z").run()

        assertTrue(platform.created.isEmpty(), "an asset with no creationDate is out of scope under a cutoff")
    }

    // ── Origin exclusions (capability `photo-selection-policy`) ────────────────────────────────────────
    // The cutoff bounds WHEN a photo was taken; these bound WHAT it is. Note the existing tests above are
    // unaffected: `resource()` carries no origin facts, and absent facts ADMIT (admit-on-doubt).

    /** A resource carrying the origin facts the enumerator would have stashed on it. */
    private fun originResource(
        name: String,
        assetId: String = name,
        isScreenshot: Boolean = false,
        isScreenRecording: Boolean = false,
        isVideo: Boolean = false,
        width: Long = 4032,
        height: Long = 3024,
        adjusted: Boolean = false,
        mime: String = "image/heic",
    ) = Resource(
        filename = name, assetId = assetId, contentType = "public.heic",
        metadata = mapOf(
            RESOURCE_META_CREATION_DATE to IN_SCOPE_DATE,
            RESOURCE_META_MIME to mime,
            RESOURCE_META_IS_SCREENSHOT to isScreenshot.toString(),
            RESOURCE_META_IS_SCREEN_RECORDING to isScreenRecording.toString(),
            RESOURCE_META_IS_VIDEO to isVideo.toString(),
            RESOURCE_META_IS_EDITED to adjusted.toString(),
            RESOURCE_META_PIXEL_AREA to (width * height).toString(),
        ),
        data = Unit,
    )

    /** A cycle with an album-exclusion port, and a manifest hook recording what the manifest actually saw. */
    /**
     * Pre-record every discovered resource as COMPLETED, so the ledger-projected manifest has rows to
     * list and the only thing left deciding its contents is the admission.
     */
    private suspend fun completing(backend: InMemoryLedgerStore, platform: FakePlatform) {
        val writer = LedgerWriter(backend)
        platform.discovered.forEach { writer.recordCompleted(it, attempt = 0, eventId = TEST_EVENT) }
    }

    private fun originCycle(
        backend: InMemoryLedgerStore,
        platform: FakePlatform,
        albumExcluded: Set<String> = emptySet(),
        manifestSaw: MutableList<String> = mutableListOf(),
    ): UploadCycle = cycle(
        backend, platform,
        // The manifest is now a PROJECTION of the ledger's COMPLETED rows (capability
        // `device-manifest`), so what it "sees" is read from the ledger at hook time rather than
        // handed over. These fixtures record COMPLETED rows for the admitted set first, so the
        // projection has something to list — see `completing`.
        onDiscovery = { _, _ -> manifestSaw += backend.completedManifestRows().map { it.key } },
        albumExcludedAssetIds = { albumExcluded },
    )

    @Test
    fun a_screenshot_never_reaches_the_engine() = runTest {
        val backend = InMemoryLedgerStore()
        val platform = FakePlatform(
            discovered = listOf(
                originResource("shot-primary.png", "shot", isScreenshot = true),
                originResource("cam-primary.heic", "cam"),
            ),
        )

        originCycle(backend, platform).run()

        assertEquals(listOf("cam-primary.heic"), platform.created.map { it.filename })
        assertNull(backend.get("shot-primary.png"), "an excluded asset creates no ledger row")
    }

    @Test
    fun a_screen_recording_and_a_gif_are_excluded() = runTest {
        // The GIF is excluded by the RESOLUTION FLOOR now, not by a rule reading its MIME — a messenger
        // GIF is 480x270 = 0.13 MP. The cycle's admitted set is unchanged for the ordinary case.
        val platform = FakePlatform(
            discovered = listOf(
                originResource("rec.mov", "rec", isScreenRecording = true, isVideo = true),
                originResource("meme.gif", "meme", width = 480, height = 270, mime = "image/gif"),
                originResource("cam.heic", "cam"),
            ),
        )

        originCycle(InMemoryLedgerStore(), platform).run()

        assertEquals(listOf("cam.heic"), platform.created.map { it.filename })
    }

    @Test
    fun a_compressed_received_image_is_excluded_but_a_1080p_video_is_not() = runTest {
        // The video floor is separate and lower ON PURPOSE: 1080p is 2.07 MP, BELOW the 3 MP image floor.
        // A single shared floor would silently drop every 1080p recording taken at the event.
        val platform = FakePlatform(
            discovered = listOf(
                originResource("wa.jpg", "wa", width = 1600, height = 1200), // 1.9 MP → excluded
                originResource("clip.mov", "clip", isVideo = true, width = 1920, height = 1080),
            ),
        )

        originCycle(InMemoryLedgerStore(), platform).run()

        assertEquals(listOf("clip.mov"), platform.created.map { it.filename }, "the 1080p recording survives")
    }

    @Test
    fun an_edited_photo_below_the_floor_is_admitted() = runTest {
        // A crop renders small. Without the hasAdjustments guard this real capture would silently vanish.
        val platform = FakePlatform(
            discovered = listOf(originResource("crop.heic", "crop", width = 1000, height = 800, adjusted = true)),
        )

        originCycle(InMemoryLedgerStore(), platform).run()

        assertEquals(listOf("crop.heic"), platform.created.map { it.filename })
    }

    @Test
    fun a_denylisted_album_member_is_excluded_via_the_injected_port() = runTest {
        val platform = FakePlatform(
            discovered = listOf(originResource("wa.heic", "wa"), originResource("cam.heic", "cam")),
        )

        originCycle(InMemoryLedgerStore(), platform, albumExcluded = setOf("wa")).run()

        assertEquals(listOf("cam.heic"), platform.created.map { it.filename })
    }

    @Test
    fun the_origin_filter_covers_the_incremental_walk() = runTest {
        val platform = FakePlatform(
            discovered = listOf(originResource("shot.png", "shot", isScreenshot = true)),
            fullEnumeration = false,
        )

        originCycle(InMemoryLedgerStore(), platform).run()

        assertTrue(platform.created.isEmpty(), "excluded on the incremental walk exactly as on a full one")
    }

    @Test
    fun an_excluded_asset_never_reaches_the_device_manifest() = runTest {
        // THE leak this change closes. The manifest hook used to be handed the RAW discovery, so an
        // excluded asset landed in the device-global accumulator, projected into device.json, entered the
        // event union — and every other member tried to download bytes that were never uploaded.
        val manifestSaw = mutableListOf<String>()
        val platform = FakePlatform(
            discovered = listOf(
                originResource("shot.png", "shot", isScreenshot = true),
                originResource("wa.heic", "wa"),
                originResource("cam.heic", "cam"),
            ),
            fullEnumeration = true,
        )

        // Every discovered resource is already COMPLETED, so the ONLY thing deciding what the
        // projection lists is the admission — which is exactly what this test is about.
        val backend = InMemoryLedgerStore()
        completing(backend, platform)
        originCycle(backend, platform, albumExcluded = setOf("wa"), manifestSaw = manifestSaw).run()

        assertEquals(listOf("cam.heic"), manifestSaw, "the manifest sees only the admitted set")
    }

    @Test
    fun the_manifest_hook_sees_the_admitted_set_and_nothing_else() = runTest {
        // BOTH exclusions now land on the SAME side of the manifest hook — it is handed the admitted set
        // itself, not the inputs to compute one from. This REVERSES the earlier split, deliberately: the
        // capture-date bounds used to be withheld from the accumulator as forward-prep for multi-event
        // membership ("another event's cutoff may admit it"), leaving the per-event projection to re-apply
        // them. That is exactly how the ceiling went missing — the projection was given a bare cutoff and
        // silently applied only the floor. Multi-event membership is a named non-goal, so the forward-prep
        // is removed rather than deepened, and the hook receives one already-decided set.
        val manifestSaw = mutableListOf<String>()
        val platform = FakePlatform(
            discovered = listOf(
                datedResource("old.heic", "2020-01-01T00:00:00Z", "old"), // pre-cutoff, no origin facts
                originResource("shot.png", "shot", isScreenshot = true),
            ),
            fullEnumeration = true,
        )

        val backend = InMemoryLedgerStore()
        completing(backend, platform)
        originCycle(backend, platform, manifestSaw = manifestSaw).run()

        assertTrue(manifestSaw.isEmpty(), "neither the pre-cutoff asset nor the screenshot is admitted")
        assertTrue(platform.created.isEmpty(), "…and neither is uploaded")
    }

    @Test
    fun an_excluded_asset_is_pruned_from_the_ledger_on_a_full_enumeration() = runTest {
        // The free retroactive cleanup: a previously-uploaded screenshot loses its ledger row the next time
        // a full enumeration runs (token expiry, re-join, reinstall), so it drops out of device.json and
        // leaves the event union. Its bytes stay in storage — no object is ever deleted.
        val backend = InMemoryLedgerStore()
        backend.put(
            LedgerEntry(key = "shot.png", assetId = "shot", state = LedgerState.COMPLETED, attempt = 0, eventId = TEST_EVENT),
        )
        val platform = FakePlatform(
            discovered = listOf(
                originResource("shot.png", "shot", isScreenshot = true),
                originResource("cam.heic", "cam"),
            ),
            fullEnumeration = true,
        )

        originCycle(backend, platform).run()

        assertNull(backend.get("shot.png"), "retainAssets prunes the now-excluded asset's row")
        assertTrue(backend.get("cam.heic") != null, "the admitted asset keeps its row")
    }
}
