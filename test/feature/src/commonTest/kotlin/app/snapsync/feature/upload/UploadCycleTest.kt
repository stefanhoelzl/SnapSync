@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.feature.upload

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.event.EventId
import app.snapsync.domain.sharing.Direction
import app.snapsync.domain.sharing.SelectionPolicy
import app.snapsync.domain.upload.LedgerState
import app.snapsync.feature.support.CapturingLogWriter
import app.snapsync.feature.support.LEDGER_EVENT
import app.snapsync.feature.support.OwnLibrary
import app.snapsync.feature.support.TestLedger
import app.snapsync.feature.support.captureRange
import app.snapsync.feature.support.keyOf
import app.snapsync.feature.support.photo
import app.snapsync.feature.support.resourceOf
import app.snapsync.feature.support.resourcesOf
import app.snapsync.model.CycleResult
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LibraryResource
import app.snapsync.model.PlatformUploadJob
import app.snapsync.model.Resource
import app.snapsync.model.TerminalOutcome
import app.snapsync.model.UploadCreateOutcome
import app.snapsync.model.UploadFailure
import app.snapsync.model.UploadRequest
import app.snapsync.model.UploadRequestProvider
import app.snapsync.model.projectDeviceManifest
import app.snapsync.model.toLedgerRow
import app.snapsync.ports.GalleryAssetResources
import app.snapsync.ports.LibraryGrant
import app.snapsync.services.gallery.KeyedResources
import app.snapsync.services.ledger.LedgerService
import app.snapsync.services.upload.BackgroundTransfer
import app.snapsync.testids.testAssetId
import app.snapsync.testids.testDeviceId
import app.snapsync.testids.testEventId
import co.touchlab.kermit.Logger
import co.touchlab.kermit.Severity
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The upload cycle over the member's REAL own photos: `OwnPhotos` over the in-memory photo library, so what the cycle
 * reads, admits and resolves is decided where production decides it, and observed at the gallery port. Only the
 * transport is a fake ([FakePlatform]): it records what the cycle asked of it and serves canned returned jobs.
 */
class UploadCycleTest {

    private companion object {
        /**
         * A permissive test cutoff: the membership shares `[TEST_CUTOFF, +30 days]`, and every photo is captured at
         * [IN_SCOPE_DATE] inside it unless a test states otherwise. Every membership carries a range (capability
         * `photo-sharing`), so a cycle cannot be built without one.
         */
        const val TEST_CUTOFF = "2026-05-15T00:00:00Z"
        const val IN_SCOPE_DATE = "2026-06-01T10:00:00Z"
        const val TEST_HOST = "https://edge.example"
        const val TEST_EVENT = "event-1"

        /** The manifest version the fixture gate reads — distinctive, so a test can see it reach the publish. */
        const val TEST_MANIFEST_VERSION = 4242L

        /**
         * The policy a membership over [cutoff] with echo set [echo] is judged by — derived where production derives
         * it, for a projection a test reads itself.
         */
        suspend fun policyFor(cutoff: String = TEST_CUTOFF, echo: Set<AssetId> = emptySet()): SelectionPolicy =
            OwnLibrary().contribution(captureRange(cutoff), echo).policy
    }

    /** A no-network provider returning a throwaway destination — the cycle never inspects the URL. */
    private class StubUploadRequestProvider : UploadRequestProvider {
        override val event = testEventId("E")

        override suspend fun provide(resource: Resource): UploadRequest = UploadRequest(
            url = "https://stub.invalid/${resource.filename}",
            headers = emptyMap(),
            resource = resource,
            event = event,
        )

        override suspend fun provideForRetry(resource: Resource): UploadRequest = provide(resource)
    }

    /**
     * Records what the cycle asked the transport to do and serves canned returned jobs; holds the member's [library]
     * with [discovered] in it. Under a partial [grant], [selection] is the member's read selection (`null`: unread).
     */
    private class FakePlatform(
        discovered: List<GalleryAssetResources> = emptyList(),
        private val retryJobs: List<PlatformUploadJob> = emptyList(),
        // Keys the "platform" finished successfully. Recorded `COMPLETED` into [ledger] when the cycle
        // drains, exactly as both device adapters do — a success no longer crosses this seam at all.
        private val succeeded: List<String> = emptyList(),
        // Retry-spent failures: returned to `DISCOVERED`, then handed back for the cycle to re-create.
        private val ackJobs: List<PlatformUploadJob> = emptyList(),
        // The ledger this platform records into. Optional only so tests that never drain need not state it.
        private val ledger: LedgerService? = null,
        private val limitAfter: Int = Int.MAX_VALUE,
        private val failCreate: Boolean = false,
        grant: LibraryGrant = LibraryGrant.GRANTED,
        val selection: List<GalleryAssetResources>? = null,
    ) : BackgroundTransfer {
        val library = OwnLibrary(grant).also { lib ->
            lib.add(*discovered.toTypedArray())
            selection?.filter { it !in discovered }?.forEach { lib.add(it) }
        }
        val created = mutableListOf<Resource>()
        val retried = mutableListOf<PlatformUploadJob>()

        /** Every creation the cycle asked for, refused ones included. */
        var createAttempts = 0

        /** Whether the cycle settled with the platform — the obligation a declined cycle still owes. */
        var drained = false

        /** Every walk of the library the discovery made, by the filter it asked for. */
        val walks get() = library.gallery.walks

        /** Keys the cycle asked to resolve — how a test asserts it enqueued from the ledger, not a walk. */
        val resolvedKeys get() = library.keys.keys

        /** How many resolve round-trips the cycle made, counted with repeats — what reusing the walk saves. */
        val resolveCalls get() = library.keys.calls

        /** Assets whose resources the discovery read — the per-asset round-trip a skip saves. */
        val readAssets get() = library.gallery.read

        private var creates = 0

        override suspend fun fetchRetryJobs() = retryJobs
        override suspend fun drainTerminals(): List<PlatformUploadJob> {
            drained = true
            succeeded.forEach { ledger?.markTerminal(it, TerminalOutcome.COMPLETED) }
            ackJobs.forEach { ledger?.markTerminal(it.key, TerminalOutcome.FAILED) }
            return ackJobs
        }
        override suspend fun retryJob(job: PlatformUploadJob, request: UploadRequest) { retried += job }

        /** Operator lever: the platform's in-flight slots freed, so it will accept jobs again. */
        fun freeSlots() { creates = 0 }

        override suspend fun createJob(request: UploadRequest, resource: Resource): UploadCreateOutcome {
            createAttempts++
            if (failCreate) return UploadCreateOutcome.FAILED
            if (creates >= limitAfter) return UploadCreateOutcome.LIMIT_EXCEEDED
            creates++
            created += resource
            return UploadCreateOutcome.CREATED
        }
    }

    /** The primary resource of the in-scope photo [id] — what a row for it is recorded from. */
    private fun resource(id: String) = resourceOf(photo(id))

    /** A retry-spent failure — the only kind of job that still crosses the seam. */
    private fun platformJob(key: String, error: UploadFailure? = null) =
        PlatformUploadJob(key = key, contentType = "image/jpeg", error = error, data = Held)

    /**
     * Seed a `COMPLETED` row — "these bytes are already stored". No production writer records that state
     * (the platform does, through the guarded terminal write), so a test states it directly.
     */
    private suspend fun LedgerService.completed(resource: Resource) =
        recordUnlessSettled(resource.toLedgerRow(LedgerState.COMPLETED))

    /** Seed a row as `REQUESTED`, which is what a terminal outcome's guarded write requires. */
    private suspend fun LedgerService.inFlight(key: String, assetId: String = key.substringBefore('-')) =
        recordUnlessSettled(LedgerEntry(key, testAssetId(assetId), LedgerState.REQUESTED))

    /**
     * The one place a cycle is built for these tests, so each test states only what it is about.
     *
     * The defaults live HERE, once and visibly, rather than on `UploadCycle`'s own parameters — that is the
     * distinction the required-ports rule draws (capability `background-upload`). A default in a test helper is an
     * answer stated once, in the file that reads it.
     *
     * The membership's contribution is derived by the real `OwnPhotos` over [platform]'s library, from [direction],
     * the range from [cutoff] and the echo set [echo]. [readGate] defaults to a joined membership on [TEST_EVENT].
     */
    private suspend fun cycle(
        backend: LedgerService,
        platform: FakePlatform,
        cutoff: String = TEST_CUTOFF,
        echo: Set<AssetId> = emptySet(),
        direction: Direction = Direction.Both,
        saveToAlbum: Boolean = true,
        readGate: (() -> CycleGate)? = null,
        onDiscovery: suspend (EventId, SelectionPolicy, Long) -> Boolean = { _, _, _ -> true },
        placeInAlbum: suspend (EventId, Set<AssetId>) -> Unit = { _, _ -> },
        log: Logger = Logger.withTag("UploadCycleTest"),
        keys: KeyedResources = platform.library.keys,
    ): UploadCycle {
        platform.selection?.let { platform.library.select(it) }
        val range = captureRange(cutoff)
        val ledger = LedgerWriter(backend)
        return UploadCycle(
            readGate = readGate ?: {
                CycleGate.Run(
                    UploadConfig(host = TEST_HOST, eventId = testEventId(TEST_EVENT)),
                    JoinedMembership(
                        eventId = testEventId(TEST_EVENT),
                        contribution = { platform.library.contribution(range, echo, direction) },
                        saveToAlbum = saveToAlbum,
                        manifestVersion = TEST_MANIFEST_VERSION,
                    ),
                )
            },
            engineFor = { SyncEngine(StubUploadRequestProvider(), ledger) },
            ledger = ledger,
            platform = platform,
            photos = platform.library.photos,
            keys = keys,
            onDiscovery = onDiscovery,
            placeInAlbum = placeInAlbum,
            log = log,
        )
    }

    private suspend fun cycleOver(
        backend: LedgerService,
        platform: FakePlatform,
    ): UploadCycle = cycle(backend, platform)

    // ---- The entry gate (capability `background-upload`) -----------------------------------------------
    // The three-state membership read, decided HERE rather than in each composition root. A root reaches
    // this decision only for the tiers its author enumerated: the OS-invoked tier gated on `cycleGate`, and
    // the app-driven tier read a two-state `StateFlow` that cannot express "unreadable" — so a failed
    // Keychain read arrived as a leave.

    @Test
    fun the_publish_carries_the_manifest_version_the_gate_read() = runTest {
        // The gate reads the version FIRST (capability `background-upload`); the cycle must hand that same value
        // to the publish rather than read a fresher one later, or a change its projection missed could carry
        // a version no higher than its own.
        val seen = mutableListOf<Long>()
        cycle(
            TestLedger().service,
            FakePlatform(discovered = listOf(photo("A"))),
            onDiscovery = { _, _, version ->
                seen += version
                true
            },
        ).run()
        assertEquals(listOf(TEST_MANIFEST_VERSION), seen)
    }

    @Test
    fun an_unreadable_membership_touches_nothing() = runTest {
        val backend = TestLedger().service
        // A library full of admissible work: the ONLY reason nothing happens is that the membership could not
        // be read.
        val touched = mutableListOf<String>()
        val platform = FakePlatform(discovered = listOf(photo("A"), photo("B")))

        val result = cycle(
            backend,
            platform,
            readGate = { CycleGate.Skip("config status=-25308, deviceId readable=false") },
            onDiscovery = { _, _, _ ->
                touched += "discovery"
                true
            },
        ).run()

        assertEquals(CycleResult.COMPLETED, result, "an unreadable read is a clean no-op, never a failure")
        assertEquals(emptyList<String>(), touched, "unreadable ≠ left: no hooks")
        assertEquals(emptyList<String>(), platform.created.map { it.filename }, "no upload job")
        assertTrue(platform.walks.isEmpty(), "the library is not walked")
    }

    // ---- Two uploaders over one ledger (decision record `changes/both-uploaders-active`, D2) ---------------

    @Test
    fun a_second_cycle_over_the_same_ledger_never_re_picks_an_in_flight_row() = runTest {
        // The app's cycle and the extension's cycle share one App-Group ledger. Write-after-act is what makes
        // that safe: the first records REQUESTED only after its job exists, and a cycle picks only DISCOVERED.
        val backend = TestLedger().service
        val first = FakePlatform(discovered = listOf(photo("a"), photo("b")))
        val second = FakePlatform(discovered = listOf(photo("a"), photo("b")))

        cycleOver(backend, first).run()
        cycleOver(backend, second).run()

        assertEquals(listOf(keyOf("a"), keyOf("b")), first.created.map { it.filename })
        assertTrue(second.created.isEmpty(), "no duplicate job start in a normal sequence")
        assertEquals(LedgerState.REQUESTED, backend.get(keyOf("a"))?.state)
    }

