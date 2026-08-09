package app.snapsync.download

import app.snapsync.feature.download.DownloadController
import app.snapsync.feature.download.UnreportedImports
import app.snapsync.ports.EventUnionSource
import app.snapsync.ports.ImportedAssetPresence
import app.snapsync.ports.ImportResult
import app.snapsync.ports.PhotoDownloadJobs
import app.snapsync.ports.PhotoLibraryImporter
import app.snapsync.ports.UnionAsset
import app.snapsync.ports.UnionResource

import app.snapsync.model.AssetPresence
import app.snapsync.ports.AssetRef
import app.snapsync.fake.InMemoryAssetPresence
import app.snapsync.fake.InMemoryDownloadStore
import app.snapsync.ports.PendingDownload
import app.snapsync.ports.StagedResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import app.snapsync.ports.OsReceipt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds

class DownloadControllerTest {

    private val myDevice = "DEVICE-ME"

    private fun asset(device: String, id: String) = UnionAsset(
        deviceId = device,
        assetId = id,
        creationDate = "2026-06-30T10:00:00Z",
        resources = listOf(
            UnionResource("$id-primary.heic", "https://e/$id/primary", "primary", "image/heic", "IMG.HEIC"),
            UnionResource("$id-live.mov", "https://e/$id/live", "live", "video/quicktime", "IMG.MOV"),
        ),
    )

    private class FakeUnion(private val assets: List<UnionAsset>, val ok: Boolean = true) : EventUnionSource {
        var calls = 0
        override suspend fun union(eventId: String): Result<List<UnionAsset>> {
            calls++
            return if (ok) Result.success(assets) else Result.failure(RuntimeException("boom"))
        }
    }

    private class RecordingJobs : PhotoDownloadJobs {
        val enqueued = mutableListOf<PendingDownload>()
        var cancelled = false
        override suspend fun enqueue(downloads: List<PendingDownload>) { enqueued += downloads }
        override suspend fun cancelAll() { cancelled = true }
    }

    /** Imports successfully, minting a deterministic created local id per asset; records nothing else. */
    private class FakeImporter : PhotoLibraryImporter {
        val imported = mutableListOf<AssetRef>()
        val attempted = mutableListOf<AssetRef>()
        var failNext = false

        /** Assets whose import reports [ImportResult.TimedOut] — the device-unhealthy answer. */
        val timeOutFor = mutableSetOf<String>()

        override suspend fun import(ref: AssetRef, resources: List<StagedResource>, creationDate: String): ImportResult {
            attempted += ref
            if (ref.sourceAssetId in timeOutFor) return ImportResult.TimedOut("forced timeout")
            if (failNext) return ImportResult.Failed("forced")
            imported += ref
            return ImportResult.Imported("LOCAL-${ref.sourceAssetId}_L0_001")
        }
    }

    private fun controller(
        union: EventUnionSource,
        store: InMemoryDownloadStore = InMemoryDownloadStore(),
        jobs: RecordingJobs = RecordingJobs(),
        importer: FakeImporter = FakeImporter(),
        presence: ImportedAssetPresence = InMemoryAssetPresence(),
        unreported: UnreportedImports = UnreportedImports(),
        downloadEnabled: () -> Boolean? = { true },
    ) = DownloadController(
        union, store, jobs, importer, presence,
        // Named from here on: this constructor has grown twice mid-change, and positional
        // arguments silently re-bind when it does.
        unreported = unreported,
        myDeviceId = myDevice, downloadEnabled = downloadEnabled,
    )

    @Test
    fun reconcile_skips_own_device_and_plans_only_foreign() = runTest {
        val jobs = RecordingJobs()
        val union = FakeUnion(listOf(asset(myDevice, "MINE"), asset("DEVICE-A", "FOREIGN")))
        controller(union, jobs = jobs).reconcile("event")

        // Only FOREIGN's two resources are enqueued; MINE (own) is skipped.
        assertEquals(setOf("FOREIGN-primary.heic", "FOREIGN-live.mov"), jobs.enqueued.map { it.resource.resourceKey }.toSet())
        assertTrue(jobs.enqueued.all { it.ref.sourceDeviceId == "DEVICE-A" })
    }

