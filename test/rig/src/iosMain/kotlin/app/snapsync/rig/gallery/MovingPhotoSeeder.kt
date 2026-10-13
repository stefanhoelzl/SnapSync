package app.snapsync.rig.gallery

import app.snapsync.rig.MovingPhoto
import app.snapsync.rig.refused
import co.touchlab.kermit.Logger
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSISO8601DateFormatter
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.Photos.PHAssetCreationRequest
import platform.Photos.PHAssetResourceCreationOptions
import platform.Photos.PHAssetResourceTypePairedVideo
import platform.Photos.PHAssetResourceTypePhoto
import platform.Photos.PHPhotoLibrary

/**
 * `POST /device/gallery/add-moving` where the photo library is REAL on iPhone: ONE asset made of the camera's own
 * files — a still, and with a movie beside it a Live Photo, the two paired by the content identifier the camera wrote
 * into both. Photos judges the pair as it judges the Camera app's: a pair it will not join fails the request, and that
 * answer is the point, since the share journey's Live Photo must be one Photos accepts.
 *
 * Blocking (`performChangesAndWait`), off the main lane like every device command. Answers the asset's local id.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
fun addLivePhoto(log: Logger, photo: MovingPhoto): String {
    val stills = photo.files.filterNot { it.isMovie }
    val movies = photo.files.filter { it.isMovie }
    if (stills.size != 1 || movies.size > 1) {
        val names = photo.files.map { it.name }
        refused("an iPhone photo is one still, with at most one paired movie; was $names")
    }
    val date = NSISO8601DateFormatter().dateFromString(photo.date)
        ?: refused("date must be an ISO-8601 instant, was '${photo.date}'")
    val dir = NSTemporaryDirectory() + "add-moving-" + NSUUID().UUIDString + "/"
    NSFileManager.defaultManager.createDirectoryAtPath(
        dir,
        withIntermediateDirectories = true,
        attributes = null,
        error = null,
    )
    val parts = (stills + movies).map { file ->
        val path = dir + file.name
        val bytes = file.bytes()
        val data = bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }
        check(data.writeToFile(path, atomically = true)) { "could not stage ${file.name}" }
        Triple(if (file.isMovie) PHAssetResourceTypePairedVideo else PHAssetResourceTypePhoto, path, file.name)
    }
    var id: String? = null
    try {
        val ok = PHPhotoLibrary.sharedPhotoLibrary().performChangesAndWait(
            changeBlock = {
                val request = PHAssetCreationRequest.creationRequestForAsset()
                parts.forEach { (type, path, name) ->
                    val options = PHAssetResourceCreationOptions().apply { originalFilename = name }
                    request.addResourceWithType(type, fileURL = NSURL.fileURLWithPath(path), options = options)
                }
                request.setCreationDate(date)
                id = request.placeholderForCreatedAsset?.localIdentifier
            },
            error = null,
        )
        val created = id
        if (!ok || created == null) refused("Photos refused the photo ${parts.map { it.third }}")
        log.i { "added ${if (movies.isEmpty()) "a photo" else "a Live Photo"} $created from ${parts.map { it.third }}" }
        return created
    } finally {
        NSFileManager.defaultManager.removeItemAtPath(dir, error = null)
    }
}