    @Test
    fun an_overlapping_duplicate_completion_is_a_guarded_no_op() = runTest {
        // Both uploaders happened to create a job for the same key. Whichever completion lands first settles the
        // row; the second matches no REQUESTED row and changes nothing.
        val backend = TestLedger().service
        backend.inFlight("dup-primary.heic", assetId = "dup")

        assertTrue(backend.markTerminal("dup-primary.heic", TerminalOutcome.COMPLETED))
        assertFalse(backend.markTerminal("dup-primary.heic", TerminalOutcome.FAILED), "the late duplicate is refused")
        assertEquals(LedgerState.COMPLETED, backend.get("dup-primary.heic")?.state, "the row converges")
    }

    @Test
    fun a_withheld_cycle_acknowledges_what_was_presented_and_creates_nothing() = runTest {
        val backend = TestLedger().service
        backend.inFlight("done-primary.heic", assetId = "done")
        backend.inFlight("lost-primary.heic", assetId = "lost")
        LedgerWriter(backend).recordRequested(resource("spent"))
        val touched = mutableListOf<String>()
        val platform = FakePlatform(
            discovered = listOf(photo("A")),
            succeeded = listOf("done-primary.heic"),
            ackJobs = listOf(platformJob("spent-primary.heic", UploadFailure("network"))),
            ledger = backend,
        )

        val result = cycle(
            backend,
            platform,
            readGate = { CycleGate.Withheld(UploadConfig(TEST_HOST, testEventId(TEST_EVENT))) },
            onDiscovery = { _, _, _ ->
                touched += "manifest"
                true
            },
        ).run()

        assertEquals(CycleResult.SKIPPED, result)
        assertTrue(platform.drained, "the presented jobs are acknowledged — 50008 otherwise")
        assertEquals(LedgerState.COMPLETED, backend.get("done-primary.heic")?.state, "a presented success is recorded")
        assertEquals(
            LedgerState.DISCOVERED,
            backend.get("spent-primary.heic")?.state,
            "a retry-spent failure is adjudicated back to the work read — but not re-created here",
        )
        assertTrue(platform.created.isEmpty(), "no job is created, retries included")
        assertEquals(
            LedgerState.REQUESTED,
            backend.get("lost-primary.heic")?.state,
            "an in-flight row is left to its completion",
        )
        assertEquals(emptyList<String>(), touched, "a temporary grant state publishes no manifest")
        assertTrue(platform.walks.isEmpty(), "the library is not walked")
    }

    @Test
    fun a_definitively_absent_membership_touches_the_ledger_not_at_all_and_uploads_nothing() = runTest {
        // Not joined is not this cycle's to clean up: the leave that got here cleared the ledger itself
        // (capability `manage-membership`). A row the cycle deleted here would be a row a later join never loads.
        val backend = TestLedger().service
        backend.completed(resource("kept"))
        val platform = FakePlatform(discovered = listOf(photo("A")))

        val result = cycle(backend, platform, readGate = { CycleGate.NotJoined }).run()

        assertEquals(CycleResult.SKIPPED, result, "no membership, nothing to re-arm for — the join arms")
        assertEquals(LedgerState.COMPLETED, backend.get(keyOf("kept"))?.state, "the ledger is untouched")
        assertEquals(emptyList<String>(), platform.created.map { it.filename }, "a leave creates no upload job")
    }

