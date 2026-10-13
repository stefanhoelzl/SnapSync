package app.snapsync.rig.gallery

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import app.snapsync.motionphoto.locateMotionVideo
import app.snapsync.rig.MovingPhoto
import app.snapsync.rig.refused
import co.touchlab.kermit.Logger
import java.time.Instant

/**
 * `POST /device/gallery/add-moving` where the photo library is REAL on Android: ONE motion photo — a camera's single
 * file, still and video — inserted into MediaStore's `DCIM/Camera` byte for byte, as the app's own item (`adb push` is
 * hidden from the app; `seedMediaStore` says why). Android's gallery has no Live Photo, so a still with a movie is
 * refused: two files are two photos here.
 *
 * The capture date is set AFTER the item is published, never into the file: MediaStore re-derives `DATE_TAKEN` from
 * the file's EXIF on publish, and rewriting a motion photo's EXIF rewrites the file the test is about. Answers the
 * item's MediaStore id.
 */
fun addMotionPhoto(context: Context, log: Logger, photo: MovingPhoto): String {
    val file = photo.files.singleOrNull()?.takeUnless { it.isMovie }
        ?: refused("an Android motion photo is one image file; was ${photo.files.map { it.name }}")
    val taken = runCatching { Instant.parse(photo.date) }.getOrNull()
        ?: refused("date must be an ISO-8601 instant, was '${photo.date}'")
    val bytes = file.bytes()
    if (!isMotionPhoto(bytes)) refused("${file.name} is no motion photo: no video where its XMP says")
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
        val heic = file.name.lowercase().endsWith(".heic")
        put(MediaStore.MediaColumns.MIME_TYPE, if (heic) "image/heic" else "image/jpeg")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera/")
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri: Uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        ?: refused("MediaStore refused to insert ${file.name}")
    checkNotNull(resolver.openOutputStream(uri)) { "no stream for $uri" }.use { it.write(bytes) }
    check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) {
        "publishing ${file.name} failed"
    }
    val dated = ContentValues().apply { put(MediaStore.MediaColumns.DATE_TAKEN, taken.toEpochMilli()) }
    check(resolver.update(uri, dated, null, null) == 1) { "dating ${file.name} failed" }
    log.i { "added motion photo ${file.name} (${bytes.size} B) as $uri, taken $taken" }
    return uri.lastPathSegment.orEmpty()
}

/**
 * Whether [file] is a motion photo a gallery app plays: an XMP packet anywhere in it (a JPEG's APP1 or a HEIC's `mime`
 * item alike) whose `MotionPhoto` item, or legacy `MicroVideoOffset`, points at a video at the file's end.
 */
fun isMotionPhoto(file: ByteArray): Boolean {
    val text = String(file, Charsets.ISO_8859_1)
    val start = text.indexOf("<x:xmpmeta")
    val end = text.indexOf("</x:xmpmeta>", start + 1)
    if (start < 0 || end < 0) return false
    return locateMotionVideo(text.substring(start, end), file) != null
}
