package app.snapsync.world

import app.snapsync.ports.DownloadTask
import app.snapsync.ports.DownloadTransport
import app.snapsync.ports.DownloadTransportHost
import app.snapsync.ports.ImportResult
import app.snapsync.ports.ImportedAssetPresence
import app.snapsync.ports.PhotoLibraryImporter
import kotlinx.coroutines.CompletableDeferred
import app.snapsync.ports.TransferOutcome
import app.snapsync.ports.AssetRef
import app.snapsync.ports.StagedResource
import app.snapsync.model.AssetPresence
import app.snapsync.model.RawAsset
import app.snapsync.model.RawResource
import app.snapsync.model.ResourceRole
import app.snapsync.model.importFilename
import app.snapsync.model.normalizeAssetId

/**
 * The operator-driven download **execution edge** (capability `harness-world-model`): a fake
 * [DownloadTransport] the world composes the **real** [app.snapsync.feature.download.QueuedPhotoDownloadJobs] over.
 *
 * Faking here rather than at `PhotoDownloadJobs` is the point. The layer above is the orchestration — the
 * bounded in-flight window, the transfer-description codec, the URL guard, and the transfer-integrity
 * check — and the world exists so the *real* stack runs against it, faking only the edge. Faking the jobs
 * instead left every one of those untested by the world and by `:test:integration`.
 *
 * [finish] mirrors the real `URLSession` delegate exactly, including the ordering the integrity check
 * depends on: ask whether the bytes may be staged, only then stage them, and report completion either way
 * (a download's completion callback follows its finish callback whether or not anything went wrong, which
 * is what frees the window slot).
 */
class FakeDownloadTransport(
    private val host: DownloadTransportHost,
    /**
     * The world's staging "disk". Staging a transfer puts its destination here, exactly as the real
     * transport's `moveToStaging` puts bytes on disk — so a test can assert that a settled row's bytes
     * were released and an unsettled row's were not.
     */
    private val disk: MutableSet<String> = mutableSetOf(),
) : DownloadTransport {

    /** Inspection: a transfer the real jobs started through this transport. */
    class Started(val url: String, val description: String) {
        var cancelled: Boolean = false
    }

    val started = mutableListOf<Started>()

    override fun start(url: String, description: String): DownloadTask? {
        val s = Started(url, description)
        started += s
        return object : DownloadTask {
            override fun cancel() {
                s.cancelled = true
                host.onCompleted(description, "cancelled")
            }
        }
    }

    /** The transfers still awaiting a finish, de-duplicated by description. */
    fun inFlight(): List<Started> = started.filterNot { it.cancelled }.distinctBy { it.description }

    /**
     * Deliver a finish for [description], exactly as the real delegate does. A rejected [outcome] leaves
     * the resource un-staged — which *is* the world's pending-for-retry state, not a new terminal one.
     */
    fun finish(description: String, outcome: TransferOutcome = HEALTHY) {
        if (host.accepts(description, outcome)) {
            host.destinationFor(description)?.let { disk += it; host.onStaged(description, it) }
        }
        host.onCompleted(description, null)
    }

    companion object {
        /** An ordinary healthy transfer: `200`, no declared length — what staging assumes by default. */
        val HEALTHY: TransferOutcome =
            TransferOutcome(statusCode = 200, expectedBytes = -1L, receivedBytes = 1_024L)
    }
}

/**
 * A [PhotoLibraryImporter] that imports into the in-memory [gallery] (capability
 * `harness-world-model`) so **echo-suppression** is exercised end to end: the imported asset enters
 * gallery enumeration with an `assetId` byte-identical to the `createdLocalId` recorded in the download
 * store, so the own-device upload cycle (which reads `suppressedLocalIds()`) never re-uploads it.
 *
 * **Two-phase, exactly like the real adapter**: it records the created-asset marker through
 * [recordCreatedLocalId] *before* it returns anything, because that is what the iOS importer does inside
 * its `performChanges` change block — the marker precedes the commit, so a created asset always has one.
 * Without that ordering the world cannot reach the state this capability's hard cases live in: a marker
 * written, and the confirmation never arriving.
 *
 * The levers ([failNextImport], [failNextImportAfterCreating], [suspendNextImport]) are what let a test
 * reach each of the ways an import can end badly — including the one that has no ending at all, where the
 * transaction is held open while other triggers run. They live here rather than in
 * `:adapter:generic:fake` because this class is world rigging, outside the fake-honesty gate.
 */
