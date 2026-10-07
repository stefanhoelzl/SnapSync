package app.snapsync.rig.gallery

import android.content.Context
import android.provider.MediaStore
import app.snapsync.compose.AppCore
import app.snapsync.rig.CensusView
import app.snapsync.rig.GalleryView
import kotlinx.serialization.json.Json

/**
 * `GET /device/gallery` where the photo library is REAL on Android — the counterpart of the iOS app host's PhotoKit
 * read: the policy half through the app's own permission-aware candidate seam, never a second walk, and the census
 * straight off MediaStore with no policy at all, as the iOS census fetches with no predicate — so an exclusion the
 * policy makes is visible as the difference.
 */
fun androidGalleryReader(core: () -> AppCore, context: Context): suspend (String?, Boolean, Boolean) -> String =
    { cutoff, resources, includesUpload ->
        val report = GalleryReport(
            candidates = core().candidates,
            grant = { core().photoPermission.value.name },
            census = { mediaStoreCensus(context) },
        )
        json.encodeToString(GalleryView.serializer(), report.read(cutoff, resources, includesUpload))
    }

/**
 * The member's default gallery as MediaStore holds it: every image and video under `DCIM`, on every volume. A
 * screenshot or screen recording is counted by the folder the platform files it in, the only mark MediaStore keeps.
 */
private fun mediaStoreCensus(context: Context): CensusView {
    val collections = listOf(
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL),
    )
    val paths = collections.flatMap { uri ->
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("DCIM/%"),
            null,
        )?.use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0).orEmpty()) } }.orEmpty()
    }
    return CensusView(
        total = paths.size.toLong(),
        screenshots = paths.count { "Screenshots" in it }.toLong(),
        screenRecordings = paths.count { "Screen record" in it || "ScreenRecord" in it }.toLong(),
    )
}

private val json = Json {
    encodeDefaults = true
    prettyPrint = true
}
