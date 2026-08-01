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
    private val recordCreatedLocalId: (AssetRef, String) -> Unit,
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
        return suspendCancellableCoroutine { cont ->
            PHPhotoLibrary.sharedPhotoLibrary().performChanges(
                {
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
                    val id = createdLocalId
                    if (success && id != null) {
                        logImportedDate(rawLocalId, creationDate)
                        cont.resume(ImportResult.Imported(id))
                    } else {
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
