package app.snapsync.rig.gallery

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import app.snapsync.levers.MovingPhoto
import app.snapsync.motionphoto.locateMotionVideo
import app.snapsync.rig.refused
import co.touchlab.kermit.Logger
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * `POST /device/gallery/add-moving` where the photo library is REAL on Android: ONE motion photo — a camera's single
 * file, still and video — inserted into MediaStore's `DCIM/Camera` byte for byte, as the app's own item (`adb push` is
 * hidden from the app; `seedMediaStore` says why). Android's gallery has no Live Photo, so a still with a movie is
 * refused: two files are two photos here.
 *
 * The capture date goes where a camera puts it, into the file's EXIF: MediaStore takes `DATE_TAKEN` from there when the
 * item is published, and refuses an app's later update of it (measured on the emulator, 2026-10-13). Each EXIF date is
 * rewritten IN PLACE ([retimed]) — the same 19 characters — so no offset moves and the motion photo stays the camera's
 * file in every other byte. Answers the item's MediaStore id.
 */
fun addMotionPhoto(context: Context, log: Logger, photo: MovingPhoto): String {
    val file = photo.files.singleOrNull()?.takeUnless { it.isMovie }
        ?: refused("an Android motion photo is one image file; was ${photo.files.map { it.name }}")
    val taken = runCatching { Instant.parse(photo.date) }.getOrNull()
        ?: refused("date must be an ISO-8601 instant, was '${photo.date}'")
    val bytes = retimed(file.bytes(), taken)
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
    log.i { "added motion photo ${file.name} (${bytes.size} B) as $uri, taken $taken" }
    return uri.lastPathSegment.orEmpty()
}

/**
 * [file] with every EXIF date (`yyyy:MM:dd HH:mm:ss`) before its motion video replaced by [taken] in UTC, in place:
 * the same length, so every offset in the file — the XMP's video length above all — still holds. A camera's EXIF time
 * is local and may carry an offset tag; one left as it was moves the date by at most a day, which the journeys' event
 * window absorbs.
 */
private fun retimed(file: ByteArray, taken: Instant): ByteArray {
    val text = String(file, Charsets.ISO_8859_1)
    val end = xmpPacket(text)?.let { locateMotionVideo(it, file)?.first } ?: file.size
    val date = EXIF_DATE.format(taken.atOffset(ZoneOffset.UTC)).toByteArray(Charsets.ISO_8859_1)
    val out = file.copyOf()
    EXIF_DATE_PATTERN.findAll(text.substring(0, end)).forEach { date.copyInto(out, it.range.first) }
    return out
}

private val EXIF_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
private val EXIF_DATE_PATTERN = Regex("""(19|20)\d\d:[01]\d:[0-3]\d [0-2]\d:[0-5]\d:[0-5]\d""")

/**
 * Whether [file] is a motion photo a gallery app plays: an XMP packet anywhere in it (a JPEG's APP1 or a HEIC's `mime`
 * item alike) whose `MotionPhoto` item, or legacy `MicroVideoOffset`, points at a video at the file's end.
 */
fun isMotionPhoto(file: ByteArray): Boolean {
    val xmp = xmpPacket(String(file, Charsets.ISO_8859_1)) ?: return false
    return locateMotionVideo(xmp, file) != null
}

/** The file's XMP packet, wherever it sits — a JPEG's APP1, a HEIC's `mime` item — or null when it has none. */
private fun xmpPacket(text: String): String? {
    val start = text.indexOf("<x:xmpmeta").takeIf { it >= 0 } ?: return null
    val end = text.indexOf("</x:xmpmeta>", start).takeIf { it >= 0 } ?: return null
    return text.substring(start, end)
}
