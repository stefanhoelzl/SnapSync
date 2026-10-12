package app.snapsync.android.gallery

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteException
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.GalleryImportRequest
import app.snapsync.ports.ImportFile
import app.snapsync.ports.ImportOutcome
import app.snapsync.ports.PlatformAssetId
import app.snapsync.ports.ResourceKind
import co.touchlab.kermit.Logger
import java.io.File
import java.io.IOException
import kotlin.time.Instant

/**
 * Rebuilding a foreign photo in the member's library: one MediaStore item, owned by this app, never visible
 * half-written — in the event album's folder when the import names one (a received photo is in the album the moment
 * it exists, never loose first), in `DCIM/Camera` otherwise.
 *
 * 1. **Insert pending** (`IS_PENDING = 1`) into the image or video collection, by the original's content
 *    type. A pending item is invisible to every other app and to this app's own reads, so its `_ID` — the asset's
 *    identity ([PlatformAssetId]) — is handed to [onPlaceholder] before anything can observe it: that record is what keeps the
 *    received photo from being shared back.
 * 2. **Copy the staged bytes**, unchanged: a HEIC stays a HEIC, a MOV stays a QuickTime movie. A Live Photo is the one
 *    exception: its still and video are first built into a motion photo ([MotionPhotoBuilder], BEFORE step 1, so a
 *    failure creates nothing and the still is inserted exactly as before), and that JPEG is what is inserted and
 *    written. Either way the staged resources are released together once the import confirms.
 * 3. **Publish** (`IS_PENDING = 0`): the one atomic point where the item goes live. The media scanner, which runs on
 *    the publish, fills `DATE_TAKEN` from the file's own metadata (EXIF with its offset, a movie's creation time).
 *    A file that carries no date comes out of that scan with none — and a value written with the publish is
 *    discarded by it (measured on the emulator, 2026-09-30) — so an item left undated is then given the sender's
 *    capture time in an update of its own; a dated one is never touched.
 *
 * A kill before step 3 leaves an invisible pending item. The core's startup sweep finds its recorded id absent and
 * imports again, and [cleanOrphans] — once per process, before the first import — deletes this app's leftover pending
 * items in `DCIM/Camera` and the event albums' folders (MediaStore would expire them after a week anyway), and a killed
 * conversion's scratch file.
 *
 * **MediaStore stores whatever bytes it is given**, so the library's own "reject what I cannot read" is done here: an
 * original that does not decode (an image with no bounds, a video with no dimensions), and a content type that is
 * neither an image nor a video, are refused for good ([ImportOutcome.Failed] with `consumedResources`), so the photo is
 * settled rather than retried forever and never lands in the camera roll broken.
 */
internal class MediaStoreImport(context: Context, private val log: Logger) {

    private val resolver: ContentResolver = context.applicationContext.contentResolver
    private val packageName: String = context.applicationContext.packageName
    private val motionPhotos = MotionPhotoBuilder(File(context.applicationContext.cacheDir, "motion-photo"), log)
    private var orphansCleaned = false

    @Synchronized
    fun import(request: GalleryImportRequest, onPlaceholder: (PlatformAssetId) -> Unit): ImportOutcome {
        cleanOrphansOnce()
        val original = request.files.firstOrNull { it.kind != ResourceKind.PAIRED_VIDEO }
            ?: return refused("no original to import among ${request.files.map { it.kind }}")
        val collection = collectionFor(original.contentType)
            ?: return refused("'${original.contentType}' is neither a photo nor a video")
        if (!decodes(
                original,
                collection,
            )
        ) {
            return refused("the original '${original.name}' does not decode")
        }
        val folder = request.album?.value?.takeIf { DefaultGallery.isAlbumFolder(it) } ?: CAMERA_FOLDER
        val motion = motionPhotoOf(request, original, collection)
        try {
            return insert(request, motion ?: original, collection, folder, onPlaceholder)
        } finally {
            motion?.let { File(it.path).delete() }
        }
    }

    /** A Live Photo's motion photo, when the request is one and it can be built; null imports [original] as it is. */
    private fun motionPhotoOf(request: GalleryImportRequest, original: ImportFile, collection: Uri): ImportFile? {
        val live = request.files.firstOrNull { it.kind == ResourceKind.PAIRED_VIDEO }
        if (live == null || collection != IMAGES || !live.contentType.startsWith("video/")) return null
        return motionPhotos.build(request.correlation, original, live)
    }