    @Test
    fun staged_resources_trigger_import_and_record_suppression() = runTest {
        val store = InMemoryDownloadStore()
        val importer = FakeImporter()
        val c = controller(FakeUnion(listOf(asset("DEVICE-A", "Q"))), store = store, importer = importer)
        val ref = AssetRef("DEVICE-A", "Q")

        c.reconcile("event")
        assertFalse(store.isImported(ref)) // nothing staged yet
        c.onResourceStaged(ref, "Q-primary.heic", "/stage/p")
        assertTrue(importer.imported.isEmpty()) // live still missing → not importable
        c.onResourceStaged(ref, "Q-live.mov", "/stage/l")

        assertEquals(listOf(ref), importer.imported)
        assertTrue(store.isImported(ref))
        assertEquals(setOf("LOCAL-Q_L0_001"), store.suppressedLocalIds()) // suppression handle recorded
        assertEquals(1, store.importedCount())
    }

    @Test
    fun a_failed_import_stays_importable_for_retry() = runTest {
        val store = InMemoryDownloadStore()
        val importer = FakeImporter().also { it.failNext = true }
        val c = controller(FakeUnion(listOf(asset("DEVICE-A", "Q"))), store = store, importer = importer)
        val ref = AssetRef("DEVICE-A", "Q")

        c.reconcile("event")
        c.onResourceStaged(ref, "Q-primary.heic", "/p")
        c.onResourceStaged(ref, "Q-live.mov", "/l")
        assertFalse(store.isImported(ref)) // failed → not imported

        importer.failNext = false
        c.importReady() // retry succeeds
        assertTrue(store.isImported(ref))
    }

    @Test
    fun reconcile_is_a_noop_when_there_is_no_membership_at_all() = runTest {
        // `null` = no membership, a DISTINCT answer from "a membership that excludes download" — and
        // neither enables the arm. The gate used to be two-valued, bound at the root with a `?: true`, so
        // this case resolved to "download freely": the same collapse `UploadArm`'s KDoc blames for starting
        // an upload producer for an event that did not exist. It was unreachable only because every caller
        // happened to pass a config-derived event id — a property of the callers, not of the gate.
        val store = InMemoryDownloadStore()
        val jobs = RecordingJobs()
        val union = FakeUnion(listOf(asset("DEVICE-A", "FOREIGN")))
        controller(union, store = store, jobs = jobs, downloadEnabled = { null }).reconcile("event")

        assertEquals(0, union.calls, "no union fetch without a membership to reconcile against")
        assertTrue(jobs.enqueued.isEmpty(), "no downloads enqueued")
        assertEquals(0, store.importedCount())
    }

    @Test
    fun reconcile_is_a_noop_when_download_is_disabled() = runTest {
        // Upload-only membership: reconcile must not even fetch the union, let alone enqueue or import.
        val store = InMemoryDownloadStore()
        val jobs = RecordingJobs()
        val union = FakeUnion(listOf(asset("DEVICE-A", "FOREIGN")))
        controller(union, store = store, jobs = jobs, downloadEnabled = { false }).reconcile("event")

        assertEquals(0, union.calls, "no union fetch when download is disabled")
        assertTrue(jobs.enqueued.isEmpty(), "no downloads enqueued when download is disabled")
        assertEquals(0, store.importedCount())
    }

    @Test
    fun reconcile_runs_normally_when_download_is_enabled() = runTest {
        // Both / download-only membership: reconcile behaves exactly as before.
        val jobs = RecordingJobs()
        val union = FakeUnion(listOf(asset("DEVICE-A", "FOREIGN")))
        controller(union, jobs = jobs, downloadEnabled = { true }).reconcile("event")

        assertEquals(1, union.calls)
        assertEquals(
            setOf("FOREIGN-primary.heic", "FOREIGN-live.mov"),
            jobs.enqueued.map { it.resource.resourceKey }.toSet(),
        )
    }

