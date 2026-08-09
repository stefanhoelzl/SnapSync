package app.snapsync.download

import app.snapsync.model.importFilename
import app.snapsync.ports.AssetRef
import app.snapsync.ports.ImportResult
import app.snapsync.ports.PhotoLibraryImporter
import app.snapsync.ports.StagedResource
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.NSMutableArray
import platform.Foundation.NSURL
import platform.Photos.PHAsset
import platform.Photos.PHAssetCollection
import platform.Photos.PHAssetCollectionChangeRequest
import platform.Photos.PHAssetCreationRequest
import platform.Photos.PHAssetResourceCreationOptions
import platform.Photos.PHPhotoLibrary
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How long one import may wait for the photo library's completion callback (capability `photo-download`).
 *
 * **What this really bounds is the LOCK.** The import runs under `DownloadController`'s mutex, so this is
 * how long a stalled photo library may block every other reconcile, import, leave, switch **and
 * `onResourceStaged`** in the process. That last one is the sharp edge: it is called from the background
 * `URLSession` delegate, inside an OS-granted wake, so blocking it can cost that wake its staging work. At
 * 30 s the exposure is six times what it was — accepted deliberately, because the alternative is worse
 * (below), and removing the lock from around the platform call is a separate change.
 *
 * ⚠️ **30 s now exceeds two of the three receipt budgets** (`ReceiptDeadlines.SILENT_PUSH` and
 * `BACKGROUND_EVENTS` are 20 s). At 5 s a stalled import was abandoned *inside* the wake with budget left
 * to make progress; at 30 s a stall is structurally guaranteed to outlive its receipt, so the OS handler
 * goes out while the transaction is still open. That is safe *because of this capability's guard* — the
 * ref is recorded as unreported, so nothing acts on the library's absent answer — and it would not have
 * been before it. Stated rather than left to be discovered: it is a real consequence of raising the bound.
 *
 * **Set from measurement of HEALTHY imports, not from the wake budget.** On an SE2 a single import takes
 * **1.0 s at 49 MB** and **5.2 s at 197 MB** — cost scales with resource size and library size — and the
 * device that reported `SNAPSYNC-9` (iPhone11,2 / iOS 18.7.9) runs roughly twice as slow, so ~10 s is a
 * legitimate worst case. 30 s is ~3× that.
 *
 * The previous value was **5 s**, derived from 250–600 ms single-HEIC imports, and it was firing on
 * healthy work: every expiry leaves an unconfirmed row, and unconfirmed rows are the entire population the
 * adjudication guard has to reason about. A bound that manufactures them is not a safety measure — it is
 * the supply line for `SNAPSYNC-9`, where 19 live markers were cleared because the library was asked about
 * transactions that were still open.
 *
 * ⏰ Re-set from the first field dump carrying the timeout line. Evidence is two devices and a synthetic
 * bench; if 197 MB imports get slower on a future iOS, this is the value to revisit.
 */
private val IMPORT_DEADLINE: Duration = 30.seconds