    private fun insert(
        request: GalleryImportRequest,
        primary: ImportFile,
        collection: Uri,
        folder: String,
        onPlaceholder: (PlatformAssetId) -> Unit,
    ): ImportOutcome {
        val uri = runCatchingCancellable { resolver.insert(collection, pendingValues(primary, folder)) }
            .onFailure { log.w(it) { "${request.correlation}: the insert was refused" } }
            .getOrNull()
            ?: return ImportOutcome.Failed("MediaStore refused the insert")
        val id = PlatformAssetId(ContentUris.parseId(uri).toString())
        onPlaceholder(id)
        try {
            copy(primary, uri)
        } catch (e: IOException) {
            discard(uri)
            return ImportOutcome.Failed("the bytes could not be written: ${e.message}", placeholder = id)
        } catch (e: SecurityException) {
            discard(uri)
            return ImportOutcome.Failed("the item could not be opened for writing: ${e.message}", placeholder = id)
        }
        request.created?.let { stampModified(uri, it) }
        val published = runCatchingCancellable { resolver.update(uri, publishedValues(), null) }.getOrDefault(0)
        if (published != 1) {
            discard(uri)
            return ImportOutcome.Failed("the item could not be published", placeholder = id)
        }
        log.i { "${request.correlation}: imported as $id into $folder" }
        return ImportOutcome.Imported(id)
    }

    private fun cleanOrphansOnce() {
        if (orphansCleaned) return
        orphansCleaned = true
        motionPhotos.clean()
        val removed = COLLECTIONS.sumOf { collection -> cleanOrphans(collection) }
        if (removed > 0) log.i { "deleted $removed pending item(s) a killed import left behind" }
    }

    /** Delete this app's pending items in the folders it imports into — only ever a killed import's leftovers. */
    private fun cleanOrphans(collection: Uri): Int = runCatchingCancellable {
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
            putString(
                ContentResolver.QUERY_ARG_SQL_SELECTION,
                "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ? AND " +
                    "(${MediaStore.MediaColumns.RELATIVE_PATH} = ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?)",
            )
            putStringArray(
                ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS,
                arrayOf(packageName, CAMERA_FOLDER, "${DefaultGallery.ALBUM_ROOT}%"),
            )
        }
        val ids = resolver.query(collection, arrayOf(MediaStore.MediaColumns._ID), args, null)?.use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getLong(0)) }
        }.orEmpty()
        ids.count { resolver.delete(ContentUris.withAppendedId(collection, it), null) == 1 }
    }.onFailure { log.w(it) { "the leftover pending items could not be cleaned" } }.getOrDefault(0)

    private fun copy(resource: ImportFile, uri: Uri) {
        val out = resolver.openOutputStream(uri, "w") ?: throw IOException("MediaStore opened no stream")
        out.use { stream -> File(resource.path).inputStream().use { it.copyTo(stream) } }
    }

    /** Whether the staged original reads as what it claims to be: an image's bounds, a video's dimensions. */
    private fun decodes(resource: ImportFile, collection: Uri): Boolean = runCatchingCancellable {
        if (collection == IMAGES) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(resource.path, bounds)
            bounds.outWidth > 0 && bounds.outHeight > 0
        } else {
            MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(resource.path)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()?.let { it > 0 } == true
            }
        }
    }.getOrDefault(false)

    private fun discard(uri: Uri) {
        runCatchingCancellable {
            resolver.delete(uri, null)
        }.onFailure { log.w(it) { "the unfinished item $uri stays pending" } }
    }

    private fun pendingValues(resource: ImportFile, folder: String) = ContentValues().apply {
        // The name the request gives. A collision makes MediaStore append " (1)".
        put(MediaStore.MediaColumns.DISPLAY_NAME, resource.name)
        put(MediaStore.MediaColumns.MIME_TYPE, resource.contentType)
        put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
        put(MediaStore.MediaColumns.IS_PENDING, 1)
    }

    private fun publishedValues() = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }

    /**
     * Set the pending file's modification time to the sender's capture time [created], before the publish scans it. The scanner
     * derives `DATE_MODIFIED` from it, and MediaProvider ignores an app's `DATE_TAKEN` (only the scan writes it), so
     * this is the one date an app can give an item whose file carries none.
     */
    private fun stampModified(uri: Uri, created: Instant) {
        val millis = created.toEpochMilliseconds()
        // Best effort, as a missing path already is: the item then keeps the time it was written at.
        val stamped = try {
            val path = resolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
            path != null && File(path).setLastModified(millis)
        } catch (e: SecurityException) {
            false.also { log.w { "$uri: ${e::class.simpleName}: ${e.message}" } }
        } catch (e: SQLiteException) {
            false.also { log.w { "$uri: ${e::class.simpleName}: ${e.message}" } }
        }
        if (!stamped) log.w { "$uri: the modification time could not be set" }
    }

    private fun refused(reason: String) = ImportOutcome.Failed(reason, consumedResources = true)
        .also { log.w { "import refused for good: $reason" } }

    companion object {
        /** Where received photos land with no event album: the camera folder, among the member's own camera photos. */
        const val CAMERA_FOLDER = "DCIM/Camera/"

        private val IMAGES: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        private val VIDEOS: Uri = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        private val COLLECTIONS = listOf(IMAGES, VIDEOS)

        fun collectionFor(contentType: String): Uri? = when {
            contentType.startsWith("image/") -> IMAGES
            contentType.startsWith("video/") -> VIDEOS
            else -> null
        }
    }
}