    @Test
    fun union_failure_keeps_last_state() = runTest {
        val store = InMemoryDownloadStore()
        val jobs = RecordingJobs()
        controller(FakeUnion(emptyList(), ok = false), store = store, jobs = jobs).reconcile("event")
        assertTrue(jobs.enqueued.isEmpty())
        assertEquals(0, store.importedCount())
    }

    /**
     * A failed union fetch costs the wake its DISCOVERY, not its WORK: the drain reads only the store
     * and bytes already on disk. This was inert while a failing fetch consumed the whole wake; with an
     * explicit request timeout it returns in seconds, so skipping the drain would strand importable
     * assets for no reason.
     */
    @Test
    fun union_failure_still_drains_staged_imports() = runTest {
        val store = InMemoryDownloadStore()
        val jobs = RecordingJobs()
        val importer = FakeImporter()
        val union = FakeUnion(listOf(asset("DEVICE-A", "Q")))
        val ref = AssetRef("DEVICE-A", "Q")

        // A good wake plans the asset and stages one of its two resources — not yet importable.
        val c = controller(union, store = store, jobs = jobs, importer = importer)
        c.reconcile("event")
        c.onResourceStaged(ref, "Q-primary.heic", "/stage/p")
        assertTrue(importer.imported.isEmpty())

        // The second resource lands, then the NEXT wake's union fetch times out. The asset is fully
        // staged, so this wake must still import it.
        store.markStaged(ref, "Q-live.mov", "/stage/l")
        val enqueuedBefore = jobs.enqueued.size
        controller(FakeUnion(emptyList(), ok = false), store = store, jobs = jobs, importer = importer)
            .reconcile("event")

        assertEquals(listOf(ref), importer.imported, "a fast union failure must not strand a staged asset")
        assertTrue(store.isImported(ref))
        assertEquals(enqueuedBefore, jobs.enqueued.size, "discovery is skipped: nothing new is enqueued")
    }

    /**
     * A timeout says the DEVICE is not answering, not that this photo is bad — so the wake's drain
     * stops rather than starting an import per remaining asset against a stalled library, each of which
     * may still commit and become a duplicate. The stopped assets stay importable for the next wake.
     */
    @Test
    fun an_import_timeout_stops_this_wakes_drain_and_leaves_the_rest_importable() = runTest {
        val store = InMemoryDownloadStore()
        val importer = FakeImporter()
        val union = FakeUnion(listOf(asset("DEVICE-A", "AAA"), asset("DEVICE-A", "BBB")))
        val c = controller(union, store = store, importer = importer)
        c.reconcile("event")

        // Both assets fully staged, so both are importable in one drain; the FIRST one times out.
        for (id in listOf("AAA", "BBB")) {
            val ref = AssetRef("DEVICE-A", id)
            store.markStaged(ref, "$id-primary.heic", "/stage/$id/p")
            store.markStaged(ref, "$id-live.mov", "/stage/$id/l")
        }
        importer.timeOutFor += store.importableAssets().first().ref.sourceAssetId

        c.importReady()

        assertEquals(1, importer.attempted.size, "the drain stopped at the timeout instead of continuing")
        assertEquals(0, store.importedCount(), "nothing was imported in the stalled wake")
        assertEquals(2, store.importableAssets().size, "both assets stay importable for the next wake")

        // The next wake, with the library healthy again, drains both.
        importer.timeOutFor.clear()
        c.importReady()
        assertEquals(2, store.importedCount())
    }

