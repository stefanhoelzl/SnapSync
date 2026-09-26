package app.snapsync.world

import app.snapsync.ports.DownloadTask
import app.snapsync.ports.DownloadTransport
import app.snapsync.ports.DownloadTransportHost
import app.snapsync.model.ImportResult
import app.snapsync.ports.PhotoLibraryImporter
import app.snapsync.fake.LibraryChangeAnswers
import app.snapsync.fake.inMemoryPhotoLibraryImporter
import kotlinx.coroutines.CompletableDeferred
import app.snapsync.model.TransferOutcome
import app.snapsync.model.AlbumId
import app.snapsync.model.AssetRef
import app.snapsync.model.StagedResource

/**
 * The operator-driven download **execution edge** (`docs/testing.md`): a fake
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
    /**
     * The operating system's session: the transfers it holds for this app. Shared across a relaunch — a
     * relaunched process's transport finds the transfers the dead one started, as a background `URLSession` does.
     */
    val started: MutableList<Started> = mutableListOf(),
) : DownloadTransport {

    /** Inspection: a transfer the real jobs started through this transport. */
    class Started(val url: String, val description: String) {
        var cancelled: Boolean = false
    }

    /** `null` for a URL that is not one, as the real transport answers — never a throw (`DownloadTransportContract`). */
    override fun start(url: String, description: String): DownloadTask? {
        if (url.isBlank()) return null
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
 * The world's rigging around the honest `:adapter:generic:fake` [inMemoryPhotoLibraryImporter]: the import
 * itself — the two-phase marker, the fresh identifier per creation, the asset landing in the gallery — is the
 * honest fake's, the one `PhotoLibraryImporterContract` holds to `IosPhotoLibraryImporter`. What lives here
 * is the operator's script for how the library ANSWERS a change, supplied as the fake's
 * [LibraryChangeAnswers], plus the inspection a test reads.
 *
 * The levers ([failNextImport], [failNextImportAfterCreating], [suspendNextImport],
 * [suspendNextImportAfterCommit]) are what let a test reach each of the ways an import can end badly —
 * including the one that has no ending at all, where the transaction is held open while other triggers run.
 */
class FakePhotoLibraryImporter(
    gallery: WorldGallery,
    /**
     * The marker write, mirroring the real adapter's constructor lambda.
     *
     * **Required, with no default.** A no-op default makes an importer that never records a marker look
     * like a working one: the row stays importable, so every later pass imports the asset AGAIN while
     * reporting success — an unbounded duplicate generator presented as a healthy path. That is exactly
     * the failure `DownloadStore.markImported` exists to absorb, and a fixture must not be the thing that
     * hides it.
     */
    recordCreatedLocalId: (AssetRef, String) -> Boolean,
    /** The mirror, invoked when a change is reported as failed *after* the marker was written. */
    clearCreatedLocalId: (AssetRef, String) -> Unit,
    /** The success mirror: the completion settles the row itself (capability `receiving-photos`). */
    confirmCreatedLocalId: (AssetRef, String) -> Unit,
) : PhotoLibraryImporter {

    /** Inspection: the source refs imported, one entry per created asset (so a repeat shows up twice). */
    val imported = mutableListOf<AssetRef>()

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
     * Cleared after one firing.
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
     * `docs/testing.md`).
     *
     * An unbounded re-selection of one ref is a live-lock, and a live-lock in a test is a HANG — which
     * names no defect and proves nothing. The cap converts it into an assertion failure that names the
     * count, so removing the drain's attempted-set produces a red test rather than a stuck one.
     */
    var attemptCap: Int = 50

    private val attempts = mutableMapOf<AssetRef, Int>()

    /** Parks until [resumeSuspendedImport]; answers the failure message when resumed as failed. */
    private suspend fun park(ref: AssetRef): String? {
        val gate = CompletableDeferred<Boolean>()
        parked = gate
        if (suspendedImport.isCompleted) suspendedImport = CompletableDeferred()
        suspendedImport.complete(ref)
        return if (gate.await()) null else "suspended import resumed as failed"
    }

    /** The operator's script for how the library answers each change. */
    private val answers = object : LibraryChangeAnswers {
        override suspend fun beforeChange(ref: AssetRef): String? {
            if (!failNextImport) return null
            failNextImport = false
            return "forced"
        }

        override suspend fun beforeCommit(ref: AssetRef): String? {
            if (!suspendNextImport) return null
            suspendNextImport = false
            return park(ref)
        }

        override suspend fun afterCommit(ref: AssetRef): String? {
            imported += ref
            if (suspendNextImportAfterCommit) {
                suspendNextImportAfterCommit = false
                park(ref)?.let { return it }
            }
            if (!failNextImportAfterCreating) return null
            failNextImportAfterCreating = false
            return "forced after creating"
        }
    }

    private val honest: PhotoLibraryImporter = inMemoryPhotoLibraryImporter(
        library = gallery.cell,
        recordCreatedLocalId = recordCreatedLocalId,
        clearCreatedLocalId = clearCreatedLocalId,
        confirmCreatedLocalId = confirmCreatedLocalId,
        answers = answers,
    )

    override suspend fun import(
        ref: AssetRef,
        resources: List<StagedResource>,
        creationDate: String,
        album: AlbumId?,
    ): ImportResult {
        val attempt = attempts.getOrElse(ref) { 0 } + 1
        attempts[ref] = attempt
        check(attempt <= attemptCap) {
            "imported ${ref.sourceAssetId} $attempt times (cap $attemptCap) — the drain is live-locking " +
                "on one ref instead of offering it once"
        }
        return honest.import(ref, resources, creationDate, album)
    }
}


