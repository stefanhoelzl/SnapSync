package app.snapsync.android.gallery

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import app.snapsync.android.storage.context
import app.snapsync.contracts.PhotoLibrary
import app.snapsync.contracts.SEED_COUNT
import app.snapsync.ports.PlatformAssetId
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
    fun seed(folder: String, isoDate: String, count: Int = SEED_COUNT): Set<PlatformAssetId> =
        (1..count).mapTo(linkedSetOf()) { n -> insert(folder, isoDate, "contract-${System.nanoTime()}-$n.jpg").first }

    /**
     * One photo captured at [isoDate] in [folder] of the storage [volume] (a MediaStore volume name): its id, and the URI
     * it is deleted through.
     */
    fun seedOne(
        folder: String,
        isoDate: String,
        volume: String = MediaStore.VOLUME_EXTERNAL_PRIMARY,
    ): Pair<PlatformAssetId, Uri> = insert(folder, isoDate, "seeded-${System.nanoTime()}.jpg", volume)

    /** Delete [ids] — this APK's own items. */
    fun delete(ids: Set<PlatformAssetId>) {
        ids.forEach { id ->
            context.contentResolver.delete(ContentUris.withAppendedId(IMAGES, id.value.toLong()), null, null)
        }
    }

    private fun insert(
        folder: String,
        isoDate: String,
        name: String,
        volume: String = MediaStore.VOLUME_EXTERNAL_PRIMARY,
    ): Pair<PlatformAssetId, Uri> {
        val taken = Instant.parse(isoDate)
        val file = datedJpeg(taken, location = null)
        try {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                put(MediaStore.MediaColumns.DATE_TAKEN, taken.toEpochMilli())
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = checkNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(volume), values)) {
                "MediaStore refused the insert into $folder on $volume"
            }
            checkNotNull(resolver.openOutputStream(uri)).use { out -> file.inputStream().use { it.copyTo(out) } }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return PlatformAssetId(ContentUris.parseId(uri).toString()) to uri
        } finally {
            file.delete()
        }
    }

    /**
     * One photo captured at [isoDate] in [folder] (a `RELATIVE_PATH` on the phone's own storage) that ANOTHER app saved —
     * the shell, as a camera app would: MediaStore treats it as no photo of this APK's. Answers its id and the path
     * [deleteSavedByAnotherApp] removes it by.
     */
    fun seedSavedByAnotherApp(
        folder: String,
        isoDate: String,
        location: Pair<Double, Double>? = null,
    ): Pair<PlatformAssetId, String> {
        val name = "other-app-${System.nanoTime()}.jpg"
        val path = "/sdcard/$folder$name"
        val file = datedJpeg(Instant.parse(isoDate), location)
        try {
            val (stdout, stdin) = automation.executeShellCommandRw("dd of=$path")
            ParcelFileDescriptor.AutoCloseOutputStream(stdin).use { out -> file.inputStream().use { it.copyTo(out) } }
            ParcelFileDescriptor.AutoCloseInputStream(stdout).use { it.readBytes() }
        } finally {
            file.delete()
        }
        // A file the shell writes is pending, unseen by every app, until it is scanned (the snapsync-android skill).
        shell("content call --uri content://media --method scan_file --arg $path")
        val id = context.contentResolver.query(
            IMAGES,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
            arrayOf(name),
            null,
        ).use { cursor -> checkNotNull(cursor?.takeIf { it.moveToFirst() }?.getLong(0)) { "$path was not scanned" } }
        return PlatformAssetId(id.toString()) to path
    }

    /** Remove a photo [seedSavedByAnotherApp] saved: the file, then its row, by scanning the path again. */
    fun deleteSavedByAnotherApp(path: String) {
        shell("rm -f $path")
        shell("content call --uri content://media --method scan_file --arg $path")
    }

    /** A JPEG file in this APK's cache, its EXIF saying it was captured at [taken] — and where, when [location] is given. */
    private fun datedJpeg(taken: Instant, location: Pair<Double, Double>?): File {
        val file = File.createTempFile("seed", ".jpg", context.cacheDir)
        file.writeBytes(PhotoLibrary.jpeg)
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, EXIF_DATE.format(taken.atOffset(ZoneOffset.UTC)))
            setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+00:00")
            location?.let { (latitude, longitude) ->
                setAttribute(ExifInterface.TAG_GPS_LATITUDE, rational(latitude))
                setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (latitude < 0) "S" else "N")
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE, rational(longitude))
                setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (longitude < 0) "W" else "E")
            }
            saveAttributes()
        }
        return file
    }

    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use {
            it.readBytes().decodeToString()
        }

    /** [degrees] as EXIF writes a coordinate: degrees, minutes and seconds, each a rational. */
    private fun rational(degrees: Double): String {
        val abs = kotlin.math.abs(degrees)
        val whole = abs.toInt()
        val minutes = ((abs - whole) * MINUTES).toInt()
        val millis = ((abs - whole - minutes / MINUTES) * SECONDS * MILLIS).toLong()
        return "$whole/1,$minutes/1,$millis/${MILLIS.toInt()}"
    }

    private const val MINUTES = 60.0
    private const val SECONDS = 3600.0
    private const val MILLIS = 1000.0

    private val IMAGES = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val EXIF_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
}