    /**
     * The two bounds together, over real parts (capability `ios-app-shell` + `photo-download`): a wake
     * whose import never answers must still release its OS handler, and must leave the photo importable.
     * This is the SNAPSYNC-6 shape — an import suspended in `performChanges` holding the controller's
     * mutex when the process died — with both bounds in place.
     *
     * The shell wiring that supplies the real handler is `:app:ios`, untested by rule; it is verified on
     * device instead. What is testable here is that the pieces compose to the right outcome.
     */
    @Test
    fun a_hung_import_still_releases_the_receipt_and_leaves_the_asset_importable() = runTest {
        val store = InMemoryDownloadStore()
        val hang = CompletableDeferred<Unit>()
        var released = false
        val ref = AssetRef("DEVICE-A", "Q")

        val hangingImporter = object : PhotoLibraryImporter {
            override suspend fun import(r: AssetRef, res: List<StagedResource>, creationDate: String): ImportResult {
                // Exactly the production shape: bound the WAIT, never the library call.
                return withTimeoutOrNull(5.seconds) { hang.await(); ImportResult.Imported("never") }
                    ?: ImportResult.TimedOut("no completion within 5s")
            }
        }
        val c = DownloadController(
            FakeUnion(listOf(asset("DEVICE-A", "Q"))), store, RecordingJobs(),
            hangingImporter, InMemoryAssetPresence(),
            unreported = UnreportedImports(),
            myDeviceId = myDevice, downloadEnabled = { true },
        )
        c.reconcile("event")
        store.markStaged(ref, "Q-primary.heic", "/p")
        store.markStaged(ref, "Q-live.mov", "/l")

        val receipt = OsReceipt("test-wake", 20.seconds, release = { released = true })
        receipt.heldFor { c.importReady() }

        assertTrue(released, "the OS handler must be released even though the import never answered")
        assertFalse(store.isImported(ref), "a photo whose import was abandoned stays importable")
        assertEquals(1, store.importableAssets().size)

        // And the controller's lock was freed, so the next wake can drain at all.
        c.onLeaveOrSwitch()
    }

    @Test
    fun leave_cancels_and_prunes_non_terminal() = runTest {
        val store = InMemoryDownloadStore()
        val jobs = RecordingJobs()
        val c = controller(FakeUnion(listOf(asset("DEVICE-A", "Q"))), store = store, jobs = jobs)
        c.reconcile("event")
        c.onLeaveOrSwitch()
        assertTrue(jobs.cancelled)
        assertTrue(store.pendingDownloads().isEmpty()) // non-terminal dropped
    }

    // ---- the SNAPSYNC-9 guard: absence is only trustworthy once the library has reported -------------

    /**
     * The reported defect (Bugsink `SNAPSYNC-9`). The library answers about COMMITTED state, so it says
     * honestly that an asset does not exist **while the transaction creating it is still open** — measured
     * 19 times, each 9-44 ms after that same asset was successfully created. Acting on that clears a live
     * marker, which drops the asset out of the suppression set, so the device re-uploads a photo it
     * downloaded and every member receives it again as new.
     */
    @Test
    fun an_absent_verdict_never_clears_a_marker_while_the_outcome_is_unreported() = runTest {
        val store = InMemoryDownloadStore()
        val unreported = UnreportedImports()
        val ref = AssetRef("DEVICE-A", "Q")
        // The library sees nothing: the transaction has not committed yet.
        val c = controller(
            FakeUnion(listOf(asset("DEVICE-A", "Q"))), store = store,
            presence = InMemoryAssetPresence(present = MutableStateFlow(emptySet())),
            unreported = unreported,
        )
        c.reconcile("event")
        // An import that created its asset and never reported: marker written, row non-terminal.
        store.recordCreatedLocalId(ref, "LOCAL-Q_L0_001")
        unreported.record(ref)

        c.importReady() // adjudicates

        assertEquals(
            setOf("LOCAL-Q_L0_001"), store.suppressedLocalIds(),
            "the marker of a live transaction survives — clearing it is what re-uploads the photo",
        )
    }

