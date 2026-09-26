package app.snapsync.download

import app.snapsync.gallery.Iso8601
import app.snapsync.ios.qos.qosLabel
import app.snapsync.model.importFilename
import app.snapsync.objc.objcBoundary
import app.snapsync.model.AlbumId
import app.snapsync.model.AssetRef
import app.snapsync.model.ImportResult
import app.snapsync.ports.PhotoLibraryImporter
import app.snapsync.model.StagedResource
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSMutableArray
import platform.Foundation.NSURL
import platform.Photos.PHAssetCollection
import platform.Photos.PHAssetCollectionChangeRequest
import platform.Photos.PHAssetCreationRequest
import platform.Photos.PHAssetResourceCreationOptions
import platform.Photos.PHPhotosErrorDomain
import platform.Photos.PHPhotosErrorInvalidResource
import platform.Photos.PHPhotosErrorMissingResource
import platform.Photos.PHPhotoLibrary
import kotlin.coroutines.resume

/**
 * The iOS [PhotoLibraryImporter] (capability `receiving-photos`): rebuilds one foreign asset from its
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
 * Event album (capability `event-album`): when the caller passes an album `localIdentifier` (the
 * membership opted in and the app already created the album), the created asset is added to that album
 * **in the same commit** as its creation, so a received photo is atomically already-in-the-album. The album
 * is resolved BEFORE `performChanges`, so the change block does no fetch of its own. Absent an album id
 * (opt-out, or not yet created), the asset imports to the camera roll only. Best-effort — a
 * missing/unresolvable album never fails the import.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosPhotoLibraryImporter(
    /**
     * Writes the created-asset marker, and reports whether it landed on a row (capability
     * `receiving-photos`). `false` means the row was pruned out from under this import, so the asset this
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
     * itself (capability `receiving-photos`). Written here rather than left to the caller because a
     * completion that arrives after its requester is gone still records the import.
     */
    private val confirmCreatedLocalId: (AssetRef, String) -> Unit,
    private val log: Logger = Logger.withTag("PhotoImporter"),
) : PhotoLibraryImporter {

    override suspend fun import(
        ref: AssetRef,
        resources: List<StagedResource>,
        creationDate: String,
        album: AlbumId?,
    ): ImportResult {
        // The shared default formatter (second precision only, exactly as before) — see [Iso8601].
        val captureDate = Iso8601.parse(creationDate)
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

        // Resolved before the transaction: an album the member deleted files nothing and fails nothing.
        val collection = album?.let { id ->
            (PHAssetCollection.fetchAssetCollectionsWithLocalIdentifiers(listOf(id), null).firstObject() as? PHAssetCollection)
                .also { if (it == null) log.w { "event album $id no longer resolves — camera roll only" } }
        }
        val created = CreatedAsset()
        // The class `performChanges` is called at — read here, on the caller's thread, for the block's trace.
        val callerQos = qosLabel()
        // NOTHING BOUNDS THIS WAIT, and that is the decision, not an omission (capability
        // `receiving-photos`).
        //
        // The bound that used to sit here existed to protect `DownloadController`'s mutex: the import ran
        // under it, and the SNAPSYNC-6 field hang held it from 09:03:37 until the process died. The import
        // no longer runs under that lock, so there is nothing left for a per-import clock to protect — and
        // the wake it would otherwise bound is bounded already by the operating system's own expiry signal,
        // which ends the wake's background time at once and deliberately lets this work run on (capability
        // `sync-status`).
        //
        // Keeping a clock here would restate the mistake this capability already names: the process is
        // suspended for arbitrary spans between a change block and its completion (measured 116 s and
        // 254 s), so a wall-clock bound expires against transactions that are ALIVE — and every expiry
        // manufactured an unconfirmed row for the adjudication guard to reason about, which is the supply
        // line for SNAPSYNC-9.
        //
        // An import that never reports therefore never returns. Its ref stays claimed for the life of the
        // process, so no *absent* answer about it is ever acted on and no second asset is created; the
        // enter/exit trace around the caller is what makes it visible (capability `privacy-security`).
        return suspendCancellableCoroutine { cont ->
            PHPhotoLibrary.sharedPhotoLibrary().performChanges(
                {
                    // Contained (law "ObjC boundaries contain every throw"): a throw here — the marker's SQLite
                    // write failing on a full disk — would otherwise unwind into PhotoKit and kill the process.
                    // PhotoKit still commits whatever the block requested before it threw, and the completion
                    // below judges that commit exactly as it judges any other: no placeholder means `Failed`, and
                    // an asset that did land is reported as landed, because retrying it would duplicate it.
                    objcBoundary(log, "import.changeBlock") {
                        requestCreation(ref, typed, captureDate, collection, created, callerQos)
                    }
                },
                { success, error ->
                    objcBoundary(log, "import.completion") {
                        // Contained twice: the settle has a fallback of its own, so a throw while settling the row
                        // still resumes the importer's caller instead of leaving it waiting forever on a completion
                        // that already ran.
                        val result = objcBoundary(
                            log,
                            "import.settle",
                            ImportResult.Failed("the import's completion threw (logged above)", consumedResources = success),
                        ) {
                            settle(ref, success, error, created)
                        }
                        cont.resume(result)
                    }
                },
            )
        }
    }

/**
 * Did this failure leave the staged files behind, or has the library already taken them?
 *
 * **Measured, not inferred** (iOS 26.2, 2026-08-26, resources staged in the App Group and added with
 * `shouldMoveFile = true`): the library takes a resource's file when it INGESTS it, which happens before it
 * validates the content and before the commit. So the boundary is *what was rejected*, not *when*:
 *
 *  - `InvalidResource` (3302) — the file's CONTENT was rejected, and it was already gone, with no asset
 *    created. Nothing remains to retry from.
 *  - `MissingResource` (3303) — the file was not there to begin with. Also nothing to retry from, and the
 *    shape a process death between ingest and the marker write leaves behind.
 *  - `ChangeNotSupported` (3300) — the REQUEST was rejected before any resource was ingested; every file
 *    survived. Retrying is correct.
 *
 * Everything else answers `false`, deliberately: retrying a recoverable failure costs one library
 * transaction, while settling an unconsumed one costs the photo permanently. The asymmetry decides the
 * default, and an error this table does not know is an error whose ingest behaviour nobody has measured.
 *
 * Measured on TWO hosts, agreeing case for case: a simulator (iOS 26.2) and the SE2 (iOS 26.6), both on
 * 2026-08-26. ⏰ Re-measure at the next iOS major.
 */
private fun consumedResources(error: NSError?): Boolean {
    if (error == null || error.domain != PHPhotosErrorDomain) return false
    return error.code == PHPhotosErrorInvalidResource || error.code == PHPhotosErrorMissingResource
}

    /** What the change block learned about the asset it asked for, read by the completion. */
    private class CreatedAsset {
        var localId: String? = null
    }

    /** The change block's body: request the asset, write its marker, and file it in the event album. */
    private fun requestCreation(
        ref: AssetRef,
        typed: List<Triple<Long, String, String>>,
        captureDate: NSDate?,
        collection: PHAssetCollection?,
        created: CreatedAsset,
        callerQos: String,
    ) {
        // Traced INSIDE the block, not before the call (capability `privacy-security`).
        // The two say different things: the call returning proves only that we asked, while
        // this line proves `photolibraryd` actually began the transaction. That difference
        // decides whether an import we stop waiting for can still land — i.e. whether it
        // becomes a duplicate. Observed in SNAPSYNC-6: one import was still awaiting its
        // completion when the process ended, and the log could not say how far it had got.
        //
        // With both QoS classes: the thread that asked for the commit, and the one PhotoKit runs this block on —
        // the class the request propagated at (commits at QOS_CLASS_BACKGROUND measured 6–7× slower, SE2).
        log.i {
            "import: change block running for ${ref.sourceAssetId} (${typed.size} resource(s)); " +
                "qos caller=$callerQos block=${qosLabel()}"
        }
        val request = PHAssetCreationRequest.creationRequestForAsset()
        for ((type, path, filename) in typed) {
            // Name the resource EXPLICITLY. With a nil options argument PhotoKit names it
            // after the file we hand it — and that file is staged under its storage object
            // name, so the photo would land in the library called
            // "<assetId>-primary.heic". The name is decided in `:domain` model/
            // (`importFilename`), which is where its fallback is unit-tested.
            val options = PHAssetResourceCreationOptions().apply {
                originalFilename = filename
                // MOVE, not copy (capability `receiving-photos`). Two things follow, and both are
                // load-bearing rather than incidental:
                //
                //  - an importing asset stops holding its bytes TWICE. Under copy it does so
                //    from the commit until the client's own release (which follows the
                //    confirming write), and that window is the instant a device short of space
                //    fails: the library must find room for a full second copy right then
                //    (`PHPhotosErrorNotEnoughSpace`). A move within the same data volume is a
                //    rename and needs no second copy at all. NOTE this is per-asset and
                //    windowed — it does NOT shrink the staging backlog, which is identical
                //    either way.
                //  - the consumed file becomes the honest signal for "there is nothing left to
                //    retry with", which is what lets a doomed import SETTLE instead of being
                //    retried on every trigger forever.
                //
                // ⚠️ The library takes the file at INGEST — before it validates the content and
                // before the commit — so a failure can leave no bytes behind. That is why the
                // failure branch below reports `consumedResources`, and why nothing here may
                // assume a staged file survives an unsuccessful import.
                shouldMoveFile = true
            }
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
            // `/`→`_` MUST match `:domain:gallery`'s `normalizeAssetId` (the discovery-side
            // transform) exactly, or the discovered assetId never meets this createdLocalId
            // and the echo re-uploads. Inlined (no gallery dep here); kept identical by the
            // gallery `normalizeAssetId` contract test.
            val id = raw.replace('/', '_')
            created.localId = id
            // `false` means the row was DELETED between this import being selected and this
            // block running — the failure the prune's `protecting` set exists to prevent
            // (capability `receiving-photos`). The asset about to be created then has no
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
        // album in THIS commit (atomic — never briefly loose).
        if (collection != null && placeholder != null) {
            val members = NSMutableArray().apply { addObject(placeholder) }
            PHAssetCollectionChangeRequest.changeRequestForAssetCollection(collection)?.addAssets(members)
        }
    }

    /** The completion's verdict: settle the row against what the library reported, then say what happened. */
    private fun settle(
        ref: AssetRef,
        success: Boolean,
        error: NSError?,
        created: CreatedAsset,
    ): ImportResult {
        // The commit's own verdict, logged before it is interpreted (capability
        // `privacy-security`): a failed commit and a missing placeholder both reduce to
        // one `Failed`, and only this line tells them apart after the fact.
        val id = created.localId
        log.i { "import: commit for ${ref.sourceAssetId} success=$success created=$id error=${error?.localizedDescription}" }
        // ORDER MATTERS, and it is store-write FIRST, forget SECOND, on both branches.
        //
        // Forgetting is what re-enables the adjudicator's absent branch for this ref, so it
        // must not happen while the row still looks unconfirmed: a concurrent adjudication
        // holding an ABSENT verdict would then see `holds` go false and strip the marker off a
        // row whose asset exists. The row's own state is the interlock — settle (or clear) it
        // first, and the adjudicator's under-lock re-check sees a row that has moved on and
        // discards the verdict. Forget first and that re-check is the only thing standing
        // between us and SNAPSYNC-9; this ordering means it is a second line, not the only one.
        return if (success && id != null) {
            // The row is settled by the party that LEARNED the outcome, before anyone is
            // resumed (capability `receiving-photos`). This block is an ObjC block untied to
            // the awaiting coroutine, so it still runs when the wait was abandoned minutes
            // ago — which is what makes an abandoned import settle itself instead of waiting
            // for a later pass to ask the library what this callback already knew.
            confirmCreatedLocalId(ref, id)
            // No read-back of the created asset here. There used to be one — a `PHAsset` fetch by the new
            // identifier, purely to log its stored creation date — costing ~35 ms of synchronous library work
            // per import for a diagnostic line no decision reads. The commit verdict above still names the
            // created identifier, which is what that line carried besides the date.
            ImportResult.Imported(id)
        } else {
            // THE MIRROR of the in-block write (capability `receiving-photos`). The library has
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
            ImportResult.Failed(
                message = error?.localizedDescription ?: "performChanges failed / no placeholder",
                consumedResources = consumedResources(error),
            )
        }
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
