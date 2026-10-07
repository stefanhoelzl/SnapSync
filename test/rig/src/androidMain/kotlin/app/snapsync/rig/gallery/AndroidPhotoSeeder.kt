package app.snapsync.rig.gallery

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.provider.MediaStore
import co.touchlab.kermit.Logger
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * `POST /device/gallery/seed` where the photo library is REAL on Android: [count] JPEGs of [kind] inserted into
 * MediaStore's `DCIM/Camera` — the member's default gallery — dated as the iOS app host dates them (`PhotoSeeder`): 2001
 * for [SeedKind.BULK], an hour ahead of now for [SeedKind.POLICY] (alternating above and below the 3 MP floor) and
 * [SeedKind.NOISE] (above it, incompressible, megabytes each). The capture date is written into the file's EXIF too, as
 * MediaStore re-derives `DATE_TAKEN` from the file when an item is published.
 *
 * The photos are the APP's own items. A photo the shell writes (`adb push`) is hidden from every other app, so it is no
 * seed at all (measured 2026-09-29); an app's own items it reads with or without a grant.
 */
fun seedMediaStore(context: Context, log: Logger, count: Int, kind: SeedKind): SeedOutcome {
    val recent = kind != SeedKind.BULK
    val base = if (recent) Instant.now().plusSeconds(LEAD_SECONDS) else Instant.parse("2001-01-01T00:00:00Z")
    log.i { "seeding $count $kind photo(s) into DCIM/Camera" }
    var created = 0
    for (index in 0 until count) {
        val above = kind == SeedKind.NOISE || (kind == SeedKind.POLICY && index % 2 == 0)
        val taken = base.plusSeconds(index * 60L)
        if (!insert(
                context,
                jpeg(index, above, noise = kind == SeedKind.NOISE, taken),
                taken,
                "seed-${taken.epochSecond}-$index.jpg",
            )
        ) {
            log.e { "seeding failed at photo $index (after $created)" }
            return SeedOutcome(requested = count, created = created, kind = kind, failedAtChunk = index)
        }
        created++
    }
    return SeedOutcome(requested = count, created = created, kind = kind, failedAtChunk = null)
}

private fun jpeg(index: Int, above: Boolean, noise: Boolean, taken: Instant): File {
    val (w, h) = if (above) ABOVE_W to ABOVE_H else SMALL to SMALL
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    if (noise) {
        val random = java.util.Random(index.toLong())
        val row = IntArray(w)
        for (y in 0 until h) {
            for (x in 0 until w) row[x] = random.nextInt() or (0xFF shl 24)
            bitmap.setPixels(row, 0, w, 0, y, w, 1)
        }
    } else {
        bitmap.eraseColor(Color.HSVToColor(floatArrayOf((index * 37 % 360).toFloat(), 0.6f, 0.9f)))
    }
    val file = File.createTempFile("seed", ".jpg")
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
    bitmap.recycle()
    ExifInterface(file).apply {
        setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, EXIF_DATE.format(taken.atOffset(ZoneOffset.UTC)))
        setAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL, "+00:00")
        saveAttributes()
    }
    return file
}

private fun insert(context: Context, file: File, taken: Instant, name: String): Boolean = try {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera/")
        put(MediaStore.MediaColumns.DATE_TAKEN, taken.toEpochMilli())
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
    if (uri == null) {
        false
    } else {
        resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1
    }
} finally {
    file.delete()
}

private const val LEAD_SECONDS = 3_600L
private const val ABOVE_W = 2048
private const val ABOVE_H = 1536
private const val SMALL = 64
private val EXIF_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
