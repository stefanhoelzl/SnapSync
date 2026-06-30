package app.snapsync.download

import app.snapsync.downloadstore.AssetRef
import app.snapsync.downloadstore.StagedResource
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.NSURL
import platform.Photos.PHAsset
import platform.Photos.PHAssetCreationRequest
import platform.Photos.PHPhotoLibrary
import kotlin.coroutines.resume

/**
 * The iOS [PhotoLibraryImporter] (capability `photo-download`): rebuilds one foreign asset from its
 * staged resources via a single `PHAssetCreationRequest` (all resources added before the one
 * `performChanges` commit — there is no API to append to an existing asset), landing in the camera
 * roll. Role→`PHAssetResourceType`: `live`→`pairedVideo`; `primary`→`photo`/`video`/`audio` by
 * `contentType`. An unrecognised type is logged and skipped.
 *
 * Echo-suppression: the created asset's local identifier (sanitized to the upload-key `assetId` form,
 * `/`→`_`, so the upload extension's discovery matches it) is recorded via [recordCreatedLocalId]
 * **inside** the change block — before the new asset can be observed — so it is never re-uploaded.
 */
@OptIn(ExperimentalForeignApi::class)
class IosPhotoLibraryImporter(
    private val recordCreatedLocalId: (AssetRef, String) -> Unit,
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
                type to r.stagedPath
            }
        }
        if (typed.isEmpty()) return ImportResult.Failed("no importable resources for ${ref.sourceAssetId}")

        var createdLocalId: String? = null
        var rawLocalId: String? = null
        return suspendCancellableCoroutine { cont ->
            PHPhotoLibrary.sharedPhotoLibrary().performChanges(
                {
                    val request = PHAssetCreationRequest.creationRequestForAsset()
                    for ((type, path) in typed) {
                        request.addResourceWithType(type, NSURL.fileURLWithPath(path), null)
                    }
                    // Preserve the ORIGINAL capture date so the imported photo sorts by when it was
                    // taken, not when it was downloaded (default would be import time).
                    if (captureDate != null) request.setCreationDate(captureDate)
                    // INSIDE the block: capture + record the suppression handle before the commit is
                    // observable, so the upload extension never re-uploads this asset.
                    val raw = request.placeholderForCreatedAsset?.localIdentifier
                    if (raw != null) {
                        rawLocalId = raw
                        val id = raw.replace('/', '_')
                        createdLocalId = id
                        recordCreatedLocalId(ref, id)
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
