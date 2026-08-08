package app.snapsync.world

import app.snapsync.ports.DownloadTask
import app.snapsync.ports.DownloadTransport
import app.snapsync.ports.DownloadTransportHost
import app.snapsync.ports.ImportResult
import app.snapsync.ports.ImportedAssetPresence
import app.snapsync.ports.PhotoLibraryImporter
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
 * The levers ([failNextImport], [abandonNextImport], [failNextImportAfterCreating]) are what let a test
 * reach each of the three ways an import can end badly. They live here rather than in
 * `:adapter:generic:fake` because this class is world rigging, outside the fake-honesty gate.
 */
class FakePhotoLibraryImporter(
    private val gallery: WorldGallery,
    /**
     * The marker write, mirroring the real adapter's constructor lambda. Defaults to a no-op so existing
     * world construction keeps working; `World` binds it to the download store.
     */
    private val recordCreatedLocalId: (AssetRef, String) -> Unit = { _, _ -> },
    /** The mirror, invoked when a change is reported as failed *after* the marker was written. */
    private val clearCreatedLocalId: (AssetRef) -> Unit = { },
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
     * Crash lever: the next import writes its marker and creates the asset in the gallery, then reports
     * [ImportResult.TimedOut] without ever confirming — the shape a process death or an abandoned wait
     * leaves behind. The asset exists; the row stays unconfirmed. Cleared after one firing.
     */
    var abandonNextImport: Boolean = false

    override suspend fun import(
        ref: AssetRef,
        resources: List<StagedResource>,
        creationDate: String,
    ): ImportResult {
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
        val attempt = attempts.getOrElse(ref) { 0 } + 1
        attempts[ref] = attempt
        val suffix = if (attempt == 1) "" else "-$attempt"
        val createdLocalId = normalizeAssetId("imported-${ref.sourceDeviceId}-${ref.sourceAssetId}$suffix")
        // INSIDE the change block, before anything is observable — the real adapter's ordering.
        recordCreatedLocalId(ref, createdLocalId)
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
        // The commit landed. What the platform reports about it is the lever's business.
        if (failNextImportAfterCreating) {
            failNextImportAfterCreating = false
            // The mirror: an OBSERVED failure undoes the marker it wrote. (The gallery keeps the asset,
            // which is the honest shape — `performChanges` reporting failure after the block ran is
            // exactly the case where the store must not keep pointing at something that may not exist.)
            clearCreatedLocalId(ref)
            return ImportResult.Failed("forced after creating")
        }
        if (abandonNextImport) {
            abandonNextImport = false
            // Marker written, asset created, confirmation never delivered — and deliberately NO clear:
            // the transaction may still commit, so clearing here is what orphans the created asset.
            return ImportResult.TimedOut("forced abandonment")
        }
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
