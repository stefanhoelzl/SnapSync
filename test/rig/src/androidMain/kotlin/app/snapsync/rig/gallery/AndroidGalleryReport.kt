package app.snapsync.rig.gallery

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import app.snapsync.compose.AppCore
import app.snapsync.levers.CensusView
import app.snapsync.levers.GalleryView

/**
 * `GET /device/gallery` where the photo library is REAL on Android — the counterpart of the iOS app host's PhotoKit
 * read: the policy half through the app's own permission-aware candidate seam, never a second walk, and the census
 * straight off MediaStore with no policy at all, as the iOS census fetches with no predicate — so an exclusion the
 * policy makes is visible as the difference.
 */
fun androidGalleryReader(core: () -> AppCore, context: Context): suspend (String?, Boolean, Boolean) -> GalleryView =
    { cutoff, resources, includesUpload ->
        val report = GalleryReport(
            candidates = core().candidates,
            grant = { core().photoPermission.value.name },
            census = { mediaStoreCensus(context) },
        )
        report.read(cutoff, resources, includesUpload)
    }

/**
 * The member's default gallery as MediaStore holds it: every image and video under `DCIM`, on every volume. A
 * screenshot or screen recording is counted by the folder the platform files it in, the only mark MediaStore keeps.
 * A motion photo has no mark there at all, so the moving count reads each image's own bytes ([isMotionPhoto]).
 */
private fun mediaStoreCensus(context: Context): CensusView {
    val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
    val collections = listOf(images, MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL))
    val items = collections.flatMap { uri ->
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.RELATIVE_PATH),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("DCIM/%"),
            null,
        )?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(Triple(uri, cursor.getLong(0), cursor.getString(1).orEmpty())) }
        }.orEmpty()
    }
    val paths = items.map { it.third }
    val moving = items.count { (uri, id, _) ->
        uri == images && runCatching {
            context.contentResolver.openInputStream(ContentUris.withAppendedId(uri, id))?.use { isMotionPhoto(it.readBytes()) }
        }.getOrNull() == true
    }
    return CensusView(
        total = paths.size.toLong(),
        screenshots = paths.count { "Screenshots" in it }.toLong(),
        screenRecordings = paths.count { "Screen record" in it || "ScreenRecord" in it }.toLong(),
        moving = moving.toLong(),
    )
}
