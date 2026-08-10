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
    /**
     * Writes the created-asset marker, and reports whether it landed on a row (capability
     * `download-store`). `false` means the row was pruned out from under this import, so the asset this
     * block is creating will have no suppression handle at all — logged as an error below, because it is
     * the only evidence the prune's protection failed.
     */
    private val recordCreatedLocalId: (AssetRef, String) -> Boolean,
    /**
     * The mirror of [recordCreatedLocalId], invoked when the library reports the change **failed**.
     * Guarded on the marker in the store's own write, so a report arriving after the row moved on clears
     * nothing.
     */
    private val clearCreatedLocalId: (AssetRef, String) -> Unit,
    /**
     * The **success** mirror: settle the row against the marker it already holds, from the completion
     * itself (capability `download-store`). Written here rather than left to the caller because a
     * completion that arrives after its requester is gone still records the import.
     */
    private val confirmCreatedLocalId: (AssetRef, String) -> Unit,
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
        // NOTHING BOUNDS THIS WAIT, and that is the decision, not an omission (capability
        // `photo-download`).
        //
        // The bound that used to sit here existed to protect `DownloadController`'s mutex: the import ran
        // under it, and the SNAPSYNC-6 field hang held it from 09:03:37 until the process died. The import
        // no longer runs under that lock, so there is nothing left for a per-import clock to protect — and
        // the wake it would otherwise bound is bounded already by `OsReceipt`, which releases the OS
        // handler on its own deadline and deliberately lets the work run on (capability `ios-app-shell`).
        //
        // Keeping a clock here would restate the mistake this capability already names: the process is
        // suspended for arbitrary spans between a change block and its completion (measured 116 s and
        // 254 s), so a wall-clock bound expires against transactions that are ALIVE — and every expiry
        // manufactured an unconfirmed row for the adjudication guard to reason about, which is the supply
        // line for SNAPSYNC-9.
        //
        // An import that never reports therefore never returns. Its ref stays claimed for the life of the
        // process, so no *absent* answer about it is ever acted on and no second asset is created; the
        // enter/exit trace around the caller is what makes it visible (capability `diagnostic-logging`).
        return suspendCancellableCoroutine { cont ->
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
                        // `false` means the row was DELETED between this import being selected and this
                        // block running — the failure the prune's `protecting` set exists to prevent
                        // (capability `download-store`). The asset about to be created then has no
                        // suppression handle at all, so this device uploads a downloaded photo back into
                        // someone else's event days later. Logged at Error so it reaches Bugsink: this
                        // line is the ONLY evidence the protection failed, and without it the failure is
                        // visible solely through its damage.
                        if (!recordCreatedLocalId(ref, id)) {
                            log.e {
                                "import: marker $id for ${ref.sourceAssetId} landed on NO ROW — its row was " +
                                    "pruned mid-import, so the created asset has no suppression handle"
                            }
                        }
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
                        logImportedDate(rawLocalId, creationDate)
                        cont.resume(ImportResult.Imported(id))
                    } else {
                        // THE MIRROR of the in-block write (capability `download-store`). The library has
                        // stated that this change failed, so the marker points at an asset that does not
                        // exist — clear it, or the row is skipped as "already created" on every future
                        // pass and the photo never arrives.
                        //
                        // Runs whether or not anything is still awaiting it: `performChanges`' completion
                        // is an ObjC block, not tied to this coroutine, so it fires even after the
                        // requester is gone (only `cont.resume` becomes a no-op). That is what makes an
                        // import whose requester died self-correcting — a late success keeps its marker
                        // for the guard to settle, a late failure clears it here.
                        if (id != null) clearCreatedLocalId(ref, id)
                        cont.resume(ImportResult.Failed(error?.localizedDescription ?: "performChanges failed / no placeholder"))
                    }
                },
            )
        }
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