    /** Once the library reports, its answer means what it says and the ordinary path resumes. */
    @Test
    fun an_absent_verdict_clears_the_marker_once_the_outcome_has_been_reported() = runTest {
        val store = InMemoryDownloadStore()
        val unreported = UnreportedImports()
        val ref = AssetRef("DEVICE-A", "Q")
        val c = controller(
            FakeUnion(listOf(asset("DEVICE-A", "Q"))), store = store,
            presence = InMemoryAssetPresence(present = MutableStateFlow(emptySet())),
            unreported = unreported,
        )
        c.reconcile("event")
        store.recordCreatedLocalId(ref, "LOCAL-Q_L0_001")
        unreported.record(ref)
        unreported.forget(ref) // the completion arrived and reported failure

        c.importReady()

        assertTrue(
            store.suppressedLocalIds().isEmpty(),
            "nothing may still commit, so absence is trustworthy and the row goes back to importable",
        )
    }

    /**
     * An abandoned wait is what MAKES a ref unreported. Without this the guard has nothing to consult and
     * the very next pass — in the field, 9-44 ms later — acts on an absent answer about a live transaction.
     */
    @Test
    fun an_abandoned_wait_records_the_ref_as_unreported() = runTest {
        val store = InMemoryDownloadStore()
        val unreported = UnreportedImports()
        val importer = FakeImporter().also { it.timeOutFor += "Q" }
        val ref = AssetRef("DEVICE-A", "Q")
        val c = controller(
            FakeUnion(listOf(asset("DEVICE-A", "Q"))), store = store, importer = importer,
            unreported = unreported,
        )
        c.reconcile("event")
        store.markStaged(ref, "Q-primary.heic", "/p")
        store.markStaged(ref, "Q-live.mov", "/l")

        c.importReady()

        assertTrue(unreported.holds(ref), "we stopped waiting, so its outcome is unknown to us")
    }

    /**
     * A later attempt that DOES report clears the distrust the earlier one caused.
     *
     * Found by mutation: nothing pinned this, and the consequence is not cosmetic. A leftover entry gates
     * the adjudication of the ref's NEXT import — a different transaction, under a different marker — so a
     * photo whose second attempt genuinely failed would never be retried, for the life of the process.
     */
    @Test
    fun a_ref_stops_being_distrusted_once_a_later_attempt_reports() = runTest {
        val store = InMemoryDownloadStore()
        val unreported = UnreportedImports()
        val importer = FakeImporter().also { it.timeOutFor += "Q" }
        val ref = AssetRef("DEVICE-A", "Q")
        val c = controller(
            FakeUnion(listOf(asset("DEVICE-A", "Q"))), store = store, importer = importer,
            unreported = unreported,
        )
        c.reconcile("event")
        store.markStaged(ref, "Q-primary.heic", "/p")
        store.markStaged(ref, "Q-live.mov", "/l")
        c.importReady()
        assertTrue(unreported.holds(ref), "the abandoned wait recorded it")

        importer.timeOutFor.clear() // the library is answering again
        c.importReady()

        assertFalse(
            unreported.holds(ref),
            "the library reported, so absence is trustworthy about this ref again",
        )
    }

    /**
     * The re-check on the PRESENT branch. A verdict is computed outside the lock and applied under it, and
     * `markImported` overwrites `createdLocalId` — so a stale PRESENT replaces a live suppression handle
     * with a dead one. Same harm as a stale ABSENT, different route.
     */
    @Test
    fun a_present_verdict_that_went_stale_is_discarded() = runTest {
        val store = InMemoryDownloadStore()
        val ref = AssetRef("DEVICE-A", "Q")
        // The row moves on DURING the lookup — which is when it really happens: the presence call blocks
        // (it is a synchronous platform round-trip, which is why it runs outside the lock), and the
        // platform's completion callback settles the row from another thread while it does. Modelled by
        // making the lookup itself the thing that moves the row, so the interleaving is deterministic.
        val lookupMovesTheRowOn = object : ImportedAssetPresence {
            override suspend fun presence(localIds: Set<String>): Map<String, AssetPresence> {
                store.clearCreatedLocalId(ref)
                store.recordCreatedLocalId(ref, "SECOND")
                return localIds.associateWith { AssetPresence.PRESENT }
            }
        }
        val c = controller(
            FakeUnion(listOf(asset("DEVICE-A", "Q"))), store = store, presence = lookupMovesTheRowOn,
        )
        c.reconcile("event")
        store.recordCreatedLocalId(ref, "FIRST") // the verdict below is computed for THIS marker

        c.importReady()

        assertEquals(
            setOf("SECOND"), store.suppressedLocalIds(),
            "the stale verdict did not overwrite the marker the row now holds",
        )
    }