class FakePhotoLibraryImporter(
    private val gallery: WorldGallery,
    /**
     * The marker write, mirroring the real adapter's constructor lambda.
     *
     * **Required, with no default.** A no-op default makes an importer that never records a marker look
     * like a working one: the row stays importable, so every later pass imports the asset AGAIN while
     * reporting success — an unbounded duplicate generator presented as a healthy path. That is exactly
     * the failure `DownloadStore.markImported` exists to absorb, and a fixture must not be the thing that
     * hides it.
     */
    private val recordCreatedLocalId: (AssetRef, String) -> Boolean,
    /** The mirror, invoked when a change is reported as failed *after* the marker was written. */
    private val clearCreatedLocalId: (AssetRef, String) -> Unit,
    /** The success mirror: the completion settles the row itself (capability `download-store`). */
    private val confirmCreatedLocalId: (AssetRef, String) -> Unit,
) : PhotoLibraryImporter {

    /** Inspection: the source refs imported, one entry per created asset (so a repeat shows up twice). */
    val imported = mutableListOf<AssetRef>()

    /** How many assets this importer has created per ref — what makes a repeat mint a fresh identifier. */
    private val attempts = mutableMapOf<AssetRef, Int>()

    /** Failure lever: the next import returns `Failed` **before** creating anything (cleared after one firing). */
    var failNextImport: Boolean = false

    /**
     * Failure lever: the next import writes its marker, creates the asset, and then reports `Failed` —
     * the real adapter's "commit reported failure after the block ran" path, where the mirror clears the
     * marker again. Cleared after one firing.
     */
    var failNextImportAfterCreating: Boolean = false

    /**
     * The lever the `SNAPSYNC-9` guard is about: the next import writes its marker — the change block ran
     * — and then **suspends before the commit lands**, holding the transaction open until the test
     * resolves it.
     *
     * Suspending, rather than returning a report about an abandonment, is the whole point. While it is
     * parked the gallery does not hold the asset, so the photo library answers *absent* about a
     * transaction that is still open — honest, and catastrophic to act on — and the ref is claimed. A
     * lever that merely *returned* that state let a test observe the aftermath; only this one lets a
     * second trigger run **while** the transaction is live, which is the interleaving the defect occurs
     * in and the one the download controller's claim exists to close.
     *
     * Nothing is settled while it is parked: no gallery asset, no clear (it may still land), no confirm.
     * All three are things the completion callback does, and supplying any of them would erase the very
     * state under test. Cleared after one firing.
     */
    var suspendNextImport: Boolean = false

    /**
     * The same hold, one step later: the marker is written, the commit **has** landed (the asset is in the
     * gallery), and only the report is missing — so a presence lookup answers *present* about it.
     *
     * Distinct from [suspendNextImport], where the library answers *absent* about a transaction that is
     * still open. Both leave an unconfirmed row and both stay claimed; only this one is recoverable by
     * adjudication, and it is the shape a process death leaves behind. Cleared after one firing.
     */
    var suspendNextImportAfterCommit: Boolean = false

    /** Signalled by [resumeSuspendedImport] to release a parked import with its chosen outcome. */
    private var parked: CompletableDeferred<Boolean>? = null

    /**
     * Completes once an import has actually parked, so a test can await the live transaction rather than
     * guessing at a delay — a race here would make every test built on this lever flaky.
     *
     * **Replaced on every park**, because a single completed deferred makes the lever single-shot in the
     * worst way: a second `await` would return the STALE ref immediately, the test would drive its
     * triggers before the second import had parked, and the resume would then find nothing suspended.
     */
    var suspendedImport: CompletableDeferred<AssetRef> = CompletableDeferred()
        private set

    /**
     * Deliver the parked import's outcome, driving the real completion path for it: on [succeeded] the
     * asset lands and the row is settled against the marker it holds; otherwise that marker is cleared.
     */
    fun resumeSuspendedImport(succeeded: Boolean) {
        val gate = parked ?: error("no import is suspended")
        parked = null
        gate.complete(succeeded)
    }

    /**
     * How many times ONE ref may be imported before this importer raises (capability
     * `harness-world-model`).
     *
     * An unbounded re-selection of one ref is a live-lock, and a live-lock in a test is a HANG — which
     * names no defect and proves nothing. The cap converts it into an assertion failure that names the
     * count, so removing the drain's attempted-set produces a red test rather than a stuck one.
     */
    var attemptCap: Int = 50

    override suspend fun import(
        ref: AssetRef,
        resources: List<StagedResource>,
        creationDate: String,
    ): ImportResult {
        // COUNTED FIRST, before any early return, so a failed attempt still consumes an identifier.
        //
        // This ordering is the fix for a test that lied. When the counter sat below the `failNextImport`
        // return, a failed import left it at zero, so the RE-import that followed a wrongly-cleared marker
        // minted `imported-<device>-<asset>` — byte-identical to the marker a test had planted by hand.
        // The assertion "the marker survived" then passed just as happily when the marker had been
        // destroyed and a SECOND asset created under the same identifier, which is precisely the duplicate
        // the capability exists to prevent. A test cannot observe a duplicate through an identifier that
        // repeats, so no identifier here may repeat.
        //
        // Identifiers alone are still not the whole defence: assert on the NUMBER of assets created
        // ([imported], or the gallery) rather than only on a marker's value, because creating the second
        // asset IS the harm.
        val attempt = attempts.getOrElse(ref) { 0 } + 1
        attempts[ref] = attempt
        check(attempt <= attemptCap) {
            "imported ${ref.sourceAssetId} $attempt times (cap $attemptCap) — the drain is live-locking " +
                "on one ref instead of offering it once"
        }
        if (failNextImport) {
            failNextImport = false
            return ImportResult.Failed("forced")
        }
        // The suppression handle: byte-identical to the enumerator's normalized `assetId` form, so the
        // download store's `suppressedLocalIds()` matches the enumerated resource's `assetId`.
        //
        // **A repeat import of the same ref mints a DIFFERENT id**, because PhotoKit does: every
        // `PHAssetCreationRequest` creates a new asset with a new `localIdentifier`. Without that, a
        // second import would land on the first one's handle and the orphaning this capability exists to
        // prevent would be unreproducible in the world. The first import keeps the bare, readable form so
        // existing expectations still read `imported-<device>-<asset>`.
        val suffix = if (attempt == 1) "" else "-$attempt"
        val createdLocalId = normalizeAssetId("imported-${ref.sourceDeviceId}-${ref.sourceAssetId}$suffix")
        // INSIDE the change block, before anything is observable — the real adapter's ordering. The
        // Boolean is the adapter's too: `false` means the row was pruned out from under this import, so
        // the asset about to be created will have no suppression handle at all (capability
        // `download-store`). The real adapter logs an error; the world raises, because a test that reaches
        // this state has hit the defect the prune's `protecting` set exists to prevent.
        check(recordCreatedLocalId(ref, createdLocalId)) {
            "marker $createdLocalId for ${ref.sourceAssetId} landed on NO ROW — its row was pruned mid-import"
        }
        if (suspendNextImport) {
            suspendNextImport = false
            // Park with the marker written and NOTHING else: no gallery asset (the commit has not
            // landed), no clear (it may still land), no confirm (nothing reported). Exactly the state the
            // field defect was adjudicated in — and, because this suspends rather than returns, the state
            // stays open while the test drives other triggers against it.
            val gate = CompletableDeferred<Boolean>()
            parked = gate
            if (suspendedImport.isCompleted) suspendedImport = CompletableDeferred()
            suspendedImport.complete(ref)
            if (!gate.await()) {
                // The completion reports failure: the mirror undoes the marker it wrote, and no asset ever
                // existed, so the gallery stays untouched.
                clearCreatedLocalId(ref, createdLocalId)
                return ImportResult.Failed("suspended import resumed as failed")
            }
            // Falls through to the ordinary success path below: the asset lands and the row is settled
            // against the marker it already holds, which is what the real completion does.
        }
        // Import into the gallery so the asset becomes enumerable (and thus visible to — but suppressed
        // from — the upload cycle).
        val newAsset = RawAsset(
            assetId = createdLocalId,
            creationDate = creationDate,
            rawResources = resources.map { staged ->
                RawResource(
                    role = if (staged.role == ResourceRole.LIVE.wire) ResourceRole.LIVE else ResourceRole.PRIMARY,
                    mimeContentType = staged.contentType,
                    // The SAME naming rule the iOS importer applies (`importFilename`), so the world
                    // cannot show a human name where a device would show a storage key.
                    originalFilename = importFilename(staged.originalFilename, staged.resourceKey),
                    handle = Unit,
                )
            },
        )
        gallery.set(gallery.current() + newAsset)
        imported += ref
        if (suspendNextImportAfterCommit) {
            suspendNextImportAfterCommit = false
            // The asset EXISTS and the row is unconfirmed: no confirm, no clear. The library will answer
            // *present* about it, which is the one verdict that can settle a row whose completion was lost.
            val gate = CompletableDeferred<Boolean>()
            parked = gate
            if (suspendedImport.isCompleted) suspendedImport = CompletableDeferred()
            suspendedImport.complete(ref)
            if (!gate.await()) {
                clearCreatedLocalId(ref, createdLocalId)
                return ImportResult.Failed("suspended import resumed as failed")
            }
        }
        // The commit landed. What the platform reports about it is the lever's business.
        if (failNextImportAfterCreating) {
            failNextImportAfterCreating = false
            // The mirror: an OBSERVED failure undoes the marker it wrote. (The gallery keeps the asset,
            // which is the honest shape — `performChanges` reporting failure after the block ran is
            // exactly the case where the store must not keep pointing at something that may not exist.)
            clearCreatedLocalId(ref, createdLocalId)
            return ImportResult.Failed("forced after creating")
        }
        // The completion's own write, mirroring the real adapter: settle the row against the marker it
        // holds, BEFORE returning, because on device it runs in a callback that fires whether or not
        // anything is still awaiting it.
        confirmCreatedLocalId(ref, createdLocalId)
        return ImportResult.Imported(createdLocalId)
    }
}


/**
 * The world's [ImportedAssetPresence]: the in-memory gallery **is** the photo library, so presence is
 * simply whether the gallery holds an asset with that id (capability `harness-world-model`).
 *
 * Backed by the real gallery rather than a settable set on purpose — an import that landed is visible
 * here for the same reason it is visible to upload discovery, so a test cannot accidentally assert
 * against a presence answer the rest of the world disagrees with.
 *
 * [readable] models the grant's *other* question: with it false every answer is `UNKNOWN`, which is what
 * a partial or revoked photo grant produces and what must never be confused with `ABSENT`.
 */
class WorldAssetPresence(private val gallery: WorldGallery) : ImportedAssetPresence {

    /** Operator lever: make the library unanswerable, as a partial or revoked grant does. */
    var readable: Boolean = true

    override suspend fun presence(localIds: Set<String>): Map<String, AssetPresence> {
        if (!readable) return localIds.associateWith { AssetPresence.UNKNOWN }
        val library = gallery.current().mapTo(mutableSetOf()) { it.assetId }
        return localIds.associateWith {
            if (it in library) AssetPresence.PRESENT else AssetPresence.ABSENT
        }
    }
}
