package app.snapsync.android.gallery

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.ExifInterface
import app.snapsync.model.StagedResource
import app.snapsync.model.motionPhotoStill
import app.snapsync.model.runCatchingCancellable
import app.snapsync.model.withExtension
import co.touchlab.kermit.Logger
import java.io.File

/**
 * **A received Live Photo, rebuilt as a motion photo** (decision record
 * `changes/archive/2026-10-01-live-motion-unification` D3): a JPEG still, Google's motion-photo XMP, and
 * the Live Photo's video appended exactly as it was delivered — the one layout Google Photos was measured to play.
 *
 * - A **HEIC** still (the iPhone default) is decoded at full size and re-encoded as JPEG at [JPEG_QUALITY], because
 *   Google Photos plays no HEIC motion photo (measured). The decoder applies the orientation, so the copy's EXIF says
 *   `NORMAL`; the capture date with its offset, the location and the camera's details are copied over. This is the
 *   receiving member's gallery copy only — the event keeps the sender's HEIC.
 * - A **JPEG** still (an iPhone set to "Most Compatible") is used byte for byte: only its XMP segment changes.
 *
 * The motion photo is written to a scratch file in [scratchDir] BEFORE the import's one insert, so a failure here — any
 * exception, and an `OutOfMemoryError` on the ~48 MB bitmap — creates nothing: [build] answers null and the still is
 * imported exactly as before (D6). [MediaStoreImport] is `@Synchronized`, so at most one bitmap is alive per process.
 */
internal class MotionPhotoBuilder(private val scratchDir: File, private val log: Logger) {

    /**
     * The motion photo built from [still] and [video], as a staged JPEG in the scratch directory — or null, and the
     * still is imported as it is. The caller deletes the returned file once the import is over.
     */
    fun build(assetId: String, still: StagedResource, video: StagedResource): StagedResource? =
        // Catches an `OutOfMemoryError` too: the full-size bitmap is the one allocation here that can fail.
        runCatchingCancellable { assemble(assetId, still, video) }.getOrElse { fallBack(assetId, it) }

    private fun assemble(assetId: String, still: StagedResource, video: StagedResource): StagedResource? {
        scratchDir.mkdirs()
        val out = File(scratchDir, SCRATCH_NAME)
        val source = File(still.stagedPath)
        val isJpeg = source.inputStream().use { it.read() == 0xFF && it.read() == 0xD8 }
        if (!isJpeg) reencode(source, out)
        val videoFile = File(video.stagedPath)
        val withXmp = motionPhotoStill(if (isJpeg) source.readBytes() else out.readBytes(), videoFile.length())
        if (withXmp == null) {
            log.w { "$assetId: the still cannot carry motion-photo XMP — imported as its still" }
            out.delete()
            return null
        }
        out.outputStream().use { stream ->
            stream.write(withXmp)
            videoFile.inputStream().use { it.copyTo(stream) }
        }
        log.i {
            "$assetId: built a motion photo (${if (isJpeg) "JPEG kept" else "re-encoded to JPEG"}, video ${videoFile.length()} B)"
        }
        // The sender's name, its extension following the bytes; the import adds SnapSync's mark (`ReceivedPhotoName`).
        // An unknown name stays unknown, and the key's extension follows instead, so the mark names it `snapsync-….jpg`.
        val name = still.originalFilename
        return still.copy(
            contentType = "image/jpeg",
            originalFilename = if (isJpeg || name.isEmpty()) name else withExtension(name, ".jpg"),
            resourceKey = if (isJpeg) still.resourceKey else withExtension(still.resourceKey, ".jpg"),
            stagedPath = out.path,
        )
    }

    /** Delete whatever a killed import left in the scratch directory. */
    fun clean() {
        scratchDir.listFiles()?.forEach { it.delete() }
    }

    private fun reencode(heic: File, out: File) {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(heic)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
        try {
            out.outputStream().use {
                check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it)) { "the JPEG encoder refused" }
            }
        } finally {
            bitmap.recycle()
        }
        val from = ExifInterface(heic.path)
        val to = ExifInterface(out.path)
        for (tag in COPIED_TAGS) from.getAttribute(tag)?.let { to.setAttribute(tag, it) }
        to.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
        to.saveAttributes()
    }

    private fun fallBack(assetId: String, e: Throwable): StagedResource? {
        log.w(e) { "$assetId: the motion photo could not be built — imported as its still" }
        File(scratchDir, SCRATCH_NAME).delete()
        return null
    }

    private companion object {
        /** Measured on the A40: ≈ 0.3 s to encode 12 MP, and indistinguishable from the HEIC by eye. */
        const val JPEG_QUALITY = 95
        const val SCRATCH_NAME = "motion-photo.jpg"

        /** What a received photo keeps: when, where, and with what it was taken. */
        val COPIED_TAGS = listOf(
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_OFFSET_TIME_ORIGINAL,
            ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME,
            ExifInterface.TAG_SUBSEC_TIME_ORIGINAL,
            ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_IMG_DIRECTION,
            ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
            ExifInterface.TAG_GPS_SPEED,
            ExifInterface.TAG_GPS_SPEED_REF,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_SOFTWARE,
            "LensMake",
            "LensModel", // no public platform constant for either; a name the platform does not know is ignored
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_APERTURE_VALUE,
            ExifInterface.TAG_SHUTTER_SPEED_VALUE,
            ExifInterface.TAG_BRIGHTNESS_VALUE,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_EXPOSURE_PROGRAM,
            ExifInterface.TAG_METERING_MODE,
            @Suppress("DEPRECATION") ExifInterface.TAG_ISO_SPEED_RATINGS, // the same tag, 0x8827
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_WHITE_BALANCE,
        )
    }
}