    /**
     * The ABSENT branch's under-lock re-check, and the reason it exists.
     *
     * Both the verdict AND the in-flight gate are read outside the lock; the completion callback runs on
     * the platform's queue and takes no lock. So between them the completion can settle the row and forget
     * the ref — `holds` then answers false, and an unguarded clear strips the marker off a row that is
     * already IMPORTED. That row is terminal, so nothing ever adjudicates or re-imports it: the asset sits
     * in the library permanently unsuppressed and upload discovery sends it back into the event.
     */
    @Test
    fun an_absent_verdict_does_not_clear_a_marker_the_completion_settled_meanwhile() = runTest {
        val store = InMemoryDownloadStore()
        val unreported = UnreportedImports()
        val ref = AssetRef("DEVICE-A", "Q")
        // The completion lands DURING the lookup — the real interleaving, made deterministic by hanging it
        // off the lookup itself (which on device is a blocking platform round-trip).
        val completionLandsDuringLookup = object : ImportedAssetPresence {
            override suspend fun presence(localIds: Set<String>): Map<String, AssetPresence> {
                store.confirmCreatedLocalId(ref, "LOCAL-Q_L0_001") // the row settles...
                unreported.forget(ref)                             // ...and stops being distrusted
                return localIds.associateWith { AssetPresence.ABSENT }
            }
        }
        val c = controller(
            FakeUnion(listOf(asset("DEVICE-A", "Q"))), store = store,
            presence = completionLandsDuringLookup, unreported = unreported,
        )
        c.reconcile("event")
        store.recordCreatedLocalId(ref, "LOCAL-Q_L0_001")
        unreported.record(ref)

        c.importReady()

        assertEquals(
            setOf("LOCAL-Q_L0_001"), store.suppressedLocalIds(),
            "the settled row keeps its marker — clearing it leaves the asset permanently unsuppressed",
        )
        assertTrue(store.isImported(ref), "and the row stays settled")
    }

