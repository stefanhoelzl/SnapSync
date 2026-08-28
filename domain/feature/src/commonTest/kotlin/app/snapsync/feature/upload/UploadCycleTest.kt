package app.snapsync.feature.upload

import app.snapsync.ports.CreateResult
import app.snapsync.ports.CycleResult
import app.snapsync.ports.Discovery
import app.snapsync.ports.DiscoveryStore
import app.snapsync.ports.PlatformUploadJob
import app.snapsync.ports.LedgerStore
import app.snapsync.ports.BackgroundTransfer

import app.snapsync.model.candidatesFromResources
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.feature.upload.LedgerWriter
import app.snapsync.model.Resource
import app.snapsync.model.projectDeviceManifest
import app.snapsync.model.ResourceRole
import app.snapsync.feature.upload.SyncEngine
import app.snapsync.model.UploadError
import app.snapsync.model.UploadRequest
import app.snapsync.model.UploadRequestProvider
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.selectionRulesFor
import app.snapsync.model.SelectionRule
import app.snapsync.model.captureCutoff
import app.snapsync.model.RESOURCE_META_CREATION_DATE
import app.snapsync.model.RESOURCE_META_IS_EDITED
import app.snapsync.model.RESOURCE_META_IS_SCREENSHOT
import app.snapsync.model.RESOURCE_META_IS_SCREEN_RECORDING
import app.snapsync.model.RESOURCE_META_IS_VIDEO
import app.snapsync.model.RESOURCE_META_PIXEL_AREA
import app.snapsync.model.RESOURCE_META_MIME
import app.snapsync.model.normalizeAssetId
import co.touchlab.kermit.Logger
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import co.touchlab.kermit.loggerConfigInit
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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

        /**
         * An admitting policy carrying the two port-read exclusions. They used to be injected into the
         * cycle and applied by it; the one derivation now folds them into the rule list, so a fixture
         * states them here (capability `photo-selection-policy`).
         */
        suspend fun admittingWith(
            cutoff: String = TEST_CUTOFF,
            echo: Set<String> = emptySet(),
            albumExcluded: Set<String> = emptySet(),
        ): SelectionPolicy = SelectionPolicy(
            selectionRulesFor(
                includesUpload = true,
                cutoff = captureCutoff(cutoff),
                ceiling = null,
                suppressedAssetIds = { echo },
                albumExcludedAssetIds = { albumExcluded },
            ),
        )

        /** An admitting policy over [cutoff], unbounded above — the shape every cycle fixture wants. */
        suspend fun admitting(cutoff: String): SelectionPolicy =
            SelectionPolicy(selectionRulesFor(includesUpload = true, cutoff = captureCutoff(cutoff), ceiling = null, suppressedAssetIds = { emptySet() }, albumExcludedAssetIds = { emptySet() }))
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
        /** `var` so a test can model a library that stops reporting changes while work remains. */
        var discovered: List<Resource> = emptyList(),
        private val retryJobs: List<PlatformUploadJob> = emptyList(),
        // Keys the "platform" finished successfully. Recorded `UPLOADED` into [ledger] when the cycle
        // drains, exactly as both device adapters do — a success no longer crosses this seam at all.
        private val succeeded: List<String> = emptyList(),
        // Retry-spent failures: recorded `FAILED`, then handed back for the cycle to re-create.
        private val ackJobs: List<PlatformUploadJob> = emptyList(),
        // The ledger this platform records into. Optional only so tests that never drain need not state it.
        private val ledger: LedgerStore? = null,
        private val nextToken: ByteArray = byteArrayOf(9),
        private val limitAfter: Int = Int.MAX_VALUE,
        private val failCreate: Boolean = false,
        private val removedAssetIds: List<String> = emptyList(),
        private val fullEnumeration: Boolean = false,
    ) : BackgroundTransfer {
        val created = mutableListOf<Resource>()
        val retried = mutableListOf<PlatformUploadJob>()
        /** Whether the cycle settled with the platform — the obligation a declined cycle still owes. */
        var drained = false
        var discoverTokenArg: ByteArray? = null
        var discoverPolicyArg: SelectionPolicy? = null
        /** Keys the cycle asked to resolve — how a test asserts it enqueued from the ledger, not a walk. */
        val resolvedKeys = mutableSetOf<String>()

        /**
         * Everything this fixture's "library" has ever held — what [resourcesFor] answers from.
         *
         * Deliberately not [discovered]: the change feed reports what CHANGED, and the whole point of
         * resolving by key is that it works for an asset the feed has stopped mentioning.
         */
        private val library = discovered
        private var creates = 0

        override suspend fun fetchRetryJobs() = retryJobs
        override suspend fun drainTerminals(): List<PlatformUploadJob> {
            drained = true
            succeeded.forEach { ledger?.markTerminal(it, LedgerState.UPLOADED) }
            ackJobs.forEach { ledger?.markTerminal(it.key, LedgerState.FAILED) }
            return ackJobs
        }
        override suspend fun retryJob(job: PlatformUploadJob, request: UploadRequest) { retried += job }

        /** Operator lever: the platform's in-flight slots freed, so it will accept jobs again. */
        fun freeSlots() { creates = 0 }

        /**
         * Resolves from the same held set [discovered] answers with — the fixture's stand-in for a
         * library. Deliberately **partial**: a key naming a resource this fixture does not hold resolves
         * to nothing, which is the port's contract and the case a test needs to be able to construct
         * (an asset that left the library between the row being written and the cycle enqueueing it).
         */
        override suspend fun resourcesFor(keys: Set<String>): List<Resource> {
            resolvedKeys += keys
            return library.filter { it.filename in keys }
        }

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

    /** A retry-spent failure — the only kind of job that still crosses the seam. */
    private fun platformJob(key: String, error: UploadError? = null) =
        PlatformUploadJob(key = key, contentType = "image/jpeg", error = error, data = Unit)

    /** Seed a row as `REQUESTED`, which is what a terminal outcome's guarded write requires. */
    private suspend fun InMemoryLedgerStore.inFlight(key: String, assetId: String = key.substringBefore('-')) =
        put(LedgerEntry(key, assetId, LedgerState.REQUESTED, attempt = 0, eventId = TEST_EVENT))

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
    private suspend fun cycle(
        backend: InMemoryLedgerStore,
        platform: FakePlatform,
        store: DiscoveryStore = FakeStore(),
        // Nullable rather than defaulted: a suspend call is not allowed in a default value.
        policy: SelectionPolicy? = null,
        saveToAlbum: Boolean = true,
        readGate: (() -> CycleGate)? = null,
        reconcile: suspend (String?) -> Boolean = { true }, // a settled join unless a test says otherwise
        onDiscovery: suspend (String, SelectionPolicy) -> Boolean = { _, _ -> true },
        onBatchUploaded: suspend (String) -> Unit = {},
        placeInAlbum: suspend (String, Set<String>) -> Unit = { _, _ -> },
        log: Logger = Logger.withTag("UploadCycleTest"),
    ): UploadCycle {
        val effectivePolicy = policy ?: admitting(TEST_CUTOFF)
        val ledger = LedgerWriter(backend)
        return UploadCycle(
            readGate = readGate ?: {
                CycleGate.Run(
                    UploadConfig(host = TEST_HOST, eventId = TEST_EVENT),
                    JoinedMembership(eventId = TEST_EVENT, policy = { effectivePolicy }, saveToAlbum = saveToAlbum),
                )
            },
            engineFor = { config -> SyncEngine(StubUploadRequestProvider(), ledger, config.eventId) },
            ledger = ledger,
            platform = platform,
            store = store,
            reconcile = reconcile,
            onDiscovery = onDiscovery,
            onBatchUploaded = onBatchUploaded,
            placeInAlbum = placeInAlbum,
            log = log,
        )
    }

    private suspend fun cycleOver(
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
            onDiscovery = { _, _ -> touched += "discovery"; true },
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
                        JoinedMembership(TEST_EVENT, { admitting(TEST_CUTOFF) }, saveToAlbum = false),
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
    private suspend fun decliningCycle(
        backend: InMemoryLedgerStore,
        platform: FakePlatform,
        store: DiscoveryStore,
        order: MutableList<String> = mutableListOf(),
    ): UploadCycle = cycle(
        backend, platform, store,
        policy = SelectionPolicy(listOf(SelectionRule.DenyAll)),
        onDiscovery = { _, _ -> order += "discovery"; true },
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
            succeeded = listOf("c-primary.heic"),
        )
        val order = mutableListOf<String>()

        val result = decliningCycle(InMemoryLedgerStore(), platform, store, order).run()

        assertEquals(CycleResult.SKIPPED, result, "declined, and distinguishable from a drained cycle")
        assertTrue(platform.created.isEmpty(), "no upload job for a membership that contributes nothing")
        // The manifest IS written, and is empty — the honest statement of "I share nothing" (capability
        // `device-manifest`). Withholding it would leave a stale manifest advertising photos the member
        // has stopped sharing. The projection is empty because the policy admits nothing, so the manifest
        // still cannot offer bytes that were never uploaded.
        assertTrue("discovery" in order, "an empty device manifest is published")
        assertTrue("notify" !in order, "no event notify is fired — there is no completion to announce")
    }

    /**
     * The gate precedes EVERYTHING — including the reconcile. A non-contributor must not walk its library to
     * discover it contributes nothing: the walk costs one PhotoKit round-trip per asset (~110 ms on an SE2),
     * so a per-asset answer would spend minutes arriving at the empty set.
     */
    @Test
    fun the_gate_precedes_the_walk_but_not_the_reconcile_or_the_manifest() = runTest {
        val store = FakeStore()
        val platform = FakePlatform(discovered = listOf(resource("a")))
        val order = mutableListOf<String>()

        decliningCycle(InMemoryLedgerStore(), platform, store, order).run()

        // What the gate withholds is NEW WORK — the walk and job creation. It does not withhold facts
        // about what is already uploaded (the reconcile, capability `sync-ledger`) nor the statement of
        // what this membership shares (the manifest, capability `device-manifest`). The terminal-job
        // settlement is deliberately not in `order`: acknowledging a job the OS already presented is not
        // new work, and a declined cycle owes it (capability `upload-lifecycle`).
        assertEquals(listOf("reconcile", "discovery"), order, "reconcile then manifest; never a notify")
        assertNull(platform.discoverPolicyArg, "the library is still never enumerated — that is the cost")
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

    /**
     * A download-only skip is the designed outcome of a setting the member chose — not a fault.
     *
     * This is the contract the inverted gate broke: because `None` carried no capture floor, every
     * download-only cycle took a "malformed policy" branch and logged at `Error`, and `crash-reporting`
     * turns `Error` into an event. One wrong severity became four Bugsink issues and an event on every
     * foreground and every completed upload task, for as long as the membership stayed download-only.
     * Both branches returned `SKIPPED` having touched nothing, which is exactly why the two tests above
     * passed while running through the wrong one.
     */
    @Test
    fun a_declined_cycle_reports_no_fault() = runTest {
        val lines = mutableListOf<Pair<Severity, String>>()
        val recorder = object : LogWriter() {
            override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
                lines += severity to message
            }
        }
        val platform = FakePlatform(discovered = listOf(resource("a")))

        val result = cycle(
            InMemoryLedgerStore(), platform, FakeStore(),
            policy = SelectionPolicy(listOf(SelectionRule.DenyAll)),
            log = Logger(loggerConfigInit(recorder), "UploadCycleTest"),
        ).run()

        assertEquals(CycleResult.SKIPPED, result)
        assertTrue(
            lines.none { it.first >= Severity.Error },
            "a routine skip must never become a crash report; logged: $lines",
        )
        assertTrue(
            lines.any { it.first == Severity.Info && "contributes nothing" in it.second },
            "and it must still SAY so — absence is never silent; logged: $lines",
        )
    }

    /**
     * The gate bounds new work, not settlement (capability `upload-lifecycle`).
     *
     * Acknowledging a job the OS already presented creates nothing, writes no manifest, enumerates
     * nothing and issues no network call — so a declined cycle still owes it. Measured on iOS 26.6: a
     * cycle that returned before this pass, with the extension still registered (which a reconfigure to
     * download-only deliberately leaves it), made the system report error 50008, DISCARD the outstanding
     * jobs, and defer the extension ~300 s against an escalating attempt count.
     */
    @Test
    fun a_declined_cycle_settles_with_the_platform_but_promotes_nothing() = runTest {
        val backend = InMemoryLedgerStore()
        val store = FakeStore()
        val presented = "c-primary.heic"
        backend.inFlight(presented, assetId = "c")
        val platform =
            FakePlatform(discovered = listOf(resource("a")), succeeded = listOf(presented), ledger = backend)
        val order = mutableListOf<String>()

        val result = decliningCycle(backend, platform, store, order).run()

        assertEquals(CycleResult.SKIPPED, result, "still declined — settling is not contributing")
        assertTrue(
            platform.drained,
            "a declined cycle still settles with the platform — an un-acknowledged presented job errors " +
                "the system 50008 and the OS discards the outstanding jobs",
        )
        assertEquals(
            LedgerState.UPLOADED, backend.get(presented)?.state,
            "recorded where the OS reported it — but NOT promoted: promotion places in the album and " +
                "gates the notify, and a non-contributor writes no manifest, so there is nothing to wake " +
                "anyone for. The row rests UPLOADED until a re-join reconciles it from storage.",
        )
        // And it took nothing the gate withholds: the walk and job creation.
        assertTrue(platform.created.isEmpty(), "no upload job is created")
        assertEquals(listOf("reconcile", "discovery"), order, "reconcile and manifest run; notify does not")
        assertNull(platform.discoverPolicyArg, "the library is never enumerated")
        assertNull(store.saved, "the discovery cursor does not advance")
        assertTrue(!store.cleared, "nor is it cleared")
    }

    // ---- Phase 0: the re-join reconciliation gate (capability `event-rejoin-reconciliation`) ----------
    // The gate lives in the CYCLE, not in each tier's composition root, because the cycle is the only
    // thing that runs on every route to a divergent ledger. Root-wired reconciliation is exactly how the
    // app-driven tier shipped with none, re-uploading the whole post-cutoff library after a reinstall.

    /** Builds a cycle whose reconcile gate is [gate], recording call order into [order]. */
    private suspend fun gatedCycle(
        backend: InMemoryLedgerStore,
        platform: FakePlatform,
        store: DiscoveryStore = FakeStore(),
        order: MutableList<String> = mutableListOf(),
        gate: suspend () -> Boolean,
    ): UploadCycle = cycle(
        backend, platform, store,
        onDiscovery = { _, _ -> order += "discovery"; true },
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
    fun a_deferred_reconcile_creates_no_jobs_but_still_settles() = runTest {
        val store = FakeStore()
        val platform = FakePlatform(
            discovered = listOf(resource("a")),
            succeeded = listOf("b-primary.heic"),
        )
        // A failed/timed-out device listing: the reconciler returns false rather than seeding.
        val cycle = gatedCycle(InMemoryLedgerStore(), platform, store) { false }

        // COMPLETED, never FAILED: a deferral is a clean no-op so the tier's scheduler simply retries.
        assertEquals(CycleResult.COMPLETED, cycle.run())

        assertTrue(platform.created.isEmpty(), "a deferred cycle must create no upload jobs")
        // The obligation is owed to the platform for jobs it has ALREADY presented, and it depends
        // neither on the direction gate — which has honoured that since the 50008 measurement — nor on
        // whether the seed succeeded. This assertion used to say the opposite, 75 lines below one saying
        // an un-acknowledged presented job makes the OS discard the outstanding jobs; no spec ever asked
        // for it, and `event-rejoin-reconciliation`'s "defers without settling" is about the ledger SEED.
        assertTrue(platform.drained, "a deferred seed still settles with the platform")
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
    fun suppressed_downloaded_assets_create_no_job_and_are_not_listed() = runTest {
        // FOREIGN is an asset this device downloaded + imported (in the suppression set). A stale
        // COMPLETED row stands in for a pre-suppression echo: it must never be re-uploaded, and must not
        // be listed to the event. It is no longer PRUNED to achieve that — the echo suppression is an
        // id set supplied per cycle, so the projection re-applies it (capability `device-manifest`), and
        // the row stays where it belongs: a true record that those bytes are on the backend.
        val backend = InMemoryLedgerStore()
        LedgerWriter(backend).recordCompleted(resource("FOREIGN-primary.heic", "FOREIGN"), attempt = 0, eventId = TEST_EVENT)
        val platform = FakePlatform(
            discovered = listOf(resource("FOREIGN-primary.heic", "FOREIGN"), resource("MINE-primary.heic", "MINE")),
            fullEnumeration = true,
        )
        val cycle = cycle(backend, platform, policy = admittingWith(echo = setOf("FOREIGN")))

        cycle.run()

        assertEquals(listOf("MINE-primary.heic"), platform.created.map { it.filename }) // FOREIGN suppressed
        assertEquals(
            LedgerState.COMPLETED, backend.get("FOREIGN-primary.heic")?.state,
            "the stale row survives — it is a true statement about bytes on the backend",
        )
        // MINE is only REQUESTED this cycle, so the projection holds just FOREIGN's row — and the echo
        // suppression, an id set supplied per cycle, is what keeps it unlisted. Pruning used to do this.
        val rows = backend.completedManifestRows()
        assertEquals(listOf("FOREIGN"), rows.map { it.assetId }, "the stale row is present to be filtered")
        val listed = projectDeviceManifest("D", rows, admittingWith(echo = setOf("FOREIGN")))
            .assets.map { it.assetId }
        assertTrue(listed.isEmpty(), "and the echo suppression keeps it out of the manifest")
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
            policy = admittingWith(echo = setOf("ABC_L0_001")),
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
    fun a_succeeded_upload_is_recorded_uploaded_then_promoted_to_completed() = runTest {
        // The two-phase completion. The platform records UPLOADED where the OS told it — that write is
        // what survives process death — and the cycle's promotion pass, having placed and notified,
        // moves the row on to COMPLETED. Nothing about the success crosses the seam.
        val backend = InMemoryLedgerStore()
        LedgerWriter(backend).recordRequested(resource("a", "a"), attempt = 0, eventId = TEST_EVENT)
        val platform = FakePlatform(succeeded = listOf("a"), ledger = backend)

        cycleOver(backend, platform).run()

        assertEquals(LedgerState.COMPLETED, backend.get("a")?.state)
        assertTrue(platform.created.isEmpty())
    }

    @Test
    fun an_uploaded_row_left_by_a_dead_process_is_promoted_without_re_uploading() = runTest {
        // THE REGRESSION. A previous process recorded the upload UPLOADED and died before any cycle
        // ran. Nothing re-delivers that completion — iOS tells a delegate once — so the only thing that
        // can settle this row is the row itself. It must promote, and it must NOT re-upload: this is
        // exactly the shape that had one device send the same two photos three times over two days.
        val backend = InMemoryLedgerStore()
        backend.put(LedgerEntry("a", "a", LedgerState.UPLOADED, attempt = 0, eventId = TEST_EVENT))
        val platform = FakePlatform(discovered = listOf(resource("a", "a")), ledger = backend)

        cycleOver(backend, platform).run()

        assertEquals(LedgerState.COMPLETED, backend.get("a")?.state, "the orphaned row is promoted")
        assertTrue(platform.created.isEmpty(), "and its bytes are never sent again")
    }

    @Test
    fun a_terminal_outcome_for_a_pruned_row_writes_nothing_at_all() = runTest {
        // The row was pruned (a mid-upload deletion, or a full-enumeration retain) before the outcome
        // arrived. The phantom `assetId=""` row this used to guard against is now structurally
        // impossible: the terminal write is a guarded UPDATE, so with no row to match it writes nothing
        // and there is no reconstruct step left to get an assetId wrong.
        val backend = InMemoryLedgerStore()
        val platform = FakePlatform(succeeded = listOf("L-primary.jpg"), ledger = backend)

        cycleOver(backend, platform).run()

        assertNull(backend.get("L-primary.jpg"), "a guarded write cannot resurrect a pruned row")
        assertTrue(platform.drained, "and the platform is still settled with")
    }

    @Test
    fun a_blank_key_terminal_outcome_is_settled_but_records_no_row() = runTest {
        // An unrecoverable key (e.g. a malformed destination URL) must never produce a phantom row, and
        // the platform must still be settled with (an un-acknowledged presented job errors the OS 50008).
        val backend = InMemoryLedgerStore()
        val platform = FakePlatform(succeeded = listOf(""), ledger = backend)

        cycleOver(backend, platform).run()

        assertNull(backend.get(""), "no phantom row for an unrecoverable key")
        assertTrue(platform.drained, "settled regardless")
    }

    @Test
    fun first_failure_retries_with_a_fresh_url_and_records_requested() = runTest {
        val backend = InMemoryLedgerStore()
        LedgerWriter(backend).recordRequested(resource("a", "a"), attempt = 0, eventId = TEST_EVENT)
        val job = platformJob("a", UploadError.Network)
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
        val job = platformJob("a", UploadError.Network)
        val platform = FakePlatform(ackJobs = listOf(job))

        val result = cycleOver(backend, platform).run()

        assertEquals(CycleResult.COMPLETED, result)
        assertEquals(listOf("a"), platform.created.map { it.filename }) // re-created
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
        // Still no REQUESTED — write-after-act is intact, and that is what this test guards.
        // But the row is no longer ABSENT: the walk recorded it DISCOVERED before any job was
        // attempted, so a create that failed leaves the resource remembered rather than forgotten.
        // Before, a failed create left nothing at all, and the resource was found again only by a walk
        // that re-derived it — which an incremental walk does not do for an asset that has not changed.
        // Same defect as the never-retried FAILED row, arriving through a different door.
        assertEquals(LedgerState.DISCOVERED, backend.get("a")?.state)
        assertContentEquals(byteArrayOf(9), store.saved) // cursor advances: the walk was recorded
    }

    @Test
    fun already_completed_re_handed_job_is_a_noop_acknowledge() = runTest {
        val backend = InMemoryLedgerStore()
        LedgerWriter(backend).recordCompleted(resource("a", "a"), attempt = 0, eventId = TEST_EVENT)
        val job = platformJob("a", UploadError.Network)
        val platform = FakePlatform(ackJobs = listOf(job), ledger = backend)

        cycleOver(backend, platform).run()

        // The guarded write cannot touch a COMPLETED row, so the re-handed failure changes nothing — the
        // suppression is now structural rather than a state check the cycle has to remember to make.
        assertTrue(platform.created.isEmpty(), "an already-COMPLETED key is not re-created")
        assertEquals(LedgerState.COMPLETED, backend.get("a")?.state)
    }

    @Test
    fun bare_rows_are_backfilled_even_when_creation_stops_early() = runTest {
        val backend = InMemoryLedgerStore()
        // What a re-join seed leaves behind: COMPLETED rows taken from a filename listing, which carries
        // no capture date. A bare row is excluded from every projection fail-closed, so until something
        // fills it this member's photos are missing from the event union.
        backend.put(LedgerEntry("seeded-a", "seeded-a", LedgerState.COMPLETED, 0, TEST_EVENT))
        backend.put(LedgerEntry("seeded-b", "seeded-b", LedgerState.COMPLETED, 0, TEST_EVENT))
        val platform = FakePlatform(
            // New work FIRST, so creation stops before the walk reaches the seeded rows in the old order.
            discovered = listOf(resource("new-1"), resource("new-2"), resource("new-3"),
                                resource("seeded-a"), resource("seeded-b")),
            limitAfter = 1,
        )

        val result = cycleOver(backend, platform, FakeStore()).run()

        assertEquals(CycleResult.PROCESSING, result)
        // BOTH are enriched, including the one the platform's job limit would once have stopped short of.
        // This is a precondition of advancing the cursor, not a nicety: a capture date lives only in the
        // library and only the walk reads it, so a bare row the cursor has moved past stays bare — and
        // invisible — until something forces a full re-enumeration.
        assertEquals(IN_SCOPE_DATE, backend.get("seeded-a")?.creationDate)
        assertEquals(IN_SCOPE_DATE, backend.get("seeded-b")?.creationDate)
    }

    @Test
    fun a_truncated_cycle_resumes_its_remainder_from_the_ledger_without_re_discovering() = runTest {
        val backend = InMemoryLedgerStore()
        val platform = FakePlatform(
            discovered = listOf(resource("a"), resource("b"), resource("c")),
            limitAfter = 2,
        )
        val store = FakeStore()
        val cycle = cycleOver(backend, platform, store)

        assertEquals(CycleResult.PROCESSING, cycle.run())
        assertEquals(listOf("a", "b"), platform.created.map { it.filename })

        // The platform frees its slots, and nothing in the library changes. Under the old design this is
        // the dead spot: an incremental walk returns nothing, so "c" was found again only by a full
        // re-enumeration — which is why the cursor was not allowed to advance in the first place.
        platform.freeSlots()
        platform.discovered = emptyList() // the change feed reports nothing new

        assertEquals(CycleResult.COMPLETED, cycle.run())

        assertEquals(listOf("a", "b", "c"), platform.created.map { it.filename }, "the remainder resumes")
        assertTrue("c" in platform.resolvedKeys, "resolved by key from the ledger, not re-derived by a walk")
    }

    @Test
    fun cap_during_creation_advances_the_cursor_and_leaves_the_remainder_discovered() = runTest {
        val backend = InMemoryLedgerStore()
        val platform = FakePlatform(
            discovered = listOf(resource("a"), resource("b"), resource("c")),
            limitAfter = 2,
        )
        val store = FakeStore()

        val result = cycleOver(backend, platform, store).run()

        assertEquals(CycleResult.PROCESSING, result)
        assertEquals(listOf("a", "b"), platform.created.map { it.filename })
        // THE INVERSION (capability `ios-photokit-upload`). The cursor advances because every fact the
        // walk produced is durable — the un-created remainder holds a DISCOVERED row, so nothing is lost
        // by moving past it. The old rule waited for "every job was created", which on a device with more
        // outstanding work than the platform's job limit is never true, so the cursor stood still and
        // every cycle re-enumerated the whole library.
        assertContentEquals(byteArrayOf(9), store.saved, "the walk was recorded, so the cursor advances")
        assertEquals(LedgerState.DISCOVERED, backend.get("c")?.state, "the remainder is remembered")
        assertEquals(LedgerState.REQUESTED, backend.get("a")?.state, "what got a job is in flight")
    }

    @Test
    fun removed_asset_rows_are_marked_absent_incrementally_by_assetId() = runTest {
        val backend = InMemoryLedgerStore()
        LedgerWriter(backend).recordCompleted(resource("A_1-photo.jpg", "A_1"), attempt = 0, eventId = TEST_EVENT)
        LedgerWriter(backend).recordRequested(resource("A_1-video.mov", "A_1"), attempt = 0, eventId = TEST_EVENT)
        LedgerWriter(backend).recordCompleted(resource("B-photo.jpg", "B"), attempt = 0, eventId = TEST_EVENT)
        val platform = FakePlatform(removedAssetIds = listOf("A_1"))

        cycleOver(backend, platform).run()

        // MARKED, not deleted: the bytes are still on the backend, so the row stays true and keeps
        // suppressing re-upload if the asset comes back (capability `sync-ledger`).
        assertEquals(true, backend.get("A_1-photo.jpg")?.absent, "departed asset's rows are marked")
        assertEquals(true, backend.get("A_1-video.mov")?.absent)
        assertEquals(LedgerState.COMPLETED, backend.get("A_1-photo.jpg")?.state, "and keep their state")
        assertEquals(false, backend.get("B-photo.jpg")?.absent, "other assets untouched")
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
        assertEquals(true, backend.get("gone-photo.jpg")?.absent, "the stuck row is marked, not deleted")
        assertEquals(0, backend.aggregates().pending, "no phantom pending pins the extension awake")
    }

    @Test
    fun a_full_enumeration_no_longer_reconciles_rows_away() = runTest {
        // The retain-live reconcile is GONE (capability `sync-ledger`). It was fed the POLICY-ADMITTED
        // set, so it could not tell "deleted from the library" from "outside the current capture window"
        // — and raising a cutoff therefore discarded the COMPLETED rows that suppress re-upload. Deletion
        // now arrives only via the change feed's precise signal, which names the departed assets.
        val backend = InMemoryLedgerStore()
        LedgerWriter(backend).recordCompleted(resource("old-photo.jpg", "old"), attempt = 0, eventId = TEST_EVENT)
        val platform = FakePlatform(discovered = listOf(resource("a-photo.jpg")), fullEnumeration = true)
        val store = FakeStore()

        val result = cycleOver(backend, platform, store).run()

        assertEquals(CycleResult.COMPLETED, result)
        assertEquals(
            LedgerState.COMPLETED, backend.get("old-photo.jpg")?.state,
            "a row the enumeration did not return is NOT removed — its absence is not evidence",
        )
        assertEquals(false, backend.get("old-photo.jpg")?.absent, "and it is not marked either")
        assertEquals(LedgerState.REQUESTED, backend.get("a-photo.jpg")?.state, "live resource uploaded")
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
        // Same inversion as `cap_during_creation_advances_the_cursor_…`: a full enumeration whose facts
        // were all recorded may advance, and the resource the cap stopped short of rests DISCOVERED.
        assertContentEquals(byteArrayOf(9), store.saved, "a recorded walk advances the cursor")
        assertEquals(LedgerState.DISCOVERED, backend.get("b-photo.jpg")?.state)
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
    fun a_marked_then_rediscovered_asset_is_not_re_uploaded() = runTest {
        val backend = InMemoryLedgerStore()
        // Uploaded, deleted, then recovered from iOS's "Recently Deleted" — which holds 30 days, the same
        // order as an event's whole life, so this is an ordinary sequence rather than an exotic one.
        LedgerWriter(backend).recordCompleted(resource("x-photo.jpg", "x"), attempt = 0, eventId = TEST_EVENT)
        val platform = FakePlatform(
            discovered = listOf(resource("x-photo.jpg")),
            removedAssetIds = listOf("x"),
        )

        cycleOver(backend, platform).run()

        // The COMPLETED row survived the deletion as a MARK, so the re-discovered key is AlreadyUploaded.
        // Under the old prune it was fresh work and the identical bytes were uploaded again.
        assertTrue(platform.created.isEmpty(), "the surviving row suppresses re-upload of identical bytes")
        assertEquals(LedgerState.COMPLETED, backend.get("x-photo.jpg")?.state)
    }

    @Test
    fun cap_during_re_create_still_walks_publishes_and_returns_processing() = runTest {
        val backend = InMemoryLedgerStore()
        LedgerWriter(backend).recordRequested(resource("a", "a"), attempt = 0, eventId = TEST_EVENT)
        val job = platformJob("a", UploadError.Network)
        val platform = FakePlatform(ackJobs = listOf(job), limitAfter = 0)
        val store = FakeStore()

        val result = cycleOver(backend, platform, store).run()

        // Still PROCESSING: the re-created retry never got a job, so work remains and the pump must
        // re-arm. What changed is everything else the cycle used to withhold on the way out.
        assertEquals(CycleResult.PROCESSING, result)
        // It WALKS. This cycle is the one whose remaining backlog most needs accounting for, and once
        // the walk only records what it finds, running it also keeps the cursor moving.
        assertNotNull(platform.discoverPolicyArg, "a settle-pass cap hit still enumerates")
        assertContentEquals(byteArrayOf(9), store.saved, "and its recorded walk advances the cursor")
        // The retry it could not re-create rests FAILED — which the ledger's work read returns next
        // cycle, so nothing depends on a later walk re-deriving it.
        assertEquals(LedgerState.FAILED, backend.get("a")?.state)
    }

    // ── Notify hook (capability `upload-completion-notify`) ──────────────────────────────────────────

    /**
     * Build a cycle recording the order its best-effort hooks fire, so the manifest→notify order is
     * asserted.
     *
     * The manifest hook models `DeviceManifestProducer`'s **skip-if-unchanged**, because that answer is
     * now the notify's whole trigger (capability `upload-completion-notify`). A fixture that always
     * reported "published" would make every notify test pass for the wrong reason — the thing under test
     * is precisely *did the projection change*.
     */
    private suspend fun cycleWithHooks(
        backend: InMemoryLedgerStore,
        platform: FakePlatform,
        order: MutableList<String>,
        store: DiscoveryStore = FakeStore(),
        notifyThrows: Boolean = false,
    ): UploadCycle {
        var lastPublished: List<String>? = null
        return cycle(
            backend, platform, store,
            onDiscovery = { _, _ ->
                order += "manifest"
                val projection = backend.completedManifestRows().map { it.key }.sorted()
                val changed = projection != lastPublished
                if (changed) lastPublished = projection
                changed
            },
            onBatchUploaded = {
                order += "notify"
                if (notifyThrows) error("notify boom")
            },
        )
    }

    @Test
    fun drained_cycle_with_a_completion_notifies_once_after_the_manifest_write() = runTest {
        val backend = InMemoryLedgerStore()
        // A real completion: the row is in flight, and the platform reports it finished. The guarded
        // write only lands on a REQUESTED row, so an in-flight row is what makes this a completion at all.
        backend.inFlight("a-primary.jpg", assetId = "a")
        val platform = FakePlatform(succeeded = listOf("a-primary.jpg"), ledger = backend)
        val order = mutableListOf<String>()

        val result = cycleWithHooks(backend, platform, order).run()

        assertEquals(CycleResult.COMPLETED, result)
        assertEquals(listOf("manifest", "notify"), order) // fires once, AFTER the device-manifest PUT
    }

    @Test
    fun cap_truncated_cycle_publishes_and_notifies() = runTest {
        val backend = InMemoryLedgerStore()
        // A real completion, and then creation hits the platform's job limit → PROCESSING.
        backend.inFlight("done-primary.jpg", assetId = "done")
        val platform = FakePlatform(
            succeeded = listOf("done-primary.jpg"),
            discovered = listOf(resource("a"), resource("b"), resource("c")),
            ledger = backend,
            limitAfter = 2,
        )
        val order = mutableListOf<String>()

        val result = cycleWithHooks(backend, platform, order).run()

        assertEquals(CycleResult.PROCESSING, result)
        // THE HEADLINE INVERSION. This assertion used to read `order.isEmpty()` — "a cap-truncated cycle
        // refreshes no manifest and fires no notify" — and that is the two-hour silence measured in the
        // field: a device with more outstanding work than the platform's job limit takes this branch on
        // every cycle, so its successfully-uploaded photos never entered the event union and no member
        // was ever woken for them. Nothing the manifest needs was missing; only the drain was.
        assertEquals(listOf("manifest", "notify"), order, "a truncated cycle publishes what it settled")
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
        backend.inFlight("a-primary.jpg", assetId = "a")
        val platform = FakePlatform(succeeded = listOf("a-primary.jpg"), ledger = backend)
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
        val platform = FakePlatform(succeeded = listOf("a-primary.jpg"))
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
            ackJobs = listOf(platformJob("a-primary.jpg", UploadError.Network)),
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

    private suspend fun cycleWithCutoff(
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
            SelectionRule.CaptureAfter(captureCutoff("2026-07-06T14:32:11Z")),
            platform.discoverPolicyArg?.rules?.filterIsInstance<SelectionRule.CaptureAfter>()?.single(),
            "the platform receives the membership's policy, carrying its capture floor as a rule",
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

    /**
     * Pre-record every discovered resource as COMPLETED, so the ledger-projected manifest has rows to
     * list and the only thing left deciding its contents is the admission.
     */
    private suspend fun completing(backend: InMemoryLedgerStore, platform: FakePlatform) {
        val writer = LedgerWriter(backend)
        platform.discovered.forEach { writer.recordCompleted(it, attempt = 0, eventId = TEST_EVENT) }
    }

    /**
     * A cycle whose POLICY carries the album exclusion, and a manifest hook recording what the manifest
     * actually saw. The exclusion used to be an injected port on the cycle; it is a rule in the policy now
     * (capability `photo-selection-policy`), which is why this is `suspend` — the one derivation reads it.
     */
    private suspend fun originCycle(
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
        // The REAL projection, not the raw rows. These used to agree only because `retainAssets` pruned
        // every row the policy stopped admitting; with the ledger no longer policy-pruned they differ, and
        // what other members see is the projection (capability `device-manifest`).
        onDiscovery = { _, policy ->
            manifestSaw += projectDeviceManifest("D", backend.completedManifestRows(), policy)
                .assets.map { it.assetId }
            true
        },
        // The album denylist is a rule in the policy now, not a port the cycle reads.
        policy = admittingWith(albumExcluded = albumExcluded),
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
    fun the_projection_re_applies_only_the_rules_a_ledger_row_can_answer() = runTest {
        // The original leak stays closed: the manifest hook is handed the POLICY, not raw discovery, so it
        // can never list bytes that were never uploaded (rows are COMPLETED or they are not there).
        //
        // What this now pins is the boundary of what the projection can re-decide. A ledger row carries an
        // assetId and a capture date — nothing about the asset's ORIGIN — and `AssetFacts` defaults land on
        // the admitted side of every rule. So the projection re-applies the capture-date bounds and the two
        // id-set exclusions (echo, denylisted album — both supplied per cycle), and cannot re-apply the
        // origin rules, which were decided at upload time.
        //
        // Consequence, accepted deliberately (capability `sync-ledger`): a row whose asset an origin rule
        // would NOW reject keeps its listing. Reaching that state needs a row written before that rule
        // existed, and every origin rule predates any event that can still be live (≤30-day lifetime).
        // It also lands on the harmless side of this capability's asymmetry — a stray visible photo, not
        // an invisible failure. Previously `retainAssets` swept such rows, at the cost of also discarding
        // rows for photos merely outside the current capture window.
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

        assertEquals(
            listOf("cam", "shot"), manifestSaw.sorted(),
            "the album denylist IS re-applied (its id set is supplied per cycle); the screenshot rule is " +
                "NOT (the row carries no origin facts)",
        )
    }

    @Test
    fun the_projection_re_applies_the_capture_date_bounds() = runTest {
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

        // The capture-date bound IS re-applied at projection — the row carries its creation date — so the
        // pre-cutoff asset is not listed. The screenshot is, for the reason given in the test above.
        assertEquals(listOf("shot"), manifestSaw, "the pre-cutoff asset is excluded by the date bound")
        assertTrue(platform.created.isEmpty(), "and neither is uploaded")
    }

    @Test
    fun an_origin_excluded_row_is_no_longer_swept_by_a_full_enumeration() = runTest {
        // The retroactive cleanup is GONE, deliberately. A full enumeration used to prune every row outside
        // the ADMITTED set, which swept a previously-uploaded screenshot — but the same sweep discarded
        // rows for photos that were merely outside the current capture window, and those rows are what
        // suppress re-upload. Narrowing therefore became irreversible, and a download-only membership would
        // have lost the event's rows entirely (capability `sync-ledger`).
        //
        // What is lost is only the sweep. Reaching this state needs a row written before the screenshot
        // rule existed, and that rule predates any event that can still be live.
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

        assertEquals(
            LedgerState.COMPLETED, backend.get("shot.png")?.state,
            "the row survives a full enumeration — its absence from the admitted set is not evidence " +
                "that the asset left the library",
        )
        assertTrue(backend.get("cam.heic") != null, "the admitted asset keeps its row")
    }

    // ---- Narrowing must not prune upload-suppression state -------------------------------------------
    // Change `separate-shared-set-from-uploaded-bytes`, tasks 1.2/1.3. Pins the claim that a NARROWING of
    // the membership's scope prunes ledger rows it has no business touching. Expected to FAIL on the code
    // as it stands: `retainAssets` is fed the policy-ADMITTED set, so an asset that is still in the library
    // and still uploaded loses its row merely because the cutoff moved past it.

    @Test
    fun raising_the_cutoff_does_not_prune_an_already_uploaded_row() = runTest {
        val backend = InMemoryLedgerStore()
        // Already contributed under the old, lower cutoff.
        backend.put(
            LedgerEntry(
                key = "old-primary.jpg", assetId = "old", state = LedgerState.COMPLETED,
                attempt = 0, eventId = TEST_EVENT, creationDate = "2026-07-01T00:00:00Z",
                role = ResourceRole.PRIMARY, contentType = "image/jpeg", originalFilename = "IMG_old.JPG",
            ),
        )
        // Both assets are still in the library; the member simply raised their cutoff past the older one.
        val platform = FakePlatform(
            discovered = listOf(
                datedResource("old-primary.jpg", "2026-07-01T00:00:00Z", "old"),
                datedResource("new-primary.jpg", "2026-07-10T00:00:00Z", "new"),
            ),
            fullEnumeration = true,
        )

        cycleWithCutoff(backend, platform, "2026-07-06T00:00:00Z").run()

        assertTrue(
            backend.get("old-primary.jpg") != null,
            "sync-ledger: retention is a fact about the library, not about the policy - the asset is " +
                "still present and still uploaded, so raising the cutoff must not prune its row " +
                "(pruning it makes the narrowing irreversible: re-widening would re-upload).",
        )
    }

    @Test
    fun turning_the_direction_off_does_not_prune_the_events_rows() = runTest {
        val backend = InMemoryLedgerStore()
        backend.put(
            LedgerEntry(
                key = "old-primary.jpg", assetId = "old", state = LedgerState.COMPLETED,
                attempt = 0, eventId = TEST_EVENT, creationDate = IN_SCOPE_DATE,
                role = ResourceRole.PRIMARY, contentType = "image/jpeg", originalFilename = "IMG_old.JPG",
            ),
        )
        val platform = FakePlatform(
            discovered = listOf(datedResource("old-primary.jpg", IN_SCOPE_DATE, "old")),
            fullEnumeration = true,
        )

        cycle(
            backend, platform,
            policy = SelectionPolicy(selectionRulesFor(
                includesUpload = false, cutoff = captureCutoff(TEST_CUTOFF), ceiling = null, suppressedAssetIds = { emptySet() }, albumExcludedAssetIds = { emptySet() })),
        ).run()

        assertTrue(
            backend.get("old-primary.jpg") != null,
            "reconfigure-membership: a drained upload is recorded so that re-enabling the direction " +
                "re-uploads nothing - turning the direction off must not wipe the event's ledger.",
        )
    }

    // ---- The manifest is published only from a settled ledger (capability `device-manifest`) ----------
    // The manifest is a FULL-STATE document, so publishing one built from an incomplete ledger does not
    // under-report — it UN-LISTS. Every resource missing from the projection stops being offered to the
    // other members although its bytes are on the backend. Harmless while a short manifest and an intended
    // one were indistinguishable in consequence; a live hazard now that a narrowing scope change retracts
    // listings deliberately (capability `reconfigure-membership`).

    @Test
    fun a_deferred_reconcile_writes_no_manifest_for_a_contributor() = runTest {
        val order = mutableListOf<String>()
        val platform = FakePlatform(discovered = listOf(resource("a-photo.jpg")), fullEnumeration = true)

        val result = cycle(
            InMemoryLedgerStore(), platform,
            reconcile = { false }, // the device file listing failed or timed out
            onDiscovery = { _, _ -> order += "discovery"; true },
        ).run()

        assertEquals(CycleResult.COMPLETED, result, "a deferral is a no-op, never a failure")
        assertTrue(order.isEmpty(), "the ledger is unseeded, so no manifest is published over the last one")
        assertTrue(platform.created.isEmpty(), "and no upload job is created")
    }

    @Test
    fun a_deferred_reconcile_writes_no_manifest_for_a_non_contributor_either() = runTest {
        // The path the reordering opened: a declined cycle now publishes an empty manifest, so it has to
        // answer the same question. An unseeded ledger must not be published as "I share nothing".
        val order = mutableListOf<String>()
        val platform = FakePlatform(discovered = listOf(resource("a-photo.jpg")))

        val result = cycle(
            InMemoryLedgerStore(), platform,
            policy = SelectionPolicy(listOf(SelectionRule.DenyAll)),
            reconcile = { false },
            onDiscovery = { _, _ -> order += "discovery"; true },
        ).run()

        assertEquals(CycleResult.SKIPPED, result)
        assertTrue(order.isEmpty(), "no manifest — 'could not tell' is not 'shares nothing'")
    }

    @Test
    fun a_settled_reconcile_publishes_the_manifest_for_a_non_contributor() = runTest {
        // The contrast that makes the test above mean something: with the ledger settled, the declined
        // cycle DOES publish, and what it publishes is empty.
        val order = mutableListOf<String>()
        val backend = InMemoryLedgerStore()
        LedgerWriter(backend).recordCompleted(resource("a-photo.jpg", "a"), attempt = 0, eventId = TEST_EVENT)
        var listed: List<String>? = null

        val result = cycle(
            backend, FakePlatform(),
            policy = SelectionPolicy(listOf(SelectionRule.DenyAll)),
            reconcile = { true },
            onDiscovery = { _, policy ->
                order += "discovery"
                listed = projectDeviceManifest("D", backend.completedManifestRows(), policy)
                    .assets.map { it.assetId }
                true
            },
        ).run()

        assertEquals(CycleResult.SKIPPED, result)
        assertEquals(listOf("discovery"), order, "the manifest is published")
        assertEquals(emptyList(), listed, "and it is empty — the member currently shares nothing")
    }

    @Test
    fun narrowing_then_widening_re_lists_without_re_uploading() = runTest {
        // The round trip the whole change is for (capabilities `reconfigure-membership`, `sync-ledger`).
        // A member shares a photo, raises their cutoff past it, then lowers it back. The listing must go
        // and come back, and the bytes must not move twice.
        val backend = InMemoryLedgerStore()
        val old = datedResource("old-primary.jpg", "2026-07-01T00:00:00Z", "old")

        // Shared under the original floor.
        cycleWithCutoff(backend, FakePlatform(discovered = listOf(old), fullEnumeration = true), "2026-06-01T00:00:00Z").run()
        LedgerWriter(backend).recordCompleted(old, attempt = 0, eventId = TEST_EVENT)
        assertEquals(
            listOf("old"),
            projectDeviceManifest("D", backend.completedManifestRows(), admittingWith(cutoff = "2026-06-01T00:00:00Z"))
                .assets.map { it.assetId },
            "precondition: shared and listed",
        )

        // NARROWED past it. A real narrowed fetch no longer returns the asset, so the platform hands back
        // nothing — which is exactly the case the deleted retain-live reconcile used to read as a deletion.
        val narrowedPlatform = FakePlatform(discovered = emptyList(), fullEnumeration = true)
        cycleWithCutoff(backend, narrowedPlatform, "2026-07-06T00:00:00Z").run()

        assertEquals(
            LedgerState.COMPLETED, backend.get("old-primary.jpg")?.state,
            "the ledger records bytes on the backend — a scope change is not a fact about that",
        )
        assertTrue(
            projectDeviceManifest("D", backend.completedManifestRows(), admittingWith(cutoff = "2026-07-06T00:00:00Z"))
                .assets.isEmpty(),
            "but it stops being listed to the event",
        )

        // WIDENED back. The asset is in scope again and the fetch returns it.
        val widenedPlatform = FakePlatform(discovered = listOf(old), fullEnumeration = true)
        cycleWithCutoff(backend, widenedPlatform, "2026-06-01T00:00:00Z").run()

        assertTrue(
            widenedPlatform.created.isEmpty(),
            "NO re-upload: the surviving COMPLETED row is what suppresses it. Under the old prune this " +
                "row was gone and every narrowed-out photo uploaded again.",
        )
        assertEquals(
            listOf("old"),
            projectDeviceManifest("D", backend.completedManifestRows(), admittingWith(cutoff = "2026-06-01T00:00:00Z"))
                .assets.map { it.assetId },
            "and it is listed again",
        )
    }
}