    @Test
    fun the_gate_is_re_read_every_cycle_so_a_leave_takes_effect_without_a_relaunch() = runTest {
        // The cycle is long-lived: a tier whose process survives across cycles must see a membership
        // change on the next run, not on the next launch.
        val backend = TestLedger().service
        val platform = FakePlatform(discovered = listOf(photo("A")))
        var joined = true

        val c = cycle(
            backend,
            platform,
            readGate = {
                if (joined) {
                    CycleGate.Run(
                        UploadConfig(TEST_HOST, testEventId(TEST_EVENT)),
                        JoinedMembership(
                            testEventId(TEST_EVENT),
                            { platform.library.contribution(captureRange(TEST_CUTOFF)) },
                            saveToAlbum = false,
                            manifestVersion = 0L,
                        ),
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

    // ---- The direction gate (capability `background-upload`) -------------------------------------------
    // It sits at the CHOKE POINT — this function, which every trigger on every tier funnels through — and
    // NOT at the arm's invoker. An invoker-gate is only as sound as its enumeration of invokers, and a new
    // tier invalidates that enumeration silently: D3 of `2026-07-07-add-join-direction-mode` reasoned "the
    // producer is never enabled, so the OS never invokes the extension", three days after a tier shipped in
    // which the APP invokes the cycle. A download-only membership then uploaded the member's camera roll on
    // every foreground, while the join gate promised "you won't share yours".

    /** Builds a cycle for a membership that contributes nothing, recording anything it dares to do. */
    private suspend fun decliningCycle(
        backend: LedgerService,
        platform: FakePlatform,
        order: MutableList<String> = mutableListOf(),
    ): UploadCycle = cycle(
        backend,
        platform,
        direction = Direction.DownloadOnly,
        onDiscovery = { _, _, _ ->
            order += "discovery"
            true
        },
    )

    @Test
    fun a_non_contributing_membership_creates_no_job_and_lists_nothing() = runTest {
        // A library full of perfectly admissible work: in scope, no origin exclusion, nothing in flight.
        // The ONLY reason nothing happens is the membership's direction.
        val platform = FakePlatform(
            discovered = listOf(photo("a"), photo("b")),
            succeeded = listOf("c-primary.heic"),
        )
        val order = mutableListOf<String>()

        val result = decliningCycle(TestLedger().service, platform, order).run()

        assertEquals(CycleResult.SKIPPED, result, "declined, and distinguishable from a drained cycle")
        assertTrue(platform.created.isEmpty(), "no upload job for a membership that contributes nothing")
        // The manifest IS written, and is empty — the honest statement of "I share nothing" (capability
        // `photo-sharing`). Withholding it would leave a stale manifest advertising photos the member
        // has stopped sharing. The projection is empty because the policy admits nothing, so the manifest
        // still cannot offer bytes that were never uploaded.
        assertTrue("discovery" in order, "an empty device manifest is published")
    }

    /**
     * The gate precedes the walk. A non-contributor must not walk its library to
     * discover it contributes nothing: the walk costs one PhotoKit round-trip per asset (~110 ms on an SE2),
     * so a per-asset answer would spend minutes arriving at the empty set.
     */
    @Test
    fun the_gate_precedes_the_walk_but_not_the_manifest() = runTest {
        val platform = FakePlatform(discovered = listOf(photo("a")))
        val order = mutableListOf<String>()

        decliningCycle(TestLedger().service, platform, order).run()

        // What the gate withholds is NEW WORK — the walk and job creation. It does not withhold the
        // statement of what this membership shares (the manifest, capability `photo-sharing`). The terminal-job
        // settlement is deliberately not in `order`: acknowledging a job the OS already presented is not
        // new work, and a declined cycle owes it (capability `background-upload`).
        assertEquals(listOf("discovery"), order, "the manifest is still published")
        assertTrue(platform.walks.isEmpty(), "the library is still never enumerated — that is the cost")
    }

    /**
     * A download-only skip is the designed outcome of a setting the member chose — not a fault.
     *
     * This is the contract the inverted gate broke: because `None` carried no capture floor, every
     * download-only cycle took a "malformed policy" branch and logged at `Error`, and `privacy-security`
     * turns `Error` into an event. One wrong severity became four Bugsink issues and an event on every
     * foreground and every completed upload task, for as long as the membership stayed download-only.
     */
    @Test
    fun a_declined_cycle_reports_no_fault() = runTest {
        val recorder = CapturingLogWriter()
        val lines = recorder.lines
        val platform = FakePlatform(discovered = listOf(photo("a")))

        val result = cycle(
            TestLedger().service,
            platform,
            direction = Direction.DownloadOnly,
            log = recorder.logger("UploadCycleTest"),
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
     * The gate bounds new work, not settlement (capability `background-upload`).
     *
     * Acknowledging a job the OS already presented creates nothing, writes no manifest, enumerates
     * nothing and issues no network call — so a declined cycle still owes it. Measured on iOS 26.6: a
     * cycle that returned before this pass, with the extension still registered (which a reconfigure to
     * download-only deliberately leaves it), made the system report error 50008, DISCARD the outstanding
     * jobs, and defer the extension ~300 s against an escalating attempt count.
     */
    @Test
    fun a_declined_cycle_settles_with_the_platform_and_nothing_more() = runTest {
        val backend = TestLedger().service
        val presented = "c-primary.heic"
        backend.inFlight(presented, assetId = "c")
        val platform =
            FakePlatform(discovered = listOf(photo("a")), succeeded = listOf(presented), ledger = backend)
        val order = mutableListOf<String>()

        val result = decliningCycle(backend, platform, order).run()

        assertEquals(CycleResult.SKIPPED, result, "still declined — settling is not contributing")
        assertTrue(
            platform.drained,
            "a declined cycle still settles with the platform — an un-acknowledged presented job errors " +
                "the system 50008 and the OS discards the outstanding jobs",
        )
        assertEquals(
            LedgerState.COMPLETED,
            backend.get(presented)?.state,
            "recorded where the OS reported it — settled, whatever the membership now contributes",
        )
        // And it took nothing the gate withholds: the walk and job creation.
        assertTrue(platform.created.isEmpty(), "no upload job is created")
        assertEquals(listOf("discovery"), order, "the manifest still runs")
        assertTrue(platform.walks.isEmpty(), "the library is never enumerated")
    }

    @Test
    fun a_cycle_never_fetches_the_listing_or_resets_the_ledger() = runTest {
        // The cycle holds no re-join reconciliation (capability `photo-sharing`): the join
        // loads the ledger, and a cycle reads what it is given. A row it did not judge survives it.
        val backend = TestLedger().service
        backend.completed(resource("stored"))
        val platform = FakePlatform(discovered = listOf(photo("stored"), photo("a")))

        assertEquals(CycleResult.COMPLETED, cycle(backend, platform).run())

        assertEquals(listOf(keyOf("a")), platform.created.map { it.filename })
        assertEquals(LedgerState.COMPLETED, backend.get(keyOf("stored"))?.state)
    }

    @Test
    fun discovery_creates_a_job_per_new_resource_and_records_requested_after_create() = runTest {
        val backend = TestLedger().service
        val platform = FakePlatform(discovered = listOf(photo("a"), photo("b")))

        val result = cycleOver(backend, platform).run()

        assertEquals(CycleResult.COMPLETED, result)
        assertEquals(listOf(keyOf("a"), keyOf("b")), platform.created.map { it.filename })
        assertEquals(LedgerState.REQUESTED, backend.get(keyOf("a"))?.state)
        assertEquals(LedgerState.REQUESTED, backend.get(keyOf("b"))?.state)
    }

    @Test
    fun discovery_skips_in_flight_and_completed_resources() = runTest {
        val backend = TestLedger().service
        LedgerWriter(backend).recordRequested(resource("a")) // in flight
        backend.completed(resource("b")) // done
        val platform = FakePlatform(discovered = listOf(photo("a"), photo("b")))

        cycleOver(backend, platform).run()

        assertTrue(platform.created.isEmpty(), "in-flight (REQUESTED) and COMPLETED keys must be skipped")
    }

    @Test
    fun discovery_does_not_re_upload_a_completed_key() = runTest {
        // Uploaded resources are immutable: a COMPLETED key is never re-uploaded, even when the same
        // asset is re-discovered (e.g. after a metadata-only change).
        val backend = TestLedger().service
        backend.completed(resource("a"))
        val platform = FakePlatform(discovered = listOf(photo("a")))

        cycleOver(backend, platform).run()

        assertTrue(platform.created.isEmpty(), "a COMPLETED key must never be re-uploaded")
    }

    @Test
    fun suppressed_downloaded_assets_create_no_job_and_are_not_listed() = runTest {
        // FOREIGN is an asset this device downloaded + imported (in the suppression set). A stale
        // COMPLETED row stands in for a pre-suppression echo: it must never be re-uploaded, and must not
        // be listed to the event. It is no longer PRUNED to achieve that — the echo suppression is an
        // id set supplied per cycle, so the projection re-applies it (capability `photo-sharing`), and
        // the row stays where it belongs: a true record that those bytes are on the backend.
        val backend = TestLedger().service
        backend.completed(resource("FOREIGN"))
        val platform = FakePlatform(discovered = listOf(photo("FOREIGN"), photo("MINE")))
        val echo = setOf(testAssetId("FOREIGN"))

        cycle(backend, platform, echo = echo).run()

        assertEquals(listOf(keyOf("MINE")), platform.created.map { it.filename }) // FOREIGN suppressed
        assertEquals(
            LedgerState.COMPLETED,
            backend.get(keyOf("FOREIGN"))?.state,
            "the stale row survives — it is a true statement about bytes on the backend",
        )
        // The read is not state-scoped, so BOTH rows are present — MINE only REQUESTED, FOREIGN stale and
        // COMPLETED. The echo suppression, an id set supplied per cycle, is what keeps FOREIGN unlisted.
        val rows = backend.manifestRows()
        assertEquals(
            listOf(testAssetId("FOREIGN"), testAssetId("MINE")),
            rows.map { it.assetId }.sorted(),
            "both rows are present to be filtered — the read filters no row",
        )
        val listed = projectDeviceManifest(testDeviceId("D"), rows, policyFor(echo = echo)).assets.map { it.assetId }
        assertEquals(
            listOf(testAssetId("MINE")),
            listed,
            "the echo suppression keeps FOREIGN out, and MINE is DECLARED though its bytes are in flight",
        )
    }

    @Test
    fun suppression_matches_on_the_canonical_assetid() = runTest {
        // The walk and the download importer mint ids through the one platform mapping, so a downloaded
        // asset's createdLocalId IS the id discovery hands out — the §7.6 load-bearing contract. A PhotoKit
        // `localIdentifier` carries `/`, which the mapping turns into the canonical id's `_`.
        val backend = TestLedger().service
        val platform = FakePlatform(discovered = listOf(photo("ABC/L0/001")))

        cycle(backend, platform, echo = setOf(testAssetId("ABC_L0_001"))).run()

        assertTrue(platform.created.isEmpty(), "a downloaded asset must be suppressed on its id")
    }

    @Test
    fun a_succeeded_upload_is_recorded_completed_where_the_platform_reports_it() = runTest {
        // The platform records the outcome where the OS told it — that write is what survives process
        // death — and it records it SETTLED: nothing a completion used to owe is left for a cycle to do.
        // Nothing about the success crosses the seam.
        val backend = TestLedger().service
        LedgerWriter(backend).recordRequested(resource("a"))
        val platform = FakePlatform(discovered = listOf(photo("a")), succeeded = listOf(keyOf("a")), ledger = backend)

        cycleOver(backend, platform).run()

        assertEquals(LedgerState.COMPLETED, backend.get(keyOf("a"))?.state)
        assertTrue(platform.created.isEmpty())
    }

    @Test
    fun a_terminal_outcome_for_a_pruned_row_writes_nothing_at_all() = runTest {
        // The row was pruned (a mid-upload deletion, or a full-enumeration retain) before the outcome
        // arrived. The terminal write is a guarded UPDATE, so with no row to match it writes nothing.
        val backend = TestLedger().service
        val platform = FakePlatform(succeeded = listOf("L-primary.jpg"), ledger = backend)

        cycleOver(backend, platform).run()

        assertNull(backend.get("L-primary.jpg"), "a guarded write cannot resurrect a pruned row")
        assertTrue(platform.drained, "and the platform is still settled with")
    }

    @Test
    fun a_blank_key_terminal_outcome_is_settled_but_records_no_row() = runTest {
        // An unrecoverable key (e.g. a malformed destination URL) must never produce a phantom row, and
        // the platform must still be settled with (an un-acknowledged presented job errors the OS 50008).
        val backend = TestLedger().service
        val platform = FakePlatform(succeeded = listOf(""), ledger = backend)

        cycleOver(backend, platform).run()

        assertNull(backend.get(""), "no phantom row for an unrecoverable key")
        assertTrue(platform.drained, "settled regardless")
    }

    @Test
    fun first_failure_retries_with_a_fresh_url_and_records_requested() = runTest {
        val backend = TestLedger().service
        LedgerWriter(backend).recordRequested(resource("a"))
        val job = platformJob(keyOf("a"), UploadFailure("network"))
        val platform = FakePlatform(discovered = listOf(photo("a")), retryJobs = listOf(job))

        cycleOver(backend, platform).run()

        assertEquals(listOf(job), platform.retried)
        val entry = backend.get(keyOf("a"))
        assertEquals(LedgerState.REQUESTED, entry?.state) // UploadStarted recorded the retry
        assertTrue(platform.created.isEmpty(), "a free retry re-points, it does not create")
    }

    @Test
    fun a_first_failure_for_a_settled_key_never_un_completes_it() = runTest {
        // A join-time load seeded the key COMPLETED from the device's stored-file listing while an old OS job for it
        // was still out, and that job now comes back as a first failure. The pass has no state check, so it
        // still retries (an untouched `.retry` job's fate is unmeasured, and a duplicate PUT converges) — but
        // both records it makes are declined by the ledger's guard.
        val backend = TestLedger().service
        backend.resetTo(
            testEventId(LEDGER_EVENT),
            listOf(LedgerEntry(keyOf("a"), testAssetId("a"), LedgerState.COMPLETED)),
        )
        val job = platformJob(keyOf("a"), UploadFailure("network"))
        val platform = FakePlatform(retryJobs = listOf(job))

        cycleOver(backend, platform).run()

        assertEquals(listOf(job), platform.retried)
        assertEquals(LedgerState.COMPLETED, backend.get(keyOf("a"))?.state, "neither the failure nor REQUESTED landed")
        assertTrue(platform.created.isEmpty())
    }

    @Test
    fun retry_spent_failure_re_creates_from_the_job_resource_and_is_acknowledged() = runTest {
        val backend = TestLedger().service
        LedgerWriter(backend).recordRequested(resource("a"))
        val job = platformJob(keyOf("a"), UploadFailure("network"))
        val platform = FakePlatform(discovered = listOf(photo("a")), ackJobs = listOf(job))

        val result = cycleOver(backend, platform).run()

        assertEquals(CycleResult.COMPLETED, result)
        assertEquals(listOf(keyOf("a")), platform.created.map { it.filename }) // re-created
        assertEquals(LedgerState.REQUESTED, backend.get(keyOf("a"))?.state)
    }

    @Test
    fun retry_spent_failures_past_the_platform_limit_are_left_for_the_work_read() = runTest {
        val backend = TestLedger().service
        LedgerWriter(backend).recordRequested(resource("a"))
        LedgerWriter(backend).recordRequested(resource("b"))
        val platform = FakePlatform(
            discovered = listOf(photo("a"), photo("b")),
            ackJobs = listOf(
                platformJob(keyOf("a"), UploadFailure("network")),
                platformJob(keyOf("b"), UploadFailure("network")),
            ),
            ledger = backend,
            limitAfter = 1,
        )

        val result = cycleOver(backend, platform).run()

        assertEquals(listOf(keyOf("a")), platform.created.map { it.filename }, "the limit stops the re-creation")
        assertEquals(LedgerState.DISCOVERED, backend.get(keyOf("b"))?.state, "the refused one waits in the work read")
        assertEquals(CycleResult.PROCESSING, result)
    }

    @Test
    fun a_retry_spent_failure_without_its_resource_is_not_re_created() = runTest {
        val backend = TestLedger().service
        LedgerWriter(backend).recordRequested(resource("a"))
        val job =
            PlatformUploadJob(
                key = keyOf("a"),
                contentType = "image/jpeg",
                error = UploadFailure("network"),
                data = null,
            )
        // The platform is full, so the enqueue pass cannot re-create it from the ledger either.
        val platform = FakePlatform(
            discovered = listOf(photo("a")),
            ackJobs = listOf(job),
            ledger = backend,
            limitAfter = 0,
        )

        cycleOver(backend, platform).run()

        assertTrue(platform.created.isEmpty(), "no resource to re-create from")
        assertEquals(
            LedgerState.DISCOVERED,
            backend.get(keyOf("a"))?.state,
            "the failure is adjudicated back to the work read",
        )
    }

    @Test
    fun a_retry_spent_failure_for_a_settled_row_is_neither_adjudicated_nor_re_created() = runTest {
        // Two uploaders can overlap: the other one's completion may have settled the row first.
        val backend = TestLedger().service
        backend.completed(resource("a"))
        val platform = FakePlatform(
            discovered = listOf(photo("a")),
            ackJobs = listOf(platformJob(keyOf("a"), UploadFailure("network"))),
            ledger = backend,
        )

        cycleOver(backend, platform).run()
        cycle(
            backend,
            platform,
            readGate = { CycleGate.Withheld(UploadConfig(TEST_HOST, testEventId(TEST_EVENT))) },
        ).run()

        assertTrue(platform.created.isEmpty())
        assertEquals(LedgerState.COMPLETED, backend.get(keyOf("a"))?.state, "a settled row is never re-opened")
    }

    @Test
    fun create_failure_records_no_requested_and_does_not_cap() = runTest {
        val backend = TestLedger().service
        val platform = FakePlatform(discovered = listOf(photo("a")), failCreate = true)

        val result = cycleOver(backend, platform).run()

        assertEquals(CycleResult.COMPLETED, result) // a create FAILURE is not the cap
        // Still no REQUESTED — write-after-act is intact, and that is what this test guards. But the row is not
        // ABSENT: the walk recorded it DISCOVERED before any job was attempted, so a create that failed leaves the
        // resource remembered rather than forgotten.
        assertEquals(LedgerState.DISCOVERED, backend.get(keyOf("a"))?.state)
    }

    @Test
    fun already_completed_re_handed_job_is_a_noop_acknowledge() = runTest {
        val backend = TestLedger().service
        backend.completed(resource("a"))
        val job = platformJob(keyOf("a"), UploadFailure("network"))
        val platform = FakePlatform(discovered = listOf(photo("a")), ackJobs = listOf(job), ledger = backend)

        cycleOver(backend, platform).run()

        // The guarded write cannot touch a COMPLETED row, so the re-handed failure changes nothing — the
        // suppression is structural rather than a state check the cycle has to remember to make.
        assertTrue(platform.created.isEmpty(), "an already-COMPLETED key is not re-created")
        assertEquals(LedgerState.COMPLETED, backend.get(keyOf("a"))?.state)
    }

    @Test
    fun bare_rows_are_backfilled_even_when_creation_stops_early() = runTest {
        val backend = TestLedger().service
        // What a join-time load leaves behind: COMPLETED rows taken from a filename listing, which carries
        // no capture date. A bare row is excluded from every projection fail-closed, so until something
        // fills it this member's photos are missing from the event union.
        backend.recordUnlessSettled(LedgerEntry(keyOf("seeded-a"), testAssetId("seeded-a"), LedgerState.COMPLETED))
        backend.recordUnlessSettled(LedgerEntry(keyOf("seeded-b"), testAssetId("seeded-b"), LedgerState.COMPLETED))
        val platform = FakePlatform(
            // New work FIRST, so creation stops before the walk reaches the seeded rows in the old order.
            discovered = listOf(photo("new-1"), photo("new-2"), photo("new-3"), photo("seeded-a"), photo("seeded-b")),
            limitAfter = 1,
        )

        val result = cycleOver(backend, platform).run()

        assertEquals(CycleResult.PROCESSING, result)
        // BOTH are enriched, including the one the platform's job limit would once have stopped short of.
        // A capture date lives only in the library and only the walk reads it, so a bare row that is not
        // filled here stays bare — and invisible to every projection.
        assertEquals(IN_SCOPE_DATE, backend.get(keyOf("seeded-a"))?.creationDate)
        assertEquals(IN_SCOPE_DATE, backend.get(keyOf("seeded-b"))?.creationDate)
    }

    @Test
    fun a_truncated_cycle_resumes_its_remainder_from_the_ledger_without_re_discovering() = runTest {
        val backend = TestLedger().service
        val platform = FakePlatform(discovered = listOf(photo("a"), photo("b"), photo("c")), limitAfter = 2)
        val cycle = cycleOver(backend, platform)

        assertEquals(CycleResult.PROCESSING, cycle.run())
        assertEquals(listOf(keyOf("a"), keyOf("b")), platform.created.map { it.filename })

        // The platform frees its slots. The walk reads nothing new — "c" is fully known to the ledger now, so it is
        // not re-derived by the walk: it is resolved from its DISCOVERED row.
        platform.freeSlots()
        platform.readAssets.clear()

        assertEquals(CycleResult.COMPLETED, cycle.run())

        assertEquals(
            listOf(keyOf("a"), keyOf("b"), keyOf("c")),
            platform.created.map { it.filename },
            "the remainder resumes",
        )
        assertTrue(platform.readAssets.isEmpty(), "the second walk re-read no asset")
        assertTrue(keyOf("c") in platform.resolvedKeys, "resolved by key from the ledger, not re-derived by a walk")
    }

    @Test
    fun cap_during_creation_leaves_the_remainder_discovered() = runTest {
        val backend = TestLedger().service
        val platform = FakePlatform(discovered = listOf(photo("a"), photo("b"), photo("c")), limitAfter = 2)

        val result = cycleOver(backend, platform).run()

        assertEquals(CycleResult.PROCESSING, result)
        assertEquals(listOf(keyOf("a"), keyOf("b")), platform.created.map { it.filename })
        // Every fact the walk produced is durable before any job is created: the un-created remainder holds a
        // DISCOVERED row, so the platform's job limit loses nothing (capability `background-upload`).
        assertEquals(LedgerState.DISCOVERED, backend.get(keyOf("c"))?.state, "the remainder is remembered")
        assertEquals(LedgerState.REQUESTED, backend.get(keyOf("a"))?.state, "what got a job is in flight")
    }

    // ---- The top-up creates until the platform refuses ---------------------------------------------------
    // Resolving a row costs a synchronous, uninterruptible platform round-trip, so rows are resolved one at a
    // time and the pass stops at the platform's first refusal: the waste is the refused row's own resolve.

    @Test
    fun a_refusal_stops_the_pass_with_no_further_resolve() = runTest {
        // Rows the ledger already fully knows, so this cycle's walk reads none of them — every row is RESOLVED by key.
        // (A row the walk just read is created from the handle in hand and resolves nothing; see the next test.)
        val backend = TestLedger().service
        val library = (1..10).map { photo("r${it.toString().padStart(2, '0')}") }
        LedgerWriter(backend).recordDiscovered(resourcesOf(*library.toTypedArray()))
        val platform = FakePlatform(discovered = library, limitAfter = 2)

        val result = cycleOver(backend, platform).run()

        assertEquals(2, platform.created.size, "creation stops at the refusal")
        assertEquals(
            setOf(keyOf("r01"), keyOf("r02"), keyOf("r03")),
            platform.resolvedKeys,
            "the two created rows and the refused one — nothing past the refusal is resolved",
        )
        assertEquals(CycleResult.PROCESSING, result, "the platform refused, so work remains")
        assertEquals(LedgerState.DISCOVERED, backend.get(keyOf("r10"))?.state, "the remainder is remembered")
    }

    @Test
    fun a_row_this_walk_read_is_created_without_a_second_resolve() = runTest {
        // The walk already read these resources through the same port; resolving them again would repeat one
        // synchronous platform round-trip per new photo for the handle already in hand.
        val backend = TestLedger().service
        val platform = FakePlatform(discovered = listOf(photo("a"), photo("b"), photo("c")))

        val result = cycleOver(backend, platform).run()

        assertEquals(CycleResult.COMPLETED, result)
        assertEquals(
            listOf(keyOf("a"), keyOf("b"), keyOf("c")),
            platform.created.map { it.filename },
            "every discovered row got a job",
        )
        assertEquals(
            listOf(testAssetId("a"), testAssetId("b"), testAssetId("c")),
            platform.readAssets,
            "the walk read each asset once",
        )
        assertEquals(0, platform.resolveCalls, "no row the walk just read was resolved a second time")
        assertTrue(platform.resolvedKeys.isEmpty())
    }

    @Test
    fun only_the_rows_the_walk_did_not_read_are_resolved() = runTest {
        // A mixed pass: "old" rests DISCOVERED from an earlier cycle and its asset is fully known, so the walk
        // skips it; "new" is discovered now. Only "old" costs a resolve. "gone" left the library: the authoritative
        // walk already retracted its row, so it is not even offered.
        val backend = TestLedger().service
        LedgerWriter(backend).recordDiscovered(resourcesOf(photo("old"), photo("gone")))
        val platform = FakePlatform(discovered = listOf(photo("old"), photo("new")))

        val result = cycleOver(backend, platform).run()

        assertEquals(CycleResult.COMPLETED, result)
        assertEquals(setOf(keyOf("old"), keyOf("new")), platform.created.mapTo(mutableSetOf()) { it.filename })
        assertEquals(
            listOf(testAssetId("new")),
            platform.readAssets,
            "the fully known asset was not re-read by the walk",
        )
        assertEquals(setOf(keyOf("old")), platform.resolvedKeys, "only rows the walk did not read are resolved")
        assertEquals(1, platform.resolveCalls, "one resolve per missed row, one row at a time")
        assertNull(backend.get(keyOf("gone")), "the departed asset's row is gone")
    }

    @Test
    fun a_backlog_the_platform_accepts_whole_drains_in_one_pass() = runTest {
        // The regression this guards: truncation is observed ONLY through a refusal now. A pass that reaches
        // the end of the admitted rows without one has created everything.
        val backend = TestLedger().service
        val platform = FakePlatform(discovered = (1..10).map { photo("r${it.toString().padStart(2, '0')}") })

        val result = cycleOver(backend, platform).run()

        assertEquals(10, platform.created.size, "every admitted row got a job")
        assertEquals(CycleResult.COMPLETED, result, "no refusal, no backlog")
    }

    @Test
    fun a_full_platform_reports_work_remaining() = runTest {
        val backend = TestLedger().service
        val platform = FakePlatform(discovered = listOf(photo("a")), limitAfter = 0)

        val result = cycleOver(backend, platform).run()

        assertTrue(platform.created.isEmpty())
        assertEquals(CycleResult.PROCESSING, result, "backpressure, not an absence of work")
        assertEquals(LedgerState.DISCOVERED, backend.get(keyOf("a"))?.state, "the row is untouched")
    }

    @Test
    fun a_full_platform_with_an_empty_ledger_still_drains() = runTest {
        // The counterweight to the test above: "the platform is full" and "there is nothing to do" must not
        // produce the same answer, or a device re-arms forever on a settled ledger.
        val backend = TestLedger().service
        val platform = FakePlatform(discovered = emptyList(), limitAfter = 0)

        val result = cycleOver(backend, platform).run()

        assertEquals(CycleResult.COMPLETED, result, "an empty backlog drains, however full the platform")
    }

    @Test
    fun a_row_that_no_longer_resolves_is_deleted_by_key_and_its_sibling_survives() = runTest {
        // A Live Photo whose primary is uploaded and whose paired video still waits. The video's key now
        // resolves to nothing — its asset left the library since. A top-up resolves from the ledger without a walk,
        // so the resolve's miss is the only evidence, and it speaks for its own key alone.
        val backend = TestLedger().service
        val (still, motion) = resourcesOf(photo("X", live = true))
        backend.completed(still)
        LedgerWriter(backend).recordDiscovered(listOf(motion))
        val platform = FakePlatform(discovered = emptyList())

        val result = cycleOver(backend, platform).topUp()

        assertEquals(CycleResult.COMPLETED, result)
        assertEquals(setOf("X-live.mov"), platform.resolvedKeys, "the row was offered")
        assertNull(backend.get("X-live.mov"), "the unresolvable row is deleted, by its key")
        assertEquals(
            LedgerState.COMPLETED,
            backend.get("X-primary.heic")?.state,
            "its sibling is not this pass's evidence: an asset-scoped write would have reached it",
        )
        assertTrue(platform.created.isEmpty())
    }

    // ---- Deletion is a presence diff over an authoritative walk (capability `photo-sharing`) ----------------
    // The walk is bounded by the policy's capture range and the ledger is device-global, so "not returned"
    // means gone only inside that window and only when the walk is authoritative — the library read under a
    // full grant, or a read selection under a partial one — whatever the row's state.

    /** A dated row, seeded directly: what a walk, a seed or an earlier event left behind. */
    private suspend fun LedgerService.row(
        key: String,
        state: LedgerState = LedgerState.COMPLETED,
        creationDate: String = IN_SCOPE_DATE,
    ) = recordUnlessSettled(
        LedgerEntry(key, testAssetId(key.substringBefore('-')), state, creationDate = creationDate),
    )

    @Test
    fun an_authoritative_walk_deletes_the_rows_of_a_departed_in_window_asset() = runTest {
        val backend = TestLedger().service
        backend.row("gone-primary.jpg")
        backend.row("gone-live.mov")
        backend.row(keyOf("kept"))
        val platform = FakePlatform(discovered = listOf(photo("kept")))

        val result = cycleOver(backend, platform).run()

        assertEquals(CycleResult.COMPLETED, result)
        assertNull(backend.get("gone-primary.jpg"), "every row of the departed asset goes")
        assertNull(backend.get("gone-live.mov"))
        assertEquals(LedgerState.COMPLETED, backend.get(keyOf("kept"))?.state, "a returned asset is present")
    }

    @Test
    fun a_row_outside_the_walks_window_is_kept() = runTest {
        // Dated while an earlier cutoff admitted it: dated before this membership's cutoff, so this
        // walk — narrowed by that cutoff — could never have returned it. Its absence is no evidence.
        val backend = TestLedger().service
        backend.row("old-photo.jpg", creationDate = "2025-01-01T00:00:00Z")
        val platform = FakePlatform()

        cycleOver(backend, platform).run()

        assertEquals(LedgerState.COMPLETED, backend.get("old-photo.jpg")?.state)
    }

    @Test
    fun a_bare_row_is_never_deleted_by_the_walk() = runTest {
        val backend = TestLedger().service
        backend.row("seeded-photo.jpg", creationDate = "")
        val platform = FakePlatform()

        cycleOver(backend, platform).run()

        assertNotNull(backend.get("seeded-photo.jpg"), "an undated row lies in no window")
    }

    @Test
    fun a_walk_that_is_not_authoritative_deletes_nothing() = runTest {
        // A library the platform could not read is no evidence that anything left it. (A READ selection is
        // authoritative — see the partial-grant tests below.)
        val backend = TestLedger().service
        backend.row("deselected-photo.jpg")
        val platform = FakePlatform()
        platform.library.gallery.unreadable = true

        cycleOver(backend, platform).run()

        assertEquals(LedgerState.COMPLETED, backend.get("deselected-photo.jpg")?.state)
    }

    @Test
    fun a_walk_that_is_not_authoritative_admits_nothing() = runTest {
        // No discovery: a photo the library holds is not admitted from a read that could not be made, so nothing is
        // created and nothing the walk would have read is recorded.
        val backend = TestLedger().service
        val platform = FakePlatform(discovered = listOf(photo("unseen")))
        platform.library.gallery.unreadable = true

        cycleOver(backend, platform).run()

        assertTrue(platform.created.isEmpty())
        assertNull(backend.get(keyOf("unseen")))
    }

    @Test
    fun an_in_flight_row_is_deleted_with_its_asset_and_its_late_completion_writes_nothing() = runTest {
        // The photo left, so it leaves the manifest THIS cycle, not when its job settles
        // (`changes/selection-is-the-walk`, D2). The job may still land its bytes; they are listed nowhere.
        val backend = TestLedger().service
        backend.row("gone-photo.jpg")
        backend.row("gone-video.mov", state = LedgerState.REQUESTED)
        val platform = FakePlatform()

        cycleOver(backend, platform).run()

        assertNull(backend.get("gone-photo.jpg"), "the settled row goes")
        assertNull(backend.get("gone-video.mov"), "the in-flight row goes with it")

        assertFalse(
            backend.markTerminal("gone-video.mov", TerminalOutcome.COMPLETED),
            "the late completion's guarded write applies to no row",
        )
        assertNull(backend.get("gone-video.mov"), "and creates none")
    }

    // ---- A presented job whose row is gone is answered and nothing more (capability `background-upload`) ----

    @Test
    fun a_withheld_settle_records_nothing_for_a_deleted_row() = runTest {
        // Before this rule, the engine's failure record — guarded only against a SETTLED row — recreated the row
        // bare: never admitted, never deleted, pending forever.
        val backend = TestLedger().service
        val platform =
            FakePlatform(ackJobs = listOf(platformJob("gone-primary.heic", UploadFailure("network"))), ledger = backend)

        cycle(
            backend,
            platform,
            readGate = { CycleGate.Withheld(UploadConfig(TEST_HOST, testEventId(TEST_EVENT))) },
        ).run()

        assertTrue(platform.drained, "the presented jobs are still drained")
        assertNull(backend.get("gone-primary.heic"), "no row is recorded for a key the ledger does not hold")
        assertEquals(0, backend.pendingResources().size)
    }

    @Test
    fun a_retry_spent_failure_for_a_deleted_row_is_not_re_created() = runTest {
        val backend = TestLedger().service
        val platform =
            FakePlatform(ackJobs = listOf(platformJob("gone-primary.heic", UploadFailure("network"))), ledger = backend)

        cycleOver(backend, platform).run()

        assertTrue(platform.created.isEmpty(), "no job is created for a photo that left")
        assertNull(backend.get("gone-primary.heic"), "and no row is recorded")
    }

    @Test
    fun a_first_failure_for_a_deleted_row_is_not_retried() = runTest {
        val backend = TestLedger().service
        val platform = FakePlatform(retryJobs = listOf(platformJob("gone-primary.heic", UploadFailure("network"))))

        cycleOver(backend, platform).run()

        assertTrue(platform.retried.isEmpty(), "a photo that left is not uploaded again")
        assertNull(backend.get("gone-primary.heic"), "no REQUESTED row is recorded for it")
    }

    @Test
    fun a_first_failure_for_a_present_row_is_still_retried() = runTest {
        val backend = TestLedger().service
        backend.inFlight("here-primary.heic", assetId = "here")
        val platform = FakePlatform(retryJobs = listOf(platformJob("here-primary.heic", UploadFailure("network"))))

        cycleOver(backend, platform).run()

        assertEquals(listOf("here-primary.heic"), platform.retried.map { it.key })
        assertEquals(LedgerState.REQUESTED, backend.get("here-primary.heic")?.state)
    }

    // ---- Under a partial grant the selection is the walk (capability `photo-access`) ---------------

    @Test
    fun a_read_selection_deletes_the_rows_of_a_de_selected_photo_whatever_their_state() = runTest {
        val backend = TestLedger().service
        backend.row(keyOf("kept"))
        backend.row(keyOf("dropped"))
        backend.row(keyOf("flying"), state = LedgerState.REQUESTED)
        val platform = FakePlatform(
            discovered = listOf(photo("dropped"), photo("flying")),
            grant = LibraryGrant.LIMITED,
            selection = listOf(photo("kept")),
        )
        val published = mutableListOf<List<AssetId>>()

        cycle(
            backend,
            platform,
            onDiscovery = { _, policy, _ ->
                published += projectDeviceManifest(testDeviceId("D"), backend.manifestRows(), policy).assets.map { it.assetId }
                true
            },
        ).run()

        assertEquals(LedgerState.COMPLETED, backend.get(keyOf("kept"))?.state, "a selected photo is present")
        assertNull(backend.get(keyOf("dropped")), "de-selecting is deleting, though the photo is still in the library")
        assertNull(backend.get(keyOf("flying")), "in flight or not")
        assertTrue(platform.walks.isEmpty(), "under a partial grant the library is never walked")
        assertEquals(
            listOf(listOf(testAssetId("kept"))),
            published,
            "the manifest that cycle publishes lists only the selection",
        )
    }

    @Test
    fun an_unread_selection_deletes_nothing_even_if_a_cycle_reaches_it() = runTest {
        // The app's admission withholds on an unread selection, so a cycle never gets here in production. This
        // pins the backstop: collapsed to an empty selection, the enqueue resolved every DISCOVERED row against
        // it and deleted each one as gone (`changes/selection-is-the-walk`, D1). Now the key resolve answers
        // `NotReadable`, and `createOne` keeps the row: could not look is never gone.
        val backend = TestLedger().service
        backend.row(keyOf("waiting"), state = LedgerState.DISCOVERED)
        backend.row(keyOf("done"))
        val platform = FakePlatform(discovered = listOf(photo("waiting"), photo("done")), grant = LibraryGrant.LIMITED)

        cycle(backend, platform).run()

        assertEquals(setOf(keyOf("waiting")), platform.resolvedKeys, "the cycle did reach the row's resolve")
        assertEquals(LedgerState.DISCOVERED, backend.get(keyOf("waiting"))?.state, "no row is deleted")
        assertEquals(LedgerState.COMPLETED, backend.get(keyOf("done"))?.state)
        assertTrue(platform.created.isEmpty())
    }

    @Test
    fun presence_is_the_walks_candidate_set_not_the_admitted_set() = runTest {
        // Still in the library, but now in a denylisted album: the admission excludes it, the walk returns
        // it. A diff fed the admitted set would delete it — the retired reconcile's defect, reintroduced.
        val backend = TestLedger().service
        backend.row(keyOf("x"))
        val platform = FakePlatform(discovered = listOf(photo("x")))
        platform.library.inAlbum("WhatsApp", "x")

        cycleOver(backend, platform).run()

        assertEquals(LedgerState.COMPLETED, backend.get(keyOf("x"))?.state)
        assertTrue(platform.created.isEmpty(), "and the admission still keeps it from uploading")
    }

    @Test
    fun a_cap_truncated_authoritative_walk_still_deletes() = runTest {
        // The deletion is a fact about the walk, not about how many jobs the cycle then created.
        val backend = TestLedger().service
        backend.row("gone-photo.jpg")
        val platform = FakePlatform(discovered = listOf(photo("a"), photo("b")), limitAfter = 1)

        val result = cycleOver(backend, platform).run()

        assertEquals(CycleResult.PROCESSING, result)
        assertNull(backend.get("gone-photo.jpg"))
        assertEquals(LedgerState.DISCOVERED, backend.get(keyOf("b"))?.state)
    }

    // ---- A walk re-reads only the assets the ledger does not fully know (capability `photo-sharing`) --------

    @Test
    fun a_fully_known_asset_is_not_re_read() = runTest {
        val backend = TestLedger().service
        backend.completed(resource("known"))
        val platform = FakePlatform(discovered = listOf(photo("known"), photo("new")))

        cycleOver(backend, platform).run()

        assertEquals(
            listOf(testAssetId("new")),
            platform.readAssets,
            "the recorded, enriched asset costs no round-trip",
        )
        assertEquals(LedgerState.REQUESTED, backend.get(keyOf("new"))?.state)
    }

    @Test
    fun an_asset_with_a_bare_row_is_read_and_its_detail_filled() = runTest {
        val backend = TestLedger().service
        backend.recordUnlessSettled(LedgerEntry(keyOf("b"), testAssetId("b"), LedgerState.COMPLETED))
        val platform = FakePlatform(discovered = listOf(photo("b")))

        cycleOver(backend, platform).run()

        assertEquals(listOf(testAssetId("b")), platform.readAssets, "only the walk can fill a bare row")
        assertEquals(IN_SCOPE_DATE, backend.get(keyOf("b"))?.creationDate)
        assertTrue(platform.created.isEmpty(), "and it is still already-uploaded")
    }

    @Test
    fun a_seed_that_listed_one_role_is_completed_by_the_walk() = runTest {
        // A join-time load from the device listing that holds only the primary: the row is bare, so the walk reads
        // the asset and finds the paired video the listing never had.
        val backend = TestLedger().service
        backend.resetTo(
            testEventId(LEDGER_EVENT),
            listOf(LedgerEntry("X-primary.heic", testAssetId("X"), LedgerState.COMPLETED)),
        )
        val platform = FakePlatform(discovered = listOf(photo("X", live = true)))

        cycleOver(backend, platform).run()

        assertEquals(IN_SCOPE_DATE, backend.get("X-primary.heic")?.creationDate)
        assertEquals(listOf("X-live.mov"), platform.created.map { it.filename }, "the missing role uploads")
    }

    @Test
    fun cap_during_re_create_still_walks_publishes_and_returns_processing() = runTest {
        val backend = TestLedger().service
        LedgerWriter(backend).recordRequested(resource("a"))
        val job = platformJob(keyOf("a"), UploadFailure("network"))
        // The asset is still in the library: this is about the cap, not about a departed photo, whose row
        // would be deleted when its key failed to resolve.
        val platform = FakePlatform(discovered = listOf(photo("a")), ackJobs = listOf(job), limitAfter = 0)

        val result = cycleOver(backend, platform).run()

        // Still PROCESSING: the re-created retry never got a job, so work remains and the tail must
        // re-arm. What changed is everything else the cycle used to withhold on the way out.
        assertEquals(CycleResult.PROCESSING, result)
        // It WALKS. This cycle is the one whose remaining backlog most needs accounting for, and an
        // authoritative walk is also what retracts a departed photo.
        assertTrue(platform.walks.isNotEmpty(), "a settle-pass cap hit still enumerates")
        // The retry it could not re-create rests DISCOVERED — which the ledger's work read returns next
        // cycle, so nothing depends on a later walk re-deriving it.
        assertEquals(LedgerState.DISCOVERED, backend.get(keyOf("a"))?.state)
    }

    // ── The publish (capabilities `photo-sharing`, `receiving-photos`) ─────────────────────

    /**
     * Build a cycle whose manifest hook records that it fired, and runs [atPublish] at that moment — so a
     * test can assert what the ledger already said when the manifest was published.
     *
     * The hook still models `DeviceManifestProducer`'s **skip-if-unchanged**, because that is what a real
     * producer answers and a fixture that always reported "published" would let a cycle look like it
     * published when it did not.
     */
    private suspend fun cycleWithHooks(
        backend: LedgerService,
        platform: FakePlatform,
        order: MutableList<String>,
        publishThrows: Boolean = false,
        atPublish: suspend () -> Unit = {},
    ): UploadCycle {
        var lastPublished: List<String>? = null
        return cycle(
            backend,
            platform,
            onDiscovery = { _, _, _ ->
                order += "manifest"
                atPublish()
                if (publishThrows) error("manifest boom")
                val projection = backend.manifestRows().map { it.key }.sorted()
                val changed = projection != lastPublished
                if (changed) lastPublished = projection
                changed
            },
        )
    }

    @Test
    fun a_completion_is_settled_before_the_manifest_is_published() = runTest {
        val backend = TestLedger().service
        // A real completion: the row is in flight, and the platform reports it finished. The guarded
        // write only lands on a REQUESTED row, so an in-flight row is what makes this a completion at all.
        backend.inFlight("a-primary.jpg", assetId = "a")
        val platform = FakePlatform(succeeded = listOf("a-primary.jpg"), ledger = backend)
        val order = mutableListOf<String>()
        val atPublish = mutableListOf<LedgerState?>()

        val result = cycleWithHooks(backend, platform, order, atPublish = {
            atPublish += backend.get("a-primary.jpg")?.state
        }).run()

        assertEquals(CycleResult.COMPLETED, result)
        assertEquals(listOf("manifest"), order, "published exactly once")
        assertEquals(
            listOf<LedgerState?>(LedgerState.COMPLETED),
            atPublish,
            "settled by the time the manifest was published",
        )
    }

    @Test
    fun a_cap_truncated_cycle_still_publishes_what_it_settled() = runTest {
        val backend = TestLedger().service
        // A real completion, and then creation hits the platform's job limit → PROCESSING.
        backend.inFlight("done-primary.jpg", assetId = "done")
        val platform = FakePlatform(
            succeeded = listOf("done-primary.jpg"),
            discovered = listOf(photo("a"), photo("b"), photo("c")),
            ledger = backend,
            limitAfter = 2,
        )
        val order = mutableListOf<String>()

        val result = cycleWithHooks(backend, platform, order).run()

        assertEquals(CycleResult.PROCESSING, result)
        // THE HEADLINE INVERSION. This assertion used to read `order.isEmpty()` — "a cap-truncated cycle
        // refreshes no manifest and fires no notify" — and that is the two-hour silence measured in the
        // field: a device with more outstanding work than the platform's job limit takes this branch on
        // every cycle, so its successfully-uploaded photos never entered the event union.
        assertEquals(listOf("manifest"), order, "a truncated cycle publishes what it settled")
        assertEquals(LedgerState.COMPLETED, backend.get("done-primary.jpg")?.state)
    }

    @Test
    fun a_cycle_that_settled_nothing_still_publishes() = runTest {
        val backend = TestLedger().service
        // New work discovered and created, but nothing COMPLETED this cycle.
        val platform = FakePlatform(discovered = listOf(photo("a")))
        val order = mutableListOf<String>()

        val result = cycleWithHooks(backend, platform, order).run()

        assertEquals(CycleResult.COMPLETED, result)
        // The publish is unconditional now, and that is a simplification rather than a change of
        // behaviour: it always ran, and only the notify behind it was gated on having settled something.
        assertEquals(listOf("manifest"), order)
    }

    @Test
    fun a_throwing_publish_does_not_fail_the_cycle() = runTest {
        val backend = TestLedger().service
        backend.inFlight("a-primary.jpg", assetId = "a")
        val platform = FakePlatform(succeeded = listOf("a-primary.jpg"), ledger = backend)
        val order = mutableListOf<String>()

        val result = cycleWithHooks(backend, platform, order, publishThrows = true).run()

        assertEquals(CycleResult.COMPLETED, result) // best-effort: the failure is absorbed
        assertEquals(listOf("manifest"), order)
    }

    @Test
    fun a_slow_publish_is_not_cut_short_by_a_timeout_of_the_cycle() = runTest {
        // The cycle used to bound the publish with a self-chosen 12 s timeout on both tiers; it holds no clock
        // of its own now (capabilities `sync-status` / `background-upload`; `changes/own-work-per-wake`,
        // D3/D8) — only the per-request HTTP timeout, which lives in the client, bounds a request.
        val backend = TestLedger().service
        backend.inFlight("a-primary.jpg", assetId = "a")
        val platform = FakePlatform(succeeded = listOf("a-primary.jpg"), ledger = backend)
        val order = mutableListOf<String>()

        val result = cycleWithHooks(backend, platform, order, atPublish = { delay(30.seconds) }).run()

        assertEquals(CycleResult.COMPLETED, result)
        assertEquals(30.seconds.inWholeMilliseconds, testScheduler.currentTime, "the publish ran to its end")
        assertEquals(listOf("manifest"), order)
    }

    @Test
    fun a_duplicate_succeeded_on_an_already_completed_key_disturbs_nothing() = runTest {
        val backend = TestLedger().service
        // The key is already COMPLETED; the OS re-hands a SUCCEEDED job (at-least-once delivery). This
        // duplicate is not new work: what it must not do is disturb the settled row or the projection built from it.
        backend.completed(resource("a"))
        val platform = FakePlatform(discovered = listOf(photo("a")), succeeded = listOf(keyOf("a")))
        val order = mutableListOf<String>()

        val result = cycleWithHooks(backend, platform, order).run()

        assertEquals(CycleResult.COMPLETED, result)
        assertEquals(LedgerState.COMPLETED, backend.get(keyOf("a"))?.state)
        assertEquals(listOf("manifest"), order)
    }

    @Test
    fun a_pure_re_ack_failed_job_on_a_completed_key_disturbs_nothing() = runTest {
        val backend = TestLedger().service
        backend.completed(resource("a"))
        // A FAILED job whose key is already COMPLETED → the re-ack arm: skipped as settled.
        val platform = FakePlatform(
            discovered = listOf(photo("a")),
            ackJobs = listOf(platformJob(keyOf("a"), UploadFailure("network"))),
        )
        val order = mutableListOf<String>()

        val result = cycleWithHooks(backend, platform, order).run()

        assertEquals(CycleResult.COMPLETED, result)
        assertEquals(LedgerState.COMPLETED, backend.get(keyOf("a"))?.state, "a re-ack never un-settles")
        assertEquals(listOf("manifest"), order)
    }

    // ── Capture-date cutoff (capability `photo-sharing`) ──────────────────────────────────────────

    private suspend fun cycleWithCutoff(
        backend: LedgerService,
        platform: FakePlatform,
        cutoff: String,
    ): UploadCycle = cycle(backend, platform, cutoff = cutoff)

    @Test
    fun cutoff_excludes_pre_cutoff_resources_from_upload() = runTest {
        val backend = TestLedger().service
        val platform = FakePlatform(
            discovered = listOf(photo("old", "2026-07-01T00:00:00Z"), photo("new", "2026-07-10T00:00:00Z")),
        )

        cycleWithCutoff(backend, platform, "2026-07-06T00:00:00Z").run()

        assertEquals(listOf(keyOf("new")), platform.created.map { it.filename })
        assertNull(backend.get(keyOf("old")), "a pre-cutoff asset creates no ledger row")
    }

    @Test
    fun cutoff_applies_to_a_walk_that_takes_no_predicate() = runTest {
        // A partial grant's selection takes no predicate at all, so the cycle's admission is all that stands in the
        // way of a pre-cutoff photo in it.
        val backend = TestLedger().service
        val platform = FakePlatform(
            grant = LibraryGrant.LIMITED,
            selection = listOf(photo("old", "2026-07-01T00:00:00Z")),
        )

        cycleWithCutoff(backend, platform, "2026-07-06T00:00:00Z").run()

        assertTrue(platform.created.isEmpty(), "a pre-cutoff asset is excluded without the fetch's help")
    }

    @Test
    fun the_cutoff_is_passed_to_the_platform_as_a_walk_bound() = runTest {
        // The range scopes the platform's own fetch, so a full enumeration does not walk the whole
        // library (capability `photo-sharing`). The cycle's admission stays authoritative.
        val backend = TestLedger().service
        val platform = FakePlatform(discovered = emptyList())
        val cutoff = Instant.parse("2026-07-06T14:32:11Z")

        cycleWithCutoff(backend, platform, cutoff.toString()).run()

        val walk = platform.walks.single()
        assertEquals(cutoff, walk.createdFrom, "the platform's fetch carries the membership's capture floor")
        assertEquals(cutoff + 30.days, walk.createdUntil, "and its ceiling")
    }

    @Test
    fun a_pre_cutoff_resource_is_dropped_even_when_the_platform_over_returns_it() = runTest {
        // The platform's date predicate is deliberately widened, so it MAY hand back assets before the
        // cutoff. The cycle's admission is what makes that safe — the admitted set must be unchanged.
        val backend = TestLedger().service
        val platform = FakePlatform(discovered = listOf(photo("old", "2000-01-01T00:00:00Z")))
        platform.library.gallery.widened = true

        cycleWithCutoff(backend, platform, "2026-07-06T14:32:11Z").run()

        assertTrue(platform.created.isEmpty(), "an over-returned pre-cutoff resource must still be dropped")
    }

    @Test
    fun an_undated_asset_is_excluded_under_a_cutoff() = runTest {
        val backend = TestLedger().service
        // No capture date: no range holds it — even one the platform's fetch does not narrow by.
        val platform = FakePlatform(discovered = listOf(photo("undated", date = "")))
        platform.library.gallery.widened = true

        cycleWithCutoff(backend, platform, "2026-07-06T00:00:00Z").run()

        assertTrue(platform.created.isEmpty(), "an asset with no creationDate is out of scope under a cutoff")
    }

    // ── Origin exclusions (capability `photo-sharing`) ────────────────────────────────────────
    // The cutoff bounds WHEN a photo was taken; these bound WHAT it is.

    /**
     * Pre-record every resource of [assets] as COMPLETED, so the ledger-projected manifest has rows to
     * list and the only thing left deciding its contents is the admission.
     */
    private suspend fun completing(backend: LedgerService, vararg assets: GalleryAssetResources) {
        resourcesOf(*assets).forEach { backend.completed(it) }
    }

    /**
     * A cycle whose library files [albumExcluded] in a messenger's album, and a manifest hook recording what the
     * manifest actually saw — the REAL projection of the ledger under the cycle's policy.
     */
    private suspend fun originCycle(
        backend: LedgerService,
        platform: FakePlatform,
        albumExcluded: Set<String> = emptySet(),
        manifestSaw: MutableList<AssetId> = mutableListOf(),
    ): UploadCycle {
        albumExcluded.forEach { platform.library.inAlbum("WhatsApp", it) }
        return cycle(
            backend,
            platform,
            onDiscovery = { _, policy, _ ->
                manifestSaw += projectDeviceManifest(testDeviceId("D"), backend.manifestRows(), policy)
                    .assets.map { it.assetId }
                true
            },
        )
    }

    @Test
    fun a_screenshot_never_reaches_the_engine() = runTest {
        val backend = TestLedger().service
        val platform = FakePlatform(
            discovered = listOf(photo("shot", ext = "png", isScreenshot = true), photo("cam")),
        )

        originCycle(backend, platform).run()

        assertEquals(listOf(keyOf("cam")), platform.created.map { it.filename })
        assertNull(backend.get(keyOf("shot", "png")), "an excluded asset creates no ledger row")
    }

    @Test
    fun a_screen_recording_and_a_gif_are_excluded() = runTest {
        // The GIF is excluded by the RESOLUTION FLOOR, not by a rule reading its type — a messenger
        // GIF is 480x270 = 0.13 MP.
        val platform = FakePlatform(
            discovered = listOf(
                photo("rec", ext = "mov", isScreenRecording = true, isVideo = true),
                photo("meme", ext = "gif", width = 480, height = 270),
                photo("cam"),
            ),
        )

        originCycle(TestLedger().service, platform).run()

        assertEquals(listOf(keyOf("cam")), platform.created.map { it.filename })
    }

    @Test
    fun a_compressed_received_image_is_excluded_but_a_1080p_video_is_not() = runTest {
        // The video floor is separate and lower ON PURPOSE: 1080p is 2.07 MP, BELOW the 3 MP image floor.
        // A single shared floor would silently drop every 1080p recording taken at the event.
        val platform = FakePlatform(
            discovered = listOf(
                photo("wa", ext = "jpg", width = 1600, height = 1200), // 1.9 MP → excluded
                photo("clip", ext = "mov", isVideo = true, width = 1920, height = 1080),
            ),
        )

        originCycle(TestLedger().service, platform).run()

        assertEquals(listOf(keyOf("clip", "mov")), platform.created.map { it.filename }, "the 1080p recording survives")
    }

    @Test
    fun an_edited_photo_below_the_floor_is_admitted() = runTest {
        // A crop renders small. Without the hasAdjustments guard this real capture would silently vanish.
        val platform = FakePlatform(discovered = listOf(photo("crop", width = 1000, height = 800, isEdited = true)))

        originCycle(TestLedger().service, platform).run()

        assertEquals(listOf(keyOf("crop")), platform.created.map { it.filename })
    }

    @Test
    fun a_denylisted_album_member_is_excluded_via_the_injected_port() = runTest {
        val platform = FakePlatform(discovered = listOf(photo("wa"), photo("cam")))

        originCycle(TestLedger().service, platform, albumExcluded = setOf("wa")).run()

        assertEquals(listOf(keyOf("cam")), platform.created.map { it.filename })
    }

    @Test
    fun the_origin_filter_covers_a_walk_that_takes_no_predicate() = runTest {
        val platform = FakePlatform(discovered = listOf(photo("shot", ext = "png", isScreenshot = true)))
        platform.library.gallery.widened = true

        originCycle(TestLedger().service, platform).run()

        assertTrue(platform.created.isEmpty(), "a library walk excludes it whether or not the platform narrowed")
    }

    @Test
    fun a_selected_photo_reaches_the_engine_whatever_it_is() = runTest {
        // Under a partial grant the member hand-picked each photo, so the rules that judge what a photo is stand
        // down (capability `photo-sharing`, "Under limited access the member's selection decides what a photo is"):
        // a screenshot and a small image are uploaded; the capture range still holds.
        val platform = FakePlatform(
            grant = LibraryGrant.LIMITED,
            selection = listOf(
                photo("shot", ext = "png", isScreenshot = true),
                photo("small", ext = "jpg", width = 1600, height = 1200),
                photo("old", "2020-01-01T00:00:00Z"),
            ),
        )

        originCycle(TestLedger().service, platform).run()

        assertEquals(
            listOf(keyOf("shot", "png"), keyOf("small", "jpg")),
            platform.created.map { it.filename }.sorted(),
        )
    }

    @Test
    fun a_selected_photo_in_a_denylisted_album_reaches_the_engine() = runTest {
        // The album denylist judges what a photo is too — and under a partial grant the album structure is not even
        // read: the member chose the photo.
        val platform = FakePlatform(grant = LibraryGrant.LIMITED, selection = listOf(photo("wa")))

        originCycle(TestLedger().service, platform, albumExcluded = setOf("wa")).run()

        assertEquals(listOf(keyOf("wa")), platform.created.map { it.filename })
    }

    @Test
    fun a_received_photo_in_the_selection_is_never_shared_back() = runTest {
        // Imports join a partial grant's selection — on iPhone as they are created, on Android as the app's own rows,
        // wherever they were saved — and the selection is judged without the rules about what a photo is. Echo
        // suppression is not one of them: a received photo stays out (capability `receiving-photos`).
        val platform = FakePlatform(grant = LibraryGrant.LIMITED, selection = listOf(photo("recv"), photo("cam")))

        cycle(TestLedger().service, platform, echo = setOf(testAssetId("recv"))).run()

        assertEquals(listOf(keyOf("cam")), platform.created.map { it.filename })
    }

    @Test
    fun a_full_walk_withdraws_a_row_the_policy_refuses_for_what_it_is() = runTest {
        // The manifest hook is handed the POLICY, not raw discovery, so it can never list bytes that were never
        // uploaded. A ledger row carries an assetId and a capture date — nothing about what the asset IS — so the
        // projection can re-apply the capture-date bounds and the two id-set exclusions (echo, denylisted album), and
        // not the screenshot or size rules. Those are answered by the walk instead: a full walk that sees the
        // screenshot and refuses it withdraws its row, as it would a deleted photo's (decision record
        // `changes/archive/2026-10-15-limited-selection-decides`, D2). The album member keeps its row: the row's own
        // admission refuses it, so it comes back with no second upload.
        val manifestSaw = mutableListOf<AssetId>()
        val shot = photo("shot", ext = "png", isScreenshot = true)
        val platform = FakePlatform(discovered = listOf(shot, photo("wa"), photo("cam")))

        // Every discovered resource is already COMPLETED, so the ONLY thing deciding what the
        // projection lists is the admission — which is exactly what this test is about.
        val backend = TestLedger().service
        completing(backend, shot, photo("wa"), photo("cam"))
        originCycle(backend, platform, albumExcluded = setOf("wa"), manifestSaw = manifestSaw).run()

        assertEquals(listOf(testAssetId("cam")), manifestSaw, "the screenshot is withdrawn, the album member excluded")
        assertNull(backend.get(keyOf("shot", "png")), "the screenshot's row is forgotten, as a deleted photo's is")
        assertEquals(LedgerState.COMPLETED, backend.get(keyOf("wa"))?.state, "the album member's row is kept")
    }

    @Test
    fun a_selection_walk_keeps_a_row_it_shares_whatever_it_is() = runTest {
        // The same screenshot read from a partial grant's selection is the member's own pick: admitted, listed, kept.
        val manifestSaw = mutableListOf<AssetId>()
        val shot = photo("shot", ext = "png", isScreenshot = true)
        val platform = FakePlatform(grant = LibraryGrant.LIMITED, selection = listOf(shot))
        val backend = TestLedger().service
        completing(backend, shot)

        originCycle(backend, platform, manifestSaw = manifestSaw).run()

        assertEquals(listOf(testAssetId("shot")), manifestSaw)
        assertEquals(LedgerState.COMPLETED, backend.get(keyOf("shot", "png"))?.state)
    }

    @Test
    fun the_projection_re_applies_the_capture_date_bounds() = runTest {
        // BOTH exclusions land on the SAME side of the manifest hook — it is handed the admitted set itself, not the
        // inputs to compute one from. The capture-date bounds used to be withheld from the accumulator as forward-prep
        // for multi-event membership, leaving the per-event projection to re-apply them — exactly how the ceiling went
        // missing. The hook receives one already-decided policy.
        val manifestSaw = mutableListOf<AssetId>()
        val old = photo("old", "2020-01-01T00:00:00Z") // pre-cutoff, an ordinary photo
        val shot = photo("shot", ext = "png", isScreenshot = true)
        val platform = FakePlatform(discovered = listOf(old, shot))

        val backend = TestLedger().service
        completing(backend, old, shot)
        originCycle(backend, platform, manifestSaw = manifestSaw).run()

        // The capture-date bound IS re-applied at projection — the row carries its creation date — so the
        // pre-cutoff asset is not listed, and its row is kept (outside the window, it comes back without an upload).
        // The screenshot is withdrawn by the walk, as in the test above.
        assertEquals(emptyList(), manifestSaw, "the pre-cutoff asset is excluded by the date bound")
        assertNotNull(backend.get(keyOf("old")), "a row outside the window is kept")
        assertTrue(platform.created.isEmpty(), "and neither is uploaded")
    }

    @Test
    fun a_bare_origin_excluded_row_is_not_swept_by_a_full_enumeration() = runTest {
        // A row with no capture date — a join-time load's — is outside every window, so even a walk that sees a
        // screenshot behind it deletes nothing: only a row the manifest would list is the walk's to judge. The walk
        // dates such a row only for an asset it admits.
        val backend = TestLedger().service
        backend.recordUnlessSettled(
            LedgerEntry(key = keyOf("shot", "png"), assetId = testAssetId("shot"), state = LedgerState.COMPLETED),
        )
        val platform = FakePlatform(discovered = listOf(photo("shot", ext = "png", isScreenshot = true), photo("cam")))

        originCycle(backend, platform).run()

        assertEquals(LedgerState.COMPLETED, backend.get(keyOf("shot", "png"))?.state, "a bare row is never judged")
        assertTrue(backend.get(keyOf("cam")) != null, "the admitted asset keeps its row")
    }

    // ---- Narrowing must not prune upload-suppression state -------------------------------------------
    // Change `separate-shared-set-from-uploaded-bytes`, tasks 1.2/1.3: a NARROWING of the membership's scope must not
    // prune ledger rows it has no business touching.

    @Test
    fun raising_the_cutoff_does_not_prune_an_already_uploaded_row() = runTest {
        val backend = TestLedger().service
        // Already contributed under the old, lower cutoff.
        backend.recordUnlessSettled(
            LedgerEntry(
                key = keyOf("old", "jpg"),
                assetId = testAssetId("old"),
                state = LedgerState.COMPLETED,
                creationDate = "2026-07-01T00:00:00Z",
                role = ResourceRole.PRIMARY,
                contentType = "image/jpeg",
                originalFilename = "IMG_old.JPG",
            ),
        )
        // Both assets are still in the library; the member simply raised their cutoff past the older one.
        val platform = FakePlatform(
            discovered = listOf(
                photo("old", "2026-07-01T00:00:00Z", ext = "jpg"),
                photo("new", "2026-07-10T00:00:00Z", ext = "jpg"),
            ),
        )

        cycleWithCutoff(backend, platform, "2026-07-06T00:00:00Z").run()

        assertTrue(
            backend.get(keyOf("old", "jpg")) != null,
            "sync-ledger: retention is a fact about the library, not about the policy - the asset is " +
                "still present and still uploaded, so raising the cutoff must not prune its row " +
                "(pruning it makes the narrowing irreversible: re-widening would re-upload).",
        )
    }

    @Test
    fun turning_the_direction_off_does_not_prune_the_events_rows() = runTest {
        val backend = TestLedger().service
        backend.recordUnlessSettled(
            LedgerEntry(
                key = keyOf("old", "jpg"),
                assetId = testAssetId("old"),
                state = LedgerState.COMPLETED,
                creationDate = IN_SCOPE_DATE,
                role = ResourceRole.PRIMARY,
                contentType = "image/jpeg",
                originalFilename = "IMG_old.JPG",
            ),
        )
        val platform = FakePlatform(discovered = listOf(photo("old", ext = "jpg")))

        cycle(backend, platform, direction = Direction.DownloadOnly).run()

        assertTrue(
            backend.get(keyOf("old", "jpg")) != null,
            "reconfigure-membership: a drained upload is recorded so that re-enabling the direction " +
                "re-uploads nothing - turning the direction off must not wipe the event's ledger.",
        )
    }

    // ---- A non-contributor's manifest (capability `photo-sharing`) --------------------------------
    // The manifest is a FULL-STATE document: a declined cycle publishes the honest statement of what the
    // membership shares, which is nothing.

    @Test
    fun a_declined_cycle_publishes_an_empty_manifest() = runTest {
        val order = mutableListOf<String>()
        val backend = TestLedger().service
        backend.completed(resource("a"))
        var listed: List<AssetId>? = null

        val result = cycle(
            backend,
            FakePlatform(discovered = listOf(photo("a"))),
            direction = Direction.DownloadOnly,
            onDiscovery = { _, policy, _ ->
                order += "discovery"
                listed = projectDeviceManifest(testDeviceId("D"), backend.manifestRows(), policy)
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
        // The round trip the whole change is for (capabilities `manage-membership`, `photo-sharing`).
        // A member shares a photo, raises their cutoff past it, then lowers it back. The listing must go
        // and come back, and the bytes must not move twice.
        val backend = TestLedger().service
        val old = photo("old", "2026-07-01T00:00:00Z")
        val wide = "2026-06-05T00:00:00Z"
        val narrow = "2026-07-06T00:00:00Z"

        // Shared under the original floor.
        cycleWithCutoff(backend, FakePlatform(discovered = listOf(old)), wide).run()
        backend.completed(resourceOf(old))
        assertEquals(
            listOf(testAssetId("old")),
            projectDeviceManifest(testDeviceId("D"), backend.manifestRows(), policyFor(wide)).assets.map { it.assetId },
            "precondition: shared and listed",
        )

        // NARROWED past it. The narrowed fetch no longer returns the asset — which is exactly the case the deleted
        // retain-live reconcile used to read as a deletion.
        val narrowedPlatform = FakePlatform(discovered = listOf(old))
        cycleWithCutoff(backend, narrowedPlatform, narrow).run()

        assertEquals(
            LedgerState.COMPLETED,
            backend.get(keyOf("old"))?.state,
            "the ledger records bytes on the backend — a scope change is not a fact about that",
        )
        assertTrue(
            projectDeviceManifest(testDeviceId("D"), backend.manifestRows(), policyFor(narrow)).assets.isEmpty(),
            "but it stops being listed to the event",
        )

        // WIDENED back. The asset is in scope again and the fetch returns it.
        val widenedPlatform = FakePlatform(discovered = listOf(old))
        cycleWithCutoff(backend, widenedPlatform, wide).run()

        assertTrue(
            widenedPlatform.created.isEmpty(),
            "NO re-upload: the surviving COMPLETED row is what suppresses it. Under the old prune this " +
                "row was gone and every narrowed-out photo uploaded again.",
        )
        assertEquals(
            listOf(testAssetId("old")),
            projectDeviceManifest(testDeviceId("D"), backend.manifestRows(), policyFor(wide)).assets.map { it.assetId },
            "and it is listed again",
        )
    }

    // ── Own-photo album placement at first enqueue (capability `event-album`) ──────────────────────────

    /** Records every placement, with how many jobs the platform had created when it was made. */
    private class Placements(private val platform: FakePlatform) {
        val calls = mutableListOf<Set<AssetId>>()
        val createdAtCall = mutableListOf<Int>()
        val hook: suspend (EventId, Set<AssetId>) -> Unit = { _, ids ->
            calls += ids
            createdAtCall += platform.created.size
        }
    }

    @Test
    fun a_new_photo_is_placed_before_its_upload_job_is_created() = runTest {
        val backend = TestLedger().service
        val platform = FakePlatform(discovered = listOf(photo("a"), photo("b")))
        val placed = Placements(platform)

        cycle(backend, platform, placeInAlbum = placed.hook).run()

        assertEquals(listOf(setOf(testAssetId("a")), setOf(testAssetId("b"))), placed.calls, "one placement per row")
        assertEquals(
            listOf(0, 1),
            placed.createdAtCall,
            "each made before its own job existed — it waits for no upload",
        )
        assertEquals(listOf(keyOf("a"), keyOf("b")), platform.created.map { it.filename }, "and the jobs follow")
    }

    @Test
    fun an_opted_out_membership_places_nothing() = runTest {
        val platform = FakePlatform(discovered = listOf(photo("a")))
        val placed = Placements(platform)

        cycle(TestLedger().service, platform, saveToAlbum = false, placeInAlbum = placed.hook).run()

        assertEquals(emptyList(), placed.calls)
        assertEquals(listOf(keyOf("a")), platform.created.map { it.filename })
    }

    @Test
    fun a_failure_re_enqueued_from_the_ledger_is_placed_again_harmlessly() = runTest {
        // A failed upload returns its row to DISCOVERED (there is no FAILED state), so the enqueue pass that
        // re-creates it places it again — a no-op for an asset already in the album (capability `event-album`).
        val backend = TestLedger().service
        LedgerWriter(backend).recordFailed(resource("f"))
        val platform = FakePlatform(discovered = listOf(photo("f"), photo("n")))
        val placed = Placements(platform)

        cycle(backend, platform, placeInAlbum = placed.hook).run()

        assertEquals(
            listOf(setOf(testAssetId("f")), setOf(testAssetId("n"))),
            placed.calls,
            "one placement per row, the failure included",
        )
        assertEquals(setOf(keyOf("f"), keyOf("n")), platform.created.map { it.filename }.toSet(), "both are enqueued")
    }

    @Test
    fun a_row_the_policy_excludes_or_that_no_longer_resolves_is_not_placed() = runTest {
        val backend = TestLedger().service
        // Admitted when it was recorded, excluded by the membership's policy now.
        backend.recordUnlessSettled(
            LedgerEntry(
                keyOf("old"),
                testAssetId("old"),
                LedgerState.DISCOVERED,
                creationDate = "2025-01-01T00:00:00Z",
            ),
        )
        // Recorded, but the asset has left the library since.
        backend.recordUnlessSettled(
            LedgerEntry(keyOf("gone"), testAssetId("gone"), LedgerState.DISCOVERED, creationDate = IN_SCOPE_DATE),
        )
        val platform = FakePlatform(discovered = listOf(photo("a")))
        val placed = Placements(platform)

        cycle(backend, platform, placeInAlbum = placed.hook).run()

        assertEquals(listOf(setOf(testAssetId("a"))), placed.calls)
        assertEquals(listOf(keyOf("a")), platform.created.map { it.filename })
    }

    // ── Own-photo album placement when a loaded row is healed (capability `event-album`) ───────────────
    // The join-time load seeds the device's stored resources as BARE `COMPLETED` rows. They are never
    // enqueued, and the provision's gather cannot admit them before a walk has dated them — so the walk that
    // dates them is what places them.

    @Test
    fun a_loaded_row_is_placed_on_the_walk_that_heals_it_before_its_detail_is_written() = runTest {
        val backend = TestLedger().service
        backend.resetTo(
            testEventId(LEDGER_EVENT),
            listOf(LedgerEntry("X-primary.heic", testAssetId("X"), LedgerState.COMPLETED)),
        )
        val platform = FakePlatform(discovered = listOf(photo("X")))
        val datesAtPlacement = mutableListOf<String?>()
        val placed = mutableListOf<Set<AssetId>>()

        cycle(backend, platform, placeInAlbum = { _, ids ->
            placed += ids
            datesAtPlacement += backend.get("X-primary.heic")?.creationDate
        }).run()

        assertEquals(listOf(setOf(testAssetId("X"))), placed, "the loaded photo is placed")
        assertEquals(listOf<String?>(""), datesAtPlacement, "while its row is still bare — placed before dated")
        assertEquals(IN_SCOPE_DATE, backend.get("X-primary.heic")?.creationDate, "and the walk then dates it")
        assertTrue(platform.created.isEmpty(), "with no upload job: its bytes are already stored")
    }

    @Test
    fun a_loaded_row_the_policy_does_not_admit_is_not_placed() = runTest {
        val backend = TestLedger().service
        backend.resetTo(
            testEventId(LEDGER_EVENT),
            listOf(LedgerEntry("old-primary.heic", testAssetId("old"), LedgerState.COMPLETED)),
        )
        val platform = FakePlatform(discovered = listOf(photo("old", "2025-01-01T00:00:00Z")))
        platform.library.gallery.widened = true
        val placed = Placements(platform)

        cycle(backend, platform, placeInAlbum = placed.hook).run()

        assertEquals(emptyList(), placed.calls, "a photo outside the window is never placed")
    }

    @Test
    fun an_opted_out_membership_places_no_healed_row() = runTest {
        val backend = TestLedger().service
        backend.resetTo(
            testEventId(LEDGER_EVENT),
            listOf(LedgerEntry("X-primary.heic", testAssetId("X"), LedgerState.COMPLETED)),
        )
        val platform = FakePlatform(discovered = listOf(photo("X")))
        val placed = Placements(platform)

        cycle(backend, platform, saveToAlbum = false, placeInAlbum = placed.hook).run()

        assertEquals(emptyList(), placed.calls)
        assertEquals(IN_SCOPE_DATE, backend.get("X-primary.heic")?.creationDate, "the row is still healed")
    }

    @Test
    fun a_healed_row_is_not_placed_again_by_the_next_walk() = runTest {
        val backend = TestLedger().service
        backend.resetTo(
            testEventId(LEDGER_EVENT),
            listOf(LedgerEntry("X-primary.heic", testAssetId("X"), LedgerState.COMPLETED)),
        )
        val platform = FakePlatform(discovered = listOf(photo("X")))
        val placed = Placements(platform)

        cycle(backend, platform, placeInAlbum = placed.hook).run()
        cycle(backend, platform, placeInAlbum = placed.hook).run()

        assertEquals(
            listOf(setOf(testAssetId("X"))),
            placed.calls,
            "once dated, the row is fully known and not re-read",
        )
    }

    @Test
    fun a_job_limit_leaves_the_refused_row_placed_and_the_next_cycle_places_only_what_still_waits() = runTest {
        val backend = TestLedger().service
        val platform = FakePlatform(discovered = listOf(photo("a"), photo("b"), photo("c")), limitAfter = 1)
        val placed = Placements(platform)

        val first = cycle(backend, platform, placeInAlbum = placed.hook).run()
        assertEquals(CycleResult.PROCESSING, first)
        assertEquals(
            listOf(setOf(testAssetId("a")), setOf(testAssetId("b"))),
            placed.calls,
            "the refused row was placed before the platform refused it; nothing past it was reached",
        )
        assertEquals(LedgerState.DISCOVERED, backend.get(keyOf("b"))?.state, "the refused rows still wait")

        platform.freeSlots()
        cycle(backend, platform, placeInAlbum = placed.hook).run()

        // Repeating a placement is free — adding an asset already in the collection is a no-op — and only
        // the rows that never got a job are repeated.
        assertEquals(listOf(setOf(testAssetId("b")), setOf(testAssetId("c"))), placed.calls.drop(2))
    }

    @Test
    fun a_placement_failure_does_not_stop_job_creation() = runTest {
        val platform = FakePlatform(discovered = listOf(photo("a")))

        val result = cycle(TestLedger().service, platform, placeInAlbum = { _, _ -> error("album boom") }).run()

        assertEquals(CycleResult.COMPLETED, result)
        assertEquals(listOf(keyOf("a")), platform.created.map { it.filename })
    }

    @Test
    fun a_declined_cycle_places_nothing() = runTest {
        val platform = FakePlatform(discovered = listOf(photo("a")))
        val placed = Placements(platform)

        cycle(
            TestLedger().service,
            platform,
            direction = Direction.DownloadOnly,
            placeInAlbum = placed.hook,
        ).run()

        assertEquals(emptyList(), placed.calls)
    }

    // ── Settle and top-up edges ─────────────────────────────────────────────────────────────────────────

    @Test
    fun a_skip_whose_root_supplied_no_forensics_still_says_why_it_skipped() = runTest {
        val recorder = CapturingLogWriter()

        val result = cycle(
            TestLedger().service,
            FakePlatform(discovered = listOf(photo("a"))),
            readGate = { CycleGate.Skip("") },
            log = recorder.logger("UploadCycleTest"),
        ).run()

        assertEquals(CycleResult.COMPLETED, result)
        assertTrue(
            recorder.lines.any { it.first == Severity.Warn && "a required read failed" in it.second },
            "an unreadable membership is visible on a device only through this line; logged: ${recorder.lines}",
        )
    }

    @Test
    fun a_top_up_whose_re_created_retry_the_platform_refused_reports_work_left() = runTest {
        // The member raised their cutoff while this photo's upload was in flight: the ledger's work read no longer
        // admits it, but the platform's spent retry is still re-created — and refused. Work remains either way.
        val backend = TestLedger().service
        LedgerWriter(backend).recordRequested(resourceOf(photo("old", "2025-06-01T10:00:00Z")))
        val platform = FakePlatform(
            ackJobs = listOf(platformJob(keyOf("old"), UploadFailure("network"))),
            ledger = backend,
            limitAfter = 0,
        )

        val result = cycle(backend, platform).topUp()

        assertEquals(CycleResult.PROCESSING, result, "the refused re-creation is work left")
        assertEquals(1, platform.createAttempts, "the re-creation was asked once; the work read admits nothing")
        assertEquals(LedgerState.DISCOVERED, backend.get(keyOf("old"))?.state)
    }

    @Test
    fun a_top_up_over_rows_the_current_policy_no_longer_admits_creates_nothing_and_keeps_them() = runTest {
        // The rows were admitted when the walk recorded them; the member has since raised their cutoff past them.
        val backend = TestLedger().service
        LedgerWriter(backend).recordDiscovered(resourcesOf(photo("a"), photo("b")))
        val platform = FakePlatform(discovered = listOf(photo("a"), photo("b")))

        val result = cycle(backend, platform, cutoff = "2026-07-01T00:00:00Z").topUp()

        assertEquals(CycleResult.COMPLETED, result, "nothing it may create is left waiting")
        assertEquals(0, platform.createAttempts)
        assertEquals(LedgerState.DISCOVERED, backend.get(keyOf("a"))?.state, "an excluded row is kept, not deleted")
        assertEquals(LedgerState.DISCOVERED, backend.get(keyOf("b"))?.state)
    }

    @Test
    fun a_row_the_other_uploader_took_between_the_read_and_the_creation_is_not_created_again() = runTest {
        // Both uploaders write the one shared ledger: the OS-driven one may create this row's job while this cycle
        // resolves it. The engine reads the row again before minting, so no second job is created.
        val backend = TestLedger().service
        val a = resource("a")
        LedgerWriter(backend).recordDiscovered(listOf(a))
        val platform = FakePlatform(discovered = listOf(photo("a")))
        platform.library.keys.before = { LedgerWriter(backend).recordRequested(a) }

        val result = cycle(backend, platform).topUp()

        assertEquals(CycleResult.COMPLETED, result)
        assertEquals(0, platform.createAttempts, "the row was already in flight")
        assertEquals(LedgerState.REQUESTED, backend.get(keyOf("a"))?.state)
    }

    @Test
    fun a_failed_placement_of_healed_rows_still_dates_them() = runTest {
        val backend = TestLedger().service
        backend.resetTo(
            testEventId(LEDGER_EVENT),
            listOf(LedgerEntry("X-primary.heic", testAssetId("X"), LedgerState.COMPLETED)),
        )
        val platform = FakePlatform(discovered = listOf(photo("X")))
        val recorder = CapturingLogWriter()

        val result = cycle(
            backend,
            platform,
            placeInAlbum = { _, _ -> error("album boom") },
            log = recorder.logger("UploadCycleTest"),
        ).run()

        assertEquals(CycleResult.COMPLETED, result, "placement is best-effort")
        assertEquals(IN_SCOPE_DATE, backend.get("X-primary.heic")?.creationDate, "the walk still dated the row")
        assertTrue(recorder.lines.any { it.first == Severity.Warn && "healed rows failed" in it.second })
    }

    @Test
    fun a_retry_spent_failure_the_platform_reported_without_an_error_is_still_re_created() = runTest {
        val backend = TestLedger().service
        LedgerWriter(backend).recordRequested(resource("a"))
        val platform = FakePlatform(
            discovered = listOf(photo("a")),
            ackJobs = listOf(platformJob(keyOf("a"), error = null)),
            ledger = backend,
        )

        cycleOver(backend, platform).run()

        assertEquals(listOf(keyOf("a")), platform.created.map { it.filename })
        assertEquals(LedgerState.REQUESTED, backend.get(keyOf("a"))?.state)
    }

    @Test
    fun once_the_platform_refuses_a_re_creation_the_later_spent_retries_are_not_offered_to_it() = runTest {
        val backend = TestLedger().service
        val library = listOf(photo("a"), photo("b"), photo("c"))
        library.forEach { LedgerWriter(backend).recordRequested(resourceOf(it)) }
        val platform = FakePlatform(
            discovered = library,
            ackJobs = listOf("a", "b", "c").map { platformJob(keyOf(it), UploadFailure("network")) },
            ledger = backend,
            limitAfter = 1,
        )

        val result = cycleOver(backend, platform).topUp()

        assertEquals(CycleResult.PROCESSING, result)
        assertEquals(listOf(keyOf("a")), platform.created.map { it.filename })
        // a created, b refused; c is adjudicated back to the work read but not offered — and the work read then stops
        // at its own first refusal (b).
        assertEquals(3, platform.createAttempts, "a, b, then the work read's first row; never c in the settle")
        assertEquals(LedgerState.DISCOVERED, backend.get(keyOf("c"))?.state, "c waits in the work read")
    }

    @Test
    fun a_re_creation_the_platform_fails_leaves_the_row_waiting_and_records_no_request() = runTest {
        val backend = TestLedger().service
        LedgerWriter(backend).recordRequested(resource("a"))
        val platform = FakePlatform(
            discovered = listOf(photo("a")),
            ackJobs = listOf(platformJob(keyOf("a"), UploadFailure("network"))),
            ledger = backend,
            failCreate = true,
        )

        val result = cycleOver(backend, platform).topUp()

        assertEquals(CycleResult.COMPLETED, result, "a failed creation is not the platform's limit")
        assertTrue(platform.created.isEmpty())
        assertEquals(
            LedgerState.DISCOVERED,
            backend.get(keyOf("a"))?.state,
            "write-after-act: no REQUESTED without a job",
        )
    }

    /** The library's own resource a returned job carries — opaque here, as it is to the cycle. */
    private object Held : LibraryResource
}