    /**
     * The gate must be read UNDER the lock, not before it. **Reproduced on device before this test
     * existed** (SE2, iOS 26.6): an import held the lock for its full 30 s deadline while three other
     * staged resources each adjudicated the same row, each saw `holds` answer false — the deadline had
     * not fired, so nothing was recorded yet — and each then queued on the mutex. The import timed out,
     * recorded its ref, released; the first queued adjudication woke with a 30 s-stale gate answer and
     * cleared the marker of an asset created 30 s earlier. The photo was re-imported as a second asset
     * and the first was left unsuppressed.
     *
     * The row-staleness re-check cannot stand in for this: at that instant the row genuinely IS still
     * unconfirmed with that marker, so it passes. Only re-reading the gate under the lock sees the record.
     */
    @Test
    fun an_absent_verdict_rechecks_the_gate_after_waiting_for_the_lock() = runTest {
        val store = InMemoryDownloadStore()
        val unreported = UnreportedImports()
        val ref = AssetRef("DEVICE-A", "Q")
        val completionNeverComes = CompletableDeferred<Unit>()
        // Holds the controller's lock for the whole import, exactly as a real one does, and records its
        // marker from "inside the change block" before hanging.
        val attempts = mutableListOf<String>()
        val holdsTheLock = object : PhotoLibraryImporter {
            override suspend fun import(r: AssetRef, res: List<StagedResource>, creationDate: String): ImportResult {
                // A DISTINCT marker per attempt, because PhotoKit mints a fresh identifier per request.
                // With one shared value this test passes over the very defect it exists to catch: the
                // buggy path clears the marker, re-imports, and rewrites the identical string, so the
                // assertion cannot tell "the marker survived" from "it was destroyed and remade".
                val id = "LOCAL-Q_L0_00${attempts.size + 1}"
                attempts += id
                store.recordCreatedLocalId(r, id)
                completionNeverComes.await()
                return ImportResult.TimedOut("no completion within the deadline")
            }
        }
        val c = DownloadController(
            FakeUnion(listOf(asset("DEVICE-A", "Q"))), store, RecordingJobs(), holdsTheLock,
            // An empty library: absent, honestly, because the transaction has not committed.
            InMemoryAssetPresence(present = MutableStateFlow(emptySet())),
            unreported = unreported,
            myDeviceId = myDevice, downloadEnabled = { true },
        )
        c.reconcile("event")
        store.markStaged(ref, "Q-primary.heic", "/p")
        store.markStaged(ref, "Q-live.mov", "/l")

        val importing = launch { c.importReady() } // takes the lock and hangs inside the importer
        yield()
        // A second trigger — on device this is another resource staging. It adjudicates OUTSIDE the lock,
        // sees `holds` answer false because the deadline has not fired yet, and then queues on the mutex.
        val adjudicating = launch { c.importReady() }
        yield()
        assertTrue(unreported.holds(ref).not(), "nothing is recorded yet — that is the point")

        completionNeverComes.complete(Unit) // the deadline fires: TimedOut, ref recorded, lock released
        importing.join()
        adjudicating.join()

        assertTrue(unreported.holds(ref), "the abandoned wait recorded it")
        assertEquals(
            listOf("LOCAL-Q_L0_001"), attempts,
            "the photo was imported ONCE — a re-import here is the duplicate, and it is the harm",
        )
        assertEquals(
            setOf("LOCAL-Q_L0_001"), store.suppressedLocalIds(),
            "the queued adjudication re-read the gate under the lock, so the live marker survived",
        )
    }

    /**
     * UNKNOWN is what a partial or revoked photo grant produces — a first-class grant in this app
     * (capability `limited-photo-access`). Treating it as absence clears live markers for every such user.
     */
    @Test
    fun an_unknown_verdict_changes_nothing() = runTest {
        val store = InMemoryDownloadStore()
        val ref = AssetRef("DEVICE-A", "Q")
        val c = controller(
            FakeUnion(listOf(asset("DEVICE-A", "Q"))), store = store,
            // Unreadable: the grant cannot answer, which is NOT the same as answering "absent".
            presence = InMemoryAssetPresence(readable = MutableStateFlow(false)),
        )
        c.reconcile("event")
        store.recordCreatedLocalId(ref, "LOCAL-Q_L0_001")

        c.importReady()

        assertEquals(
            setOf("LOCAL-Q_L0_001"), store.suppressedLocalIds(),
            "a miss under an unreadable grant is not absence",
        )
        assertFalse(store.isImported(ref), "and nothing was settled on an answer we did not get")
    }

    /**
     * A row the presence lookup returned NO entry for. The port's contract says a missing entry means
     * UNKNOWN; reading it as ABSENT would clear that row's marker on the strength of a non-answer.
     */
    @Test
    fun a_verdict_missing_from_the_lookup_is_read_as_unknown_not_absent() = runTest {
        val store = InMemoryDownloadStore()
        val ref = AssetRef("DEVICE-A", "Q")
        val silent = object : ImportedAssetPresence {
            override suspend fun presence(localIds: Set<String>): Map<String, AssetPresence> = emptyMap()
        }
        val c = controller(FakeUnion(listOf(asset("DEVICE-A", "Q"))), store = store, presence = silent)
        c.reconcile("event")
        store.recordCreatedLocalId(ref, "LOCAL-Q_L0_001")

        c.importReady()

        assertEquals(
            setOf("LOCAL-Q_L0_001"), store.suppressedLocalIds(),
            "no entry is not an absent entry",
        )
    }
}