/**
 * The iOS [PhotoLibraryImporter] (capability `photo-download`): rebuilds one foreign asset from its
 * staged resources via a single `PHAssetCreationRequest` (all resources added before the one
 * `performChanges` commit — there is no API to append to an existing asset), landing in the camera
 * roll. Role→`PHAssetResourceType`: `live`→`pairedVideo`; `primary`→`photo`/`video`/`audio` by
 * `contentType`. An unrecognised type is logged and skipped.
 *
 * Naming: each resource is created with an explicit `originalFilename` — the capturing device's own
 * name, carried through the manifest and the union (see `importFilename`). Left to PhotoKit, the
 * resource would be named after the staged file, which is the storage object key.
 *
 * Echo-suppression: the created asset's local identifier (sanitized to the upload-key `assetId` form,
 * `/`→`_`, so the upload extension's discovery matches it) is recorded via [recordCreatedLocalId]
 * **inside** the change block — before the new asset can be observed — so it is never re-uploaded.
 *
 * Event album (capability `event-album`): when [albumId] returns a non-null album `localIdentifier` (the
 * membership opted in and the app already created the album), the created asset is added to that album
 * **in the same commit** as its creation, so a received photo is atomically already-in-the-album. Absent
 * an album id (opt-out, or not yet created), the asset imports to the camera roll only. Best-effort — a
 * missing/unresolvable album never fails the import.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosPhotoLibraryImporter(
    private val recordCreatedLocalId: (AssetRef, String) -> Unit,
    /**
     * The mirror of [recordCreatedLocalId], invoked when the library reports the change **failed**.
     * Never on a timeout — see the `TimedOut` branch.
     */
    private val clearCreatedLocalId: (AssetRef) -> Unit,
    /**
     * The **success** mirror: settle the row against the marker it already holds, from the completion
     * itself (capability `download-store`). Written here rather than left to the caller because a
     * completion that arrives after its requester is gone still records the import.
     */
    private val confirmCreatedLocalId: (AssetRef, String) -> Unit,
    /**
     * The library has reported this import's outcome, so an *absent* answer about its asset is
     * trustworthy again (capability `photo-download`).
     *
     * Invoked on **both** completion paths, success and reported failure. It matters most on failure:
     * the marker is cleared, the row becomes importable, and a later import of the same ref mints a NEW
     * marker — and a leftover entry here would gate that new import's own adjudication, stranding the
     * photo.
     */
    private val forgetUnreported: (AssetRef) -> Unit,
    private val albumId: () -> String? = { null },
    private val log: Logger = Logger.withTag("PhotoImporter"),
) : PhotoLibraryImporter {

    override suspend fun import(ref: AssetRef, resources: List<StagedResource>, creationDate: String): ImportResult {
        val captureDate = NSISO8601DateFormatter().dateFromString(creationDate)
        if (captureDate == null) log.w { "unparseable creationDate '$creationDate' for ${ref.sourceAssetId} — will default to import time" }
        val typed = resources.mapNotNull { r ->
            val type = resourceType(r.role, r.contentType)
            if (type == null) {
                log.w { "skip resource ${r.resourceKey}: unmapped role=${r.role} contentType=${r.contentType}" }
                null
            } else {
                Triple(type, r.stagedPath, importFilename(r.originalFilename, r.resourceKey))
            }
        }
        if (typed.isEmpty()) return ImportResult.Failed("no importable resources for ${ref.sourceAssetId}")

        var createdLocalId: String? = null
        var rawLocalId: String? = null
        // The wait is bounded, the library call is NOT (capability `photo-download`).
        //
        // `performChanges` returns to its caller and only this coroutine suspends — measured, not
        // assumed: in SNAPSYNC-6 one import never received its completion, yet the main thread went on
        // running for three minutes (`← onSilentPush (38ms)`, the next reconcile, a later burst). So
        // abandoning the wait frees a continuation, not a thread, and `withTimeoutOrNull` is safe here
        // in a way it would NOT be around a blocking call like the change-feed fetch, which is exactly
        // why `IosDiscovery` hops off-main instead of timing out.
        //
        // What this really rescues is the LOCK: the import runs under `DownloadController`'s mutex, and
        // the field hang held it from 09:03:37 until the process died — every later reconcile, import,
        // leave and switch in that process was queued behind it, permanently.
        return withTimeoutOrNull(IMPORT_DEADLINE) {
        suspendCancellableCoroutine { cont ->
            PHPhotoLibrary.sharedPhotoLibrary().performChanges(
                {
                    // Traced INSIDE the block, not before the call (capability `diagnostic-logging`).
                    // The two say different things: the call returning proves only that we asked, while
                    // this line proves `photolibraryd` actually began the transaction. That difference
                    // decides whether an import we stop waiting for can still land — i.e. whether it
                    // becomes a duplicate. Observed in SNAPSYNC-6: one import was still awaiting its
                    // completion when the process ended, and the log could not say how far it had got.
                    log.i { "import: change block running for ${ref.sourceAssetId} (${typed.size} resource(s))" }
                    val request = PHAssetCreationRequest.creationRequestForAsset()
                    for ((type, path, filename) in typed) {
                        // Name the resource EXPLICITLY. With a nil options argument PhotoKit names it
                        // after the file we hand it — and that file is staged under its storage object
                        // name, so the photo would land in the library called
                        // "<assetId>-primary.heic". The name is decided in `:domain` model/
                        // (`importFilename`), which is where its fallback is unit-tested.
                        val options = PHAssetResourceCreationOptions().apply { originalFilename = filename }
                        request.addResourceWithType(type, NSURL.fileURLWithPath(path), options)
                    }
                    // Preserve the ORIGINAL capture date so the imported photo sorts by when it was
                    // taken, not when it was downloaded (default would be import time).
                    if (captureDate != null) request.setCreationDate(captureDate)
                    // INSIDE the block: capture + record the suppression handle before the commit is
                    // observable, so the upload extension never re-uploads this asset.
                    val placeholder = request.placeholderForCreatedAsset
                    val raw = placeholder?.localIdentifier
                    if (raw != null) {
                        rawLocalId = raw
                        // `/`→`_` MUST match `:domain:gallery`'s `normalizeAssetId` (the discovery-side
                        // transform) exactly, or the discovered assetId never meets this createdLocalId
                        // and the echo re-uploads. Inlined (no gallery dep here); kept identical by the
                        // gallery `normalizeAssetId` contract test.
                        val id = raw.replace('/', '_')
                        createdLocalId = id
                        recordCreatedLocalId(ref, id)
                    }
                    // Event album (capability `event-album`): add the just-created asset to the event
                    // album in THIS commit (atomic — never briefly loose). Best-effort: if the album no
                    // longer resolves, import to the camera roll only.
                    val album = albumId()
                    if (album != null && placeholder != null) {
                        val collection = PHAssetCollection
                            .fetchAssetCollectionsWithLocalIdentifiers(listOf(album), null)
                            .firstObject() as? PHAssetCollection
                        if (collection != null) {
                            val members = NSMutableArray().apply { addObject(placeholder) }
                            PHAssetCollectionChangeRequest.changeRequestForAssetCollection(collection)
                                ?.addAssets(members)
                        } else {
                            log.w { "event album $album no longer resolves — camera roll only" }
                        }
                    }
                },
                { success, error ->
                    // The commit's own verdict, logged before it is interpreted (capability
                    // `diagnostic-logging`): a failed commit and a missing placeholder both reduce to
                    // one `Failed`, and only this line tells them apart after the fact.
                    log.i { "import: commit for ${ref.sourceAssetId} success=$success error=${error?.localizedDescription}" }
                    val id = createdLocalId
                    // ORDER MATTERS, and it is store-write FIRST, forget SECOND, on both branches.
                    //
                    // Forgetting is what re-enables the adjudicator's absent branch for this ref, so it
                    // must not happen while the row still looks unconfirmed: a concurrent adjudication
                    // holding an ABSENT verdict would then see `holds` go false and strip the marker off a
                    // row whose asset exists. The row's own state is the interlock — settle (or clear) it
                    // first, and the adjudicator's under-lock re-check sees a row that has moved on and
                    // discards the verdict. Forget first and that re-check is the only thing standing
                    // between us and SNAPSYNC-9; this ordering means it is a second line, not the only one.
                    if (success && id != null) {
                        // The row is settled by the party that LEARNED the outcome, before anyone is
                        // resumed (capability `download-store`). This block is an ObjC block untied to
                        // the awaiting coroutine, so it still runs when the wait was abandoned minutes
                        // ago — which is what makes an abandoned import settle itself instead of waiting
                        // for a later pass to ask the library what this callback already knew.
                        confirmCreatedLocalId(ref, id)
                        forgetUnreported(ref) // reported, and the row is terminal before we say so
                        logImportedDate(rawLocalId, creationDate)
                        cont.resume(ImportResult.Imported(id))
                    } else {
                        // THE MIRROR of the in-block write (capability `download-store`). The library has
                        // stated that this change failed, so the marker points at an asset that does not
                        // exist — clear it, or the row is skipped as "already created" on every future
                        // pass and the photo never arrives.
                        //
                        // Runs even when the wait was already abandoned: `performChanges`' completion is
                        // an ObjC block, not tied to this coroutine, so it still fires after a
                        // `TimedOut` (only `cont.resume` becomes a no-op). That is what makes an
                        // abandoned import self-correcting — a late success keeps its marker for the
                        // guard to settle, a late failure clears it here.
                        if (id != null) clearCreatedLocalId(ref)
                        forgetUnreported(ref) // reported, and the marker is gone before we say so
                        cont.resume(ImportResult.Failed(error?.localizedDescription ?: "performChanges failed / no placeholder"))
                    }
                },
            )
        }
        } ?: ImportResult.TimedOut(
            // NO clear here, deliberately (capability `photo-download`): the transaction may still
            // commit, and clearing a marker whose asset then appears is exactly what orphans it — the
            // first copy loses its suppression and is uploaded back into the event. The row stays
            // unconfirmed and the guard adjudicates it against the library on a later pass.
            "no completion from the photo library within $IMPORT_DEADLINE for ${ref.sourceAssetId}",
        )
    }

    /** Readback proof: fetch the created asset and log its actual creationDate vs the intended one. */
    private fun logImportedDate(rawLocalId: String?, intended: String) {
        val id = rawLocalId ?: return
        val asset = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(id), null).firstObject() as? PHAsset
        log.i { "imported ${id} creationDate(actual)=${asset?.creationDate?.description} intended=$intended" }
    }

    /** Map a generic role + MIME content type to the PhotoKit resource-type raw value, or null if unmapped. */
    private fun resourceType(role: String, contentType: String): Long? = when (role) {
        "live" -> 9L // pairedVideo
        "primary" -> when {
            contentType.startsWith("image/") -> 1L // photo
            contentType.startsWith("video/") -> 2L // video
            contentType.startsWith("audio/") -> 3L // audio
            else -> null
        }
        else -> null
    }
}
