package app.snapsync.download

import app.snapsync.feature.download.DownloadController
import app.snapsync.ports.EventUnionSource
import app.snapsync.ports.ImportedAssetPresence
import app.snapsync.ports.ImportResult
import app.snapsync.ports.DownloadStore
import app.snapsync.ports.PhotoDownloadJobs
import app.snapsync.ports.PhotoLibraryImporter
import app.snapsync.ports.UnionAsset
import app.snapsync.ports.UnionResource

import app.snapsync.model.AssetPresence
import app.snapsync.ports.AssetRef
import app.snapsync.fake.InMemoryAssetPresence
import app.snapsync.fake.InMemoryDownloadStore
import app.snapsync.ports.StagedBytes
import app.snapsync.ports.PendingDownload
import app.snapsync.ports.StagedResource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail
import app.snapsync.ports.OsReceipt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
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

    /**
     * Imports successfully, minting a deterministic created local id per asset; records nothing else.
     *
     * [hangFor] holds an import open forever — the shape a stalled photo library produces now that nothing
     * bounds the wait. A hung import never returns, so its ref stays claimed for the life of the process.
     */
    private class FakeImporter : PhotoLibraryImporter {
        val imported = mutableListOf<AssetRef>()
        val attempted = mutableListOf<AssetRef>()
        var failNext = false

        /** Whether a forced failure reports that the library already took the staged files. */
        var failConsumingResources = false

        /**
         * Where the in-block marker write lands, when a test needs one.
         *
         * The REAL importer records `createdLocalId` from inside `performChanges`, before the commit is
         * observable — which is precisely what makes a row unconfirmed for the whole duration of its
         * import. A fake that skips it cannot reproduce a burst's steady state, and a test written against
         * such a fake passes whether or not adjudication runs. That is not hypothetical: this fake did skip
         * it, and the first version of `a_burst_of_staged_resources_asks_the_photo_library_nothing` survived
         * having the removed call sites put back.
         */
        var markerStore: InMemoryDownloadStore? = null

        /** Assets whose import never returns — the library is not answering about them. */
        val hangFor = mutableSetOf<String>()

        /**
         * How many times ONE ref may be imported before this fake raises.
         *
         * An unbounded re-selection of one ref is a live-lock, and a live-lock in a test is a HANG — which
         * names no defect and proves nothing. Without this, removing the drain's attempted-set makes the
         * suite hang instead of go red, so the mutation could not be revert-proofed at all. Measured: it
         * did exactly that on the first attempt.
         */
        var attemptCap: Int = 20

        /** Completes once a hung import has actually entered, so a test never races the claim. */
        val hanging = CompletableDeferred<AssetRef>()

        private val never = CompletableDeferred<Unit>()

        override suspend fun import(ref: AssetRef, resources: List<StagedResource>, creationDate: String): ImportResult {
            attempted += ref
            val forThisRef = attempted.count { it == ref }
            check(forThisRef <= attemptCap) {
                "imported ${ref.sourceAssetId} $forThisRef times (cap $attemptCap) — the drain is " +
                    "live-locking on one ref instead of offering it once per pass"
            }
            markerStore?.recordCreatedLocalId(ref, "LOCAL-${ref.sourceAssetId}_L0_001")
            if (ref.sourceAssetId in hangFor) {
                hanging.complete(ref)
                never.await() // no deadline anywhere: this is what a stalled library looks like
            }
            if (failNext) return ImportResult.Failed("forced", consumedResources = failConsumingResources)
            imported += ref
            return ImportResult.Imported("LOCAL-${ref.sourceAssetId}_L0_001")
        }
    }

    /**
     * Counts library lookups, so "how often did we ask?" is assertable rather than inferred from a log.
     * Rigging, so it lives in the test and not in `:adapter:generic:fake` (`FakeHonestyTest`).
     */
    private class CountingPresence(
        private val delegate: ImportedAssetPresence = InMemoryAssetPresence(),
    ) : ImportedAssetPresence {
        var calls = 0
            private set

        override suspend fun presence(localIds: Set<String>): Map<String, AssetPresence> {
            calls++
            return delegate.presence(localIds)
        }
    }

    private fun controller(
        union: EventUnionSource,
        store: DownloadStore = InMemoryDownloadStore(),
        jobs: RecordingJobs = RecordingJobs(),
        importer: FakeImporter = FakeImporter(),
        presence: ImportedAssetPresence = InMemoryAssetPresence(),
        downloadEnabled: () -> Boolean? = { true },
        stagedBytes: StagedBytes = StagedBytes.None,
    ) = DownloadController(
        union, store, jobs, importer, presence,
        stagedBytes = stagedBytes,
        // Named from here on: this constructor has grown twice mid-change, and positional
        // arguments silently re-bind when it does.
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
        assertFalse(store.isSettled(ref)) // nothing staged yet
        c.onResourceStaged(ref, "Q-primary.heic", "/stage/p")
        assertTrue(importer.imported.isEmpty()) // live still missing → not importable
        c.onResourceStaged(ref, "Q-live.mov", "/stage/l")

        assertEquals(listOf(ref), importer.imported)
        assertTrue(store.isSettled(ref))
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
        assertFalse(store.isSettled(ref)) // failed → not imported

        importer.failNext = false
        c.importReady() // retry succeeds
        assertTrue(store.isSettled(ref))
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
        assertTrue(store.isSettled(ref))
        assertEquals(enqueuedBefore, jobs.enqueued.size, "discovery is skipped: nothing new is enqueued")
    }

    /**
     * ONE stalled import strands no other ref (capability `photo-download`).
     *
     * This is what the claim buys, and it is the opposite of the rule it replaced. A per-import deadline
     * used to stop the whole wake's drain, so that a stalled library did not have one transaction
     * abandoned per remaining asset; nothing is abandoned any more, so the right behaviour is for every
     * other trigger to keep going past the claimed ref.
     *
     * Claiming the whole importable batch up front would fail this: refs BBB and CCC would be claimed
     * behind AAA and stay claimed until the process ended.
     */
    @Test
    fun a_hung_import_strands_no_other_ref() = runTest {
        val store = InMemoryDownloadStore()
        val importer = FakeImporter().also { it.hangFor += "AAA" }
        val union = FakeUnion(listOf(asset("DEVICE-A", "AAA"), asset("DEVICE-A", "BBB"), asset("DEVICE-A", "CCC")))
        val c = controller(union, store = store, importer = importer)
        c.reconcile("event")
        for (id in listOf("AAA", "BBB", "CCC")) {
            val ref = AssetRef("DEVICE-A", id)
            store.markStaged(ref, "$id-primary.heic", "/stage/$id/p")
            store.markStaged(ref, "$id-live.mov", "/stage/$id/l")
        }

        // The first trigger reaches AAA and parks inside the library, holding its claim.
        val stalled = launch { c.importReady() }
        importer.hanging.await()

        // A second trigger — on device, another resource staging or a push. It must skip the claimed ref
        // and import the rest, rather than queueing behind it or finding them claimed.
        withTimeoutOrNull(5.seconds) { c.importReady() }
            ?: fail("a second trigger queued behind the stalled import instead of skipping it")

        assertEquals(
            setOf("BBB", "CCC"), importer.imported.mapTo(mutableSetOf()) { it.sourceAssetId },
            "every other ref imported while AAA's transaction stayed open",
        )
        assertFalse(store.isSettled(AssetRef("DEVICE-A", "AAA")), "and AAA is still not imported")
        stalled.cancel()
    }

    /**
     * THE FLAGSHIP (`SNAPSYNC-6`). A stalled photo library must block nothing else.
     *
     * The field hang held the controller's lock from 09:03:37 until the process died — every later
     * reconcile, import, leave, switch and `onResourceStaged` in that process queued behind it,
     * permanently. `onResourceStaged` is the sharp edge: it is called from the background `URLSession`
     * delegate inside an OS-granted wake, so blocking it costs that wake its staging work.
     *
     * **Written to FAIL rather than hang.** Move the platform call back under the mutex and each
     * `withTimeoutOrNull` below returns null, so this reports a named failure instead of a stuck suite —
     * a mutation that merely hangs proves nothing.
     */
    @Test
    fun a_stalled_import_blocks_no_other_operation() = runTest {
        val store = InMemoryDownloadStore()
        val jobs = RecordingJobs()
        val importer = FakeImporter().also { it.hangFor += "Q" }
        val ref = AssetRef("DEVICE-A", "Q")
        val other = AssetRef("DEVICE-B", "R")
        val c = controller(
            FakeUnion(listOf(asset("DEVICE-A", "Q"), asset("DEVICE-B", "R"))),
            store = store, jobs = jobs, importer = importer,
        )
        c.reconcile("event")
        store.markStaged(ref, "Q-primary.heic", "/p")
        store.markStaged(ref, "Q-live.mov", "/l")

        val stalled = launch { c.importReady() }
        importer.hanging.await() // the transaction is open and its claim is held

        withTimeoutOrNull(5.seconds) { c.reconcile("event") }
            ?: fail("reconcile queued behind the stalled import — the lock still spans the platform call")
        withTimeoutOrNull(5.seconds) { c.onResourceStaged(other, "R-primary.heic", "/rp") }
            ?: fail("onResourceStaged queued behind the stalled import — an OS wake would lose its staging")
        withTimeoutOrNull(5.seconds) { c.onLeaveOrSwitch() }
            ?: fail("leave queued behind the stalled import")

        assertTrue(jobs.cancelled, "the leave really ran rather than merely returning")
        stalled.cancel()
    }

    /**
     * A wake whose import never answers must still release its OS handler, and must leave the photo
     * importable (capability `ios-app-shell` + `photo-download`).
     *
     * Nothing bounds the import any more; `OsReceipt` bounds the HOLD and lets the work run on, which is
     * the only bound left in the system. The shell wiring that supplies the real handler is `:app:ios`,
     * untested by rule and verified on device.
     */
    @Test
    fun a_hung_import_still_releases_the_receipt_and_leaves_the_asset_importable() = runTest {
        val store = InMemoryDownloadStore()
        var released = false
        val ref = AssetRef("DEVICE-A", "Q")
        val importer = FakeImporter().also { it.hangFor += "Q" }
        val c = controller(FakeUnion(listOf(asset("DEVICE-A", "Q"))), store = store, importer = importer)
        c.reconcile("event")
        store.markStaged(ref, "Q-primary.heic", "/p")
        store.markStaged(ref, "Q-live.mov", "/l")

        val receipt = OsReceipt("test-wake", 1.seconds, release = { released = true })
        val held = launch { receipt.heldFor { c.importReady() } }
        importer.hanging.await()
        // The receipt's deadline is the only clock left in the system; the import is still parked.
        delay(2.seconds)
        assertTrue(released, "the OS handler must be released even though the import never answered")

        assertFalse(store.isSettled(ref), "a photo whose import never reported stays importable")
        assertEquals(1, store.importableAssets().size)
        held.cancel()
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
     *
     * The gate is now the CLAIM: a ref whose import is running in this process is exactly a ref whose
     * transaction may still be open.
     */
    @Test
    fun an_absent_verdict_never_clears_a_marker_while_the_import_is_claimed() = runTest {
        val store = InMemoryDownloadStore()
        val ref = AssetRef("DEVICE-A", "Q")
        val importer = FakeImporter().also { it.hangFor += "Q" }
        // The library sees nothing: the transaction has not committed yet.
        val c = controller(
            FakeUnion(listOf(asset("DEVICE-A", "Q"))), store = store, importer = importer,
            presence = InMemoryAssetPresence(present = MutableStateFlow(emptySet())),
        )
        c.reconcile("event")
        store.markStaged(ref, "Q-primary.heic", "/p")
        store.markStaged(ref, "Q-live.mov", "/l")

        val stalled = launch { c.importReady() }
        importer.hanging.await()
        // The change block ran and wrote its marker; the commit has not landed. On device this write comes
        // from inside `performChanges`, which is why it is not the importer fake's business here.
        store.recordCreatedLocalId(ref, "LOCAL-Q_L0_001")

        c.sweepInterruptedImports() // a second trigger adjudicates the unconfirmed row

        assertEquals(
            setOf("LOCAL-Q_L0_001"), store.suppressedLocalIds(),
            "the marker of a live transaction survives — clearing it is what re-uploads the photo",
        )
        stalled.cancel()
    }

    /** Once the import reports, the claim is gone and the library's answer means what it says. */
    @Test
    fun an_absent_verdict_clears_the_marker_once_the_import_is_no_longer_claimed() = runTest {
        val store = InMemoryDownloadStore()
        val ref = AssetRef("DEVICE-A", "Q")
        val c = controller(
            FakeUnion(listOf(asset("DEVICE-A", "Q"))), store = store,
            presence = InMemoryAssetPresence(present = MutableStateFlow(emptySet())),
        )
        c.reconcile("event")
        // A marker left by a PREVIOUS process: nothing is claimed here, so absence is trustworthy.
        store.recordCreatedLocalId(ref, "LOCAL-Q_L0_001")

        c.sweepInterruptedImports()

        assertTrue(
            store.suppressedLocalIds().isEmpty(),
            "nothing may still commit, so absence is trustworthy and the row goes back to importable",
        )
    }

    /**
     * A *present* verdict releases the claim — the ONLY recovery for a completion that is never delivered.
     *
     * Without it the ref stays claimed for the life of the process: its row is never re-imported, its
     * staged bytes are never freed, and the status total never reaches 100%.
     */
    @Test
    fun a_present_verdict_settles_the_row_and_releases_the_claim() = runTest {
        val store = InMemoryDownloadStore()
        val ref = AssetRef("DEVICE-A", "Q")
        val importer = FakeImporter().also { it.hangFor += "Q" }
        val present = MutableStateFlow(emptySet<String>())
        val c = controller(
            FakeUnion(listOf(asset("DEVICE-A", "Q"))), store = store, importer = importer,
            presence = InMemoryAssetPresence(present = present),
        )
        c.reconcile("event")
        store.markStaged(ref, "Q-primary.heic", "/p")
        store.markStaged(ref, "Q-live.mov", "/l")

        val stalled = launch { c.importReady() }
        importer.hanging.await()
        store.recordCreatedLocalId(ref, "LOCAL-Q_L0_001")
        // The commit DID land; only the callback was lost. The library can see it.
        present.value = setOf("LOCAL-Q_L0_001")

        c.sweepInterruptedImports()

        assertTrue(store.isSettled(ref), "the row is settled against the marker it already held")
        assertEquals(setOf("LOCAL-Q_L0_001"), store.suppressedLocalIds())
        // The claim is released, so a leave's prune is no longer told to protect this ref — the
        // observable consequence of the release, since the set itself is private.
        c.onLeaveOrSwitch()
        assertTrue(store.isSettled(ref), "and the settled row survives the leave on its own merits")
        stalled.cancel()
    }

    /**
     * Two triggers over ONE importable asset create exactly ONE asset (capability `photo-download`).
     *
     * This is the race the lock's *span* used to prevent and the claim now does. Asserted on the number of
     * imports rather than on a marker's value: creating the second asset IS the harm, and an assertion on
     * a marker can pass while the duplicate is being created.
     */
    @Test
    fun two_concurrent_triggers_import_one_asset_once() = runTest {
        val store = InMemoryDownloadStore()
        val importer = FakeImporter().also { it.hangFor += "Q" }
        val ref = AssetRef("DEVICE-A", "Q")
        val c = controller(FakeUnion(listOf(asset("DEVICE-A", "Q"))), store = store, importer = importer)
        c.reconcile("event")
        store.markStaged(ref, "Q-primary.heic", "/p")
        store.markStaged(ref, "Q-live.mov", "/l")

        val first = launch { c.importReady() }
        importer.hanging.await() // the first has claimed the ref and is inside the library
        c.importReady()          // the second must find nothing to do

        assertEquals(
            listOf(ref), importer.attempted,
            "the second trigger must not start a second import for a ref already being imported",
        )
        first.cancel()
    }

    /**
     * A permanently failing asset is offered at most once per drain (capability `photo-download`).
     *
     * A `Failed` import leaves its row importable AND releases its claim, so without the drain's
     * attempted-set the loop re-selects the same ref forever — spinning on any permanently bad resource.
     * The importer's own attempt counter is what turns that live-lock into a named failure rather than a
     * hang.
     */
    @Test
    fun a_permanently_failing_asset_is_attempted_once_per_drain() = runTest {
        val store = InMemoryDownloadStore()
        val importer = FakeImporter().also { it.failNext = true } // stays armed: never cleared below
        val ref = AssetRef("DEVICE-A", "Q")
        val c = controller(FakeUnion(listOf(asset("DEVICE-A", "Q"))), store = store, importer = importer)
        c.reconcile("event")
        store.markStaged(ref, "Q-primary.heic", "/p")
        store.markStaged(ref, "Q-live.mov", "/l")

        c.importReady()

        assertEquals(listOf(ref), importer.attempted, "offered once, not spun on")
        assertEquals(1, store.importableAssets().size, "and still importable for a later wake")
    }

    /**
     * A leave during a claimed import spares that row, and the import settles afterwards.
     *
     * The transaction may still commit, so the import is not cancelled and its row must survive the prune
     * — otherwise the marker write lands on nothing and the created asset is uploaded back into the event.
     * The consequence is deliberate: a leave no longer fully cleans, because the photo IS in the library
     * and its handle is the only thing keeping it out of the upload universe.
     */
    @Test
    fun a_leave_during_a_claimed_import_spares_its_row() = runTest {
        val store = InMemoryDownloadStore()
        val importer = FakeImporter().also { it.hangFor += "Q" }
        val ref = AssetRef("DEVICE-A", "Q")
        val c = controller(FakeUnion(listOf(asset("DEVICE-A", "Q"))), store = store, importer = importer)
        c.reconcile("event")
        store.markStaged(ref, "Q-primary.heic", "/p")
        store.markStaged(ref, "Q-live.mov", "/l")

        val stalled = launch { c.importReady() }
        importer.hanging.await()

        c.onLeaveOrSwitch()

        // The change block runs after the leave — the whole point of protecting the row.
        assertTrue(
            store.recordCreatedLocalId(ref, "LOCAL-Q_L0_001"),
            "the marker write must land on a row that still exists",
        )
        assertEquals(setOf("LOCAL-Q_L0_001"), store.suppressedLocalIds())
        stalled.cancel()
    }

    /**
     * The guard on the PRESENT branch. A verdict is computed outside the lock and applied under it, so a
     * stale PRESENT must not settle a row whose marker has moved on — that would record an import against
     * an identifier the row no longer describes. The guard lives in the store's write.
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
                store.clearCreatedLocalId(ref, "FIRST")
                store.recordCreatedLocalId(ref, "SECOND")
                return localIds.associateWith { AssetPresence.PRESENT }
            }
        }
        val c = controller(
            FakeUnion(listOf(asset("DEVICE-A", "Q"))), store = store, presence = lookupMovesTheRowOn,
        )
        c.reconcile("event")
        store.recordCreatedLocalId(ref, "FIRST") // the verdict below is computed for THIS marker

        c.sweepInterruptedImports()

        assertEquals(
            setOf("SECOND"), store.suppressedLocalIds(),
            "the stale verdict did not overwrite the marker the row now holds",
        )
        assertFalse(store.isSettled(ref), "and it did not settle a row it no longer describes")
    }

    /**
     * The ABSENT branch's guard, and the reason it is in the store's write rather than in a re-check here.
     *
     * The verdict is read outside the lock and the completion callback runs on the platform's queue taking
     * no lock, so between them the completion can settle the row. An unguarded clear then strips the marker
     * off a row that is already IMPORTED — and that row is terminal, so nothing ever adjudicates or
     * re-imports it: the asset sits in the library permanently unsuppressed and upload discovery sends it
     * back into the event.
     */
    @Test
    fun an_absent_verdict_does_not_clear_a_marker_the_completion_settled_meanwhile() = runTest {
        val store = InMemoryDownloadStore()
        val ref = AssetRef("DEVICE-A", "Q")
        // The completion lands DURING the lookup — the real interleaving, made deterministic by hanging it
        // off the lookup itself (which on device is a blocking platform round-trip).
        val completionLandsDuringLookup = object : ImportedAssetPresence {
            override suspend fun presence(localIds: Set<String>): Map<String, AssetPresence> {
                store.confirmCreatedLocalId(ref, "LOCAL-Q_L0_001") // the row settles
                return localIds.associateWith { AssetPresence.ABSENT }
            }
        }
        val c = controller(
            FakeUnion(listOf(asset("DEVICE-A", "Q"))), store = store,
            presence = completionLandsDuringLookup,
        )
        c.reconcile("event")
        store.recordCreatedLocalId(ref, "LOCAL-Q_L0_001")

        c.sweepInterruptedImports()

        assertEquals(
            setOf("LOCAL-Q_L0_001"), store.suppressedLocalIds(),
            "the settled row keeps its marker — clearing it leaves the asset permanently unsuppressed",
        )
        assertTrue(store.isSettled(ref), "and the row stays settled")
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

        c.sweepInterruptedImports()

        assertEquals(
            setOf("LOCAL-Q_L0_001"), store.suppressedLocalIds(),
            "a miss under an unreadable grant is not absence",
        )
        assertFalse(store.isSettled(ref), "and nothing was settled on an answer we did not get")
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

        c.sweepInterruptedImports()

        assertEquals(
            setOf("LOCAL-Q_L0_001"), store.suppressedLocalIds(),
            "no entry is not an absent entry",
        )
    }

    /**
     * THE RESIDUAL, pinned (capability `photo-download`). Measured on device 2026-08-09: a
     * `performChanges` commit SURVIVES the death of the process that opened it, so the prior change's
     * premise ("a transaction cannot outlive its process") is false. A relaunch is normally safe anyway —
     * the commit has landed by the time adjudication asks, so the answer is *present* and the row settles.
     *
     * This pins what happens in the one window where it has NOT landed: a fresh process, no claim, and a
     * library honestly answering *absent* about a commit still in flight. It documents accepted behaviour,
     * not desired behaviour — the same bounded trade `photo-download` already takes for a deleted photo
     * ("resurrects it at most once").
     */
    @Test
    fun a_surviving_commit_still_in_flight_at_relaunch_is_the_accepted_residual() = runTest {
        val store = InMemoryDownloadStore()
        val importer = FakeImporter()
        val ref = AssetRef("DEVICE-A", "Q")
        // A previous process: the change block ran and recorded its marker; the process then died.
        val c = controller(
            FakeUnion(listOf(asset("DEVICE-A", "Q"))), store = store, importer = importer,
            presence = InMemoryAssetPresence(present = MutableStateFlow(emptySet())), // commit in flight
        )
        c.reconcile("event")
        store.markStaged(ref, "Q-primary.heic", "/p")
        store.markStaged(ref, "Q-live.mov", "/l")
        store.recordCreatedLocalId(ref, "FIRST-COPY")

        c.sweepInterruptedImports() // the relaunched process adjudicates

        // What happens today, asserted so a future change cannot alter it silently: the marker is
        // cleared, the asset is imported a SECOND time, and the suppression set holds only the second
        // copy. When the surviving commit then lands, the first copy is in the library with nothing
        // recording that it must not be uploaded — and upload discovery sends it back into the event.
        assertEquals(1, importer.imported.size, "the row was re-imported")
        assertEquals(
            setOf("LOCAL-Q_L0_001"), store.suppressedLocalIds(),
            "and the FIRST copy's handle is gone — this is the residual, not a desired outcome",
        )
        assertTrue("FIRST-COPY" !in store.suppressedLocalIds(), "the surviving commit's asset is unsuppressed")
    }

    /**
     * THE REGRESSION THIS CHANGE EXISTS TO PREVENT (capability `photo-download`).
     *
     * Adjudication used to be the first act of `reconcile`, `importReady` and `onResourceStaged`. The last
     * fires once per staged resource, and during a burst the only unconfirmed row is the import in flight —
     * whose transaction is open, so the library can only answer *absent*, and whose *absent* the gate then
     * discards. Measured on an iPhone XS: 1,164 verdicts in one 131-asset burst, 1,149 of them thrown away.
     *
     * A process that inherited nothing must ask nothing, however much work it does.
     */
    @Test
    fun a_burst_of_staged_resources_asks_the_photo_library_nothing() = runTest {
        val presence = CountingPresence()
        val store = InMemoryDownloadStore()
        // Q's change block runs, writes its marker and never returns — the library is mid-commit. Q is now
        // unconfirmed AND claimed, which is a burst's steady state, and exactly the row every staged
        // resource used to ask about.
        val importer = FakeImporter().apply { markerStore = store; hangFor += "Q" }
        val q = AssetRef("DEVICE-A", "Q")
        val r = AssetRef("DEVICE-A", "R")
        val c = controller(
            FakeUnion(listOf(asset("DEVICE-A", "Q"), asset("DEVICE-A", "R"))),
            store = store, importer = importer, presence = presence,
        )

        c.reconcile("event")
        // Staged into the store directly: routing these through `onResourceStaged` would drain inline and
        // the second call would never return, because Q's import is the one that hangs.
        store.markStaged(q, "Q-primary.heic", "/qp")
        store.markStaged(q, "Q-live.mov", "/ql")
        val stalled = launch { c.importReady() }
        importer.hanging.await()
        assertEquals(1, store.unconfirmedImports().size, "the in-flight import IS an unconfirmed row")

        // The rest of the burst arrives while that commit is open.
        c.onResourceStaged(r, "R-primary.heic", "/rp")
        c.onResourceStaged(r, "R-live.mov", "/rl")
        c.reconcile("event")
        c.importReady()

        assertEquals(
            0, presence.calls,
            "a burst must cost ZERO library lookups: the only unconfirmed row is the import in flight, " +
                "whose transaction is open, so the library can only answer *absent* and the gate can only " +
                "throw that answer away — 1,149 times in the measured burst",
        )
        stalled.cancel()
    }

    /** And the sweep, which is the one thing that may ask, asks once. */
    @Test
    fun the_sweep_asks_once_and_only_about_what_it_inherited() = runTest {
        val presence = CountingPresence()
        val store = InMemoryDownloadStore()
        val ref = AssetRef("DEVICE-A", "Q")
        val c = controller(FakeUnion(listOf(asset("DEVICE-A", "Q"))), store = store, presence = presence)

        c.sweepInterruptedImports()
        assertEquals(0, presence.calls, "nothing inherited → no row carries a marker → no lookup at all")

        c.reconcile("event")
        store.markStaged(ref, "Q-primary.heic", "/p")
        store.markStaged(ref, "Q-live.mov", "/l")
        store.recordCreatedLocalId(ref, "LOCAL-Q_L0_001") // what a dead process left behind
        c.sweepInterruptedImports()
        assertEquals(1, presence.calls, "one inherited row → exactly one batched lookup")
    }

    /**
     * A failure that consumed the staged files can never succeed: the library takes a resource at ingest,
     * and a resource recorded as staged is never re-downloaded. Retrying it spends a library transaction
     * per trigger forever, which is the second half of this change.
     */
    @Test
    fun an_import_whose_bytes_the_library_consumed_settles_instead_of_retrying() = runTest {
        val store = InMemoryDownloadStore()
        val importer = FakeImporter().apply { failNext = true; failConsumingResources = true }
        val ref = AssetRef("DEVICE-A", "Q")
        val c = controller(FakeUnion(listOf(asset("DEVICE-A", "Q"))), store = store, importer = importer)

        c.reconcile("event")
        store.markStaged(ref, "Q-primary.heic", "/p")
        store.markStaged(ref, "Q-live.mov", "/l")
        c.importReady()

        assertEquals(1, importer.attempted.size, "attempted once")
        assertTrue(store.isSettled(ref), "and settled, so discovery never plans it again")
        assertTrue(store.importableAssets().isEmpty(), "it is out of importable work")
        assertTrue(store.stagedResources(ref).isEmpty(), "and the rows that made it findable are gone")

        c.importReady()
        c.importReady()
        assertEquals(1, importer.attempted.size, "no later trigger attempts it again")
    }

    /** The mirror: a failure that consumed nothing is still ordinary retryable work. */
    @Test
    fun an_import_rejected_before_ingest_keeps_its_bytes_and_is_retried() = runTest {
        val store = InMemoryDownloadStore()
        val importer = FakeImporter().apply { failNext = true; failConsumingResources = false }
        val ref = AssetRef("DEVICE-A", "Q")
        val c = controller(FakeUnion(listOf(asset("DEVICE-A", "Q"))), store = store, importer = importer)

        c.reconcile("event")
        store.markStaged(ref, "Q-primary.heic", "/p")
        store.markStaged(ref, "Q-live.mov", "/l")
        c.importReady()

        assertFalse(store.isSettled(ref), "the request was rejected, not the bytes — nothing is settled")
        assertEquals(2, store.stagedResources(ref).size, "its bytes are intact")

        importer.failNext = false
        c.importReady()
        assertTrue(store.isSettled(ref), "and a later trigger imports it")
    }

    /**
     * An asset that can never arrive leaves the DENOMINATOR (design D8). Counting it pegs the download line
     * below completion forever, in a state the member can neither act on nor dismiss; the loss reaches the
     * operator through the crash-reporting sink instead of the screen.
     */
    @Test
    fun a_settled_unimportable_asset_leaves_the_download_total() = runTest {
        val store = InMemoryDownloadStore()
        val importer = FakeImporter().apply { failNext = true; failConsumingResources = true }
        val ref = AssetRef("DEVICE-A", "Q")
        val c = controller(
            FakeUnion(listOf(asset("DEVICE-A", "Q"), asset("DEVICE-A", "R"))),
            store = store, importer = importer,
        )

        c.reconcile("event")
        assertEquals(2, store.assetCount(), "both are outstanding while both can still arrive")
        store.markStaged(ref, "Q-primary.heic", "/p")
        store.markStaged(ref, "Q-live.mov", "/l")
        c.importReady()

        assertEquals(1, store.assetCount(), "the unimportable one is no longer counted as outstanding")
        assertEquals(0, store.importedCount(), "and it is emphatically not counted as arrived")
    }

    // ---- the durable-state reset (capability `ios-app-shell`, `POST /device/reset`) -----------------

    @Test
    fun reset_prunes_non_terminal_rows() = runTest {
        val store = InMemoryDownloadStore()
        val c = controller(FakeUnion(listOf(asset("DEVICE-A", "Q"))), store = store)
        c.reconcile("event")

        c.onDurableStateReset()

        assertTrue(store.pendingDownloads().isEmpty(), "the reset left non-terminal rows behind")
    }

    /**
     * A reset must NOT delete the row of an import already claimed — the whole reason this half of the
     * reset lives on the controller and holds its mutex.
     *
     * The claimed row's change block has not run. Delete it and its marker write lands on nothing, so the
     * created asset drops out of the suppression set and this device uploads back into the event a photo it
     * had just downloaded — which every other member then receives again as new. Reading the claimed set as
     * a snapshot and pruning afterwards leaves exactly that window, and the reset suspends between its
     * steps, so the window is wide.
     *
     * Written to FAIL rather than hang: if the prune is ever moved back under a lock the stalled import
     * holds, the `withTimeoutOrNull` reports a named failure instead of a stuck suite.
     */
    @Test
    fun reset_spares_a_claimed_import_while_pruning_the_rest() = runTest {
        val store = InMemoryDownloadStore()
        val importer = FakeImporter().also { it.hangFor += "Q" }
        val claimed = AssetRef("DEVICE-A", "Q")
        val unclaimed = AssetRef("DEVICE-B", "R")
        val c = controller(
            FakeUnion(listOf(asset("DEVICE-A", "Q"), asset("DEVICE-B", "R"))),
            store = store, importer = importer,
        )
        c.reconcile("event")
        // Q is staged WHOLE, so it becomes importable and the drain claims it; R is staged in part, so it
        // stays non-terminal and unclaimed — which is exactly the row a reset is meant to drop.
        store.markStaged(claimed, "Q-primary.heic", "/p")
        store.markStaged(claimed, "Q-live.mov", "/l")
        store.markStaged(unclaimed, "R-primary.heic", "/rp")

        val stalled = launch { c.importReady() }
        importer.hanging.await() // Q's transaction is open and its claim is held.

        withTimeoutOrNull(5.seconds) { c.onDurableStateReset() }
            ?: fail("the reset queued behind the stalled import — the prune is back under the platform call")

        assertEquals(
            listOf(claimed),
            store.importableAssets().map { it.ref },
            "the claimed row was pruned — its marker write would land on nothing",
        )
        assertTrue(
            store.pendingDownloads().none { it.ref == unclaimed },
            "the unclaimed non-terminal row survived the reset",
        )
        stalled.cancel()
    }

    // ---- the staged-byte backlog reclaim (capability `download-store`) ------------------------------

    /**
     * A [StagedBytes] that is its own inspection: [remaining] is the "disk" and [released] records the
     * calls. Test-local rather than the honest fake, whose own state is private to it — inspection is
     * the operator's rigging, not part of a port contract.
     */
    private class RecordingStagedBytes(
        val remaining: MutableSet<String> = mutableSetOf(),
        private val failWith: (() -> Nothing)? = null,
    ) : StagedBytes {
        val released = mutableListOf<String>()
        override fun stagingRoot(): String = "staged:/"
        override suspend fun release(paths: List<String>) {
            failWith?.invoke()
            released += paths
            remaining.removeAll(paths.toSet())
        }
    }

    @Test
    fun reclaim_frees_the_files_of_imported_assets_and_extinguishes_itself() = runTest {
        // The backlog every install accumulated before bytes were released per asset: the row is
        // IMPORTED and its resources still record a stagedPath, so the photo is stored twice — once in
        // the library and once in staging — forever.
        val store = InMemoryDownloadStore()
        val staged = RecordingStagedBytes(mutableSetOf("/p", "/l"))
        val ref = AssetRef("DEVICE-A", "Q")
        val c = controller(FakeUnion(listOf(asset("DEVICE-A", "Q"))), store = store, stagedBytes = staged)
        c.reconcile("event")
        store.markStaged(ref, "Q-primary.heic", "/p")
        store.markStaged(ref, "Q-live.mov", "/l")
        store.markImported(ref, "LOCAL-Q")

        c.releaseSettledBytes()
        assertTrue(staged.remaining.isEmpty(), "the settled asset's staged files were left on disk")

        // Self-extinguishing: releasing drops the very rows that made the work findable, so the second
        // pass is one store query answering nothing — no flag, no migration marker, no run-once state.
        val afterFirst = staged.released.size
        c.releaseSettledBytes()
        assertEquals(afterFirst, staged.released.size, "a second pass released files it no longer owned")
    }

    @Test
    fun reclaim_that_cannot_read_the_backlog_does_nothing_rather_than_throwing() = runTest {
        // It runs unconditionally on every foreground entry, so an escaping error here would take down
        // work that has nothing to do with reclaiming disk.
        val staged = RecordingStagedBytes(mutableSetOf("/p"))
        val store = object : DownloadStore by InMemoryDownloadStore() {
            override suspend fun stagedPathsOfImportedAssets(): List<String> = error("store unreadable")
        }
        val c = controller(FakeUnion(emptyList()), store = store, stagedBytes = staged)

        c.releaseSettledBytes() // must not throw

        assertTrue(staged.released.isEmpty(), "nothing may be released on an answer we never got")
    }

    @Test
    fun a_failed_release_keeps_the_rows_so_a_later_pass_can_retry() = runTest {
        // ORDER, and it is load-bearing. Release first, drop second: if the drop ran first and the
        // release then failed, the files would be orphaned with no row referencing them —
        // unreclaimably, and across launches. Failing this way round costs one retry.
        val inner = InMemoryDownloadStore()
        var dropped = false
        val store = object : DownloadStore by inner {
            override suspend fun dropResourcesOfImportedAssets() {
                dropped = true
                inner.dropResourcesOfImportedAssets()
            }
        }
        val failing = RecordingStagedBytes(failWith = { error("disk is busy") })
        val ref = AssetRef("DEVICE-A", "Q")
        val c = controller(FakeUnion(listOf(asset("DEVICE-A", "Q"))), store = store, stagedBytes = failing)
        c.reconcile("event")
        store.markStaged(ref, "Q-primary.heic", "/p")
        store.markStaged(ref, "Q-live.mov", "/l")
        store.markImported(ref, "LOCAL-Q")

        c.releaseSettledBytes() // must not throw

        assertFalse(dropped, "the rows were dropped after a release that failed — the files are orphaned")
        assertEquals(
            listOf("/p", "/l"),
            store.stagedPathsOfImportedAssets(),
            "the backlog must still be findable for the next pass",
        )
    }
}
