package app.snapsync.android.gallery

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.media.ExifInterface
import android.os.Build
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import app.snapsync.android.storage.context
import app.snapsync.contracts.PhotoLibrary
import app.snapsync.contracts.SEED_COUNT
import app.snapsync.model.AssetId
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The photo-library contracts' fixture on the emulator: photos this test APK inserts into MediaStore, in a clause's own
 * capture window and folder, and deletes again after the clause — its own items, so no confirmation is raised.
 *
 * The capture date is written as the photo's EXIF `DateTimeOriginal` (UTC offset included) AND as `DATE_TAKEN`: the
 * media scanner re-derives `DATE_TAKEN` from the file when an item is published, so the file has to say it too.
 */
internal object MediaStoreSeeder {

    /** The folder the default gallery's ordinary photos are seeded into. */
    const val CAMERA = "DCIM/Camera/"

    /** Give this APK the full photo grant — the grant a live run holds (revoking one kills the process). */
    fun grantFull() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            listOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        (permissions + Manifest.permission.ACCESS_MEDIA_LOCATION).forEach {
            automation.grantRuntimePermission(context.packageName, it)
        }
    }

    /** [count] photos captured at [isoDate], in [folder] (a `RELATIVE_PATH`, ending in `/`). */
    fun seed(folder: String, isoDate: String, count: Int = SEED_COUNT): Set<AssetId> =
        (1..count).mapTo(linkedSetOf()) { n -> insert(folder, isoDate, "contract-${System.nanoTime()}-$n.jpg") }

    /** Delete [ids] — this APK's own items. */
    fun delete(ids: Set<AssetId>) {
        ids.forEach { id ->
            context.contentResolver.delete(ContentUris.withAppendedId(IMAGES, id.value.toLong()), null, null)
        }
    }

    private fun insert(folder: String, isoDate: String, name: String): AssetId {
        val taken = Instant.parse(isoDate)
        val file = File.createTempFile("seed", ".jpg", context.cacheDir)
        try {
            file.writeBytes(PhotoLibrary.jpeg)
            ExifInterface(file).apply {
                setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, EXIF_DATE.format(taken.atOffset(ZoneOffset.UTC)))
                setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+00:00")
                saveAttributes()
            }
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                put(MediaStore.MediaColumns.DATE_TAKEN, taken.toEpochMilli())
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = checkNotNull(resolver.insert(IMAGES, values)) { "MediaStore refused the insert into $folder" }
            checkNotNull(resolver.openOutputStream(uri)).use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return AssetId(ContentUris.parseId(uri).toString())
        } finally {
            file.delete()
        }
    }

    private val IMAGES = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val EXIF_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
}
