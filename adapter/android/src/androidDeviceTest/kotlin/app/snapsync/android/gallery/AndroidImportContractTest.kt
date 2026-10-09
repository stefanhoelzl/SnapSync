package app.snapsync.android.gallery

import android.app.Application
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.graphics.BitmapFactory
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import app.snapsync.android.permission.AndroidPhotoPermission
import app.snapsync.android.scene.ForegroundActivity
import app.snapsync.android.storage.context
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.GalleryImportContract
import app.snapsync.contracts.GalleryImportState
import app.snapsync.contracts.Host
import app.snapsync.contracts.ImportDeliveries
import app.snapsync.contracts.ImportedLibrary
import app.snapsync.contracts.MarkerState
import app.snapsync.contracts.PhotoLibrary
import app.snapsync.contracts.StagedImport
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.model.AssetId
import app.snapsync.model.AssetRef
import app.snapsync.model.GalleryAccess
import app.snapsync.model.GalleryRead
import app.snapsync.model.ImportRequest
import app.snapsync.model.ImportResult
import app.snapsync.model.ReceivedPhotoName
import app.snapsync.model.ResourceRole
import app.snapsync.model.StagedResource
import app.snapsync.model.jpegXmp
import app.snapsync.model.locateMotionVideo
import app.snapsync.model.motionPhotoStill
import app.snapsync.ports.GalleryHandlers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The MediaStore import (capability `receiving-photos`) against the [GalleryImportContract] on the emulator, and the
 * Android facts no shared contract states: a received photo lands once in the camera folder at its capture time — an
 * iPhone HEIC with an offset and an iPhone HEVC movie and an Android MP4 through their own metadata, a JPEG with no
 * date of its own through the modification time the import stamps (MediaProvider ignores an app's `DATE_TAKEN`); a Live Photo
 * arrives as ONE JPEG motion photo with its video appended unchanged, or as its still, once, when that cannot be built;
 * what a killed import leaves is invisible, reads as absent, and is cleaned at the next import;
 * a content type that is neither photo nor video is refused for good.
 *
 * The fixtures under `resources/import/` were generated with Pillow/pillow-heif and ffmpeg (libx265 `hvc1`, the
 * `com.apple.quicktime.creationdate` atom) to carry an iPhone's metadata; each was captured at [CAPTURED].
 * `iphone-live.heic` (128×64, rotated a quarter turn) also carries a location and Make/Model. It is sized in whole
 * 64-pixel blocks: a padded HEIF needs a crop box, which the API-30 decoder ignores (measured).
 */
class AndroidImportContractTest {

    private val foreground = ForegroundActivity(context.applicationContext as Application)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val deliveries = ImportDeliveries()
    private val created = mutableSetOf<Uri>()
    private val staging = File(context.filesDir, "contract-staging")
    private val albums = mutableListOf<String>()

    /** What the import tells its owner: kept, and every created row remembered for the cleanup. */
    private val handlers = GalleryHandlers(
        onChanged = {},
        onImportPlaceholder = deliveries.handlers.onImportPlaceholder,
        onImportSettled = { ref, result ->
            deliveries.handlers.onImportSettled(ref, result)
            if (result is ImportResult.Imported) created += uriOf(result.createdLocalId)
        },
    )

    private val gallery by lazy {
        AndroidGallery(context, AndroidPhotoPermission(context, foreground), scope).apply { listen(handlers) }
    }

    @BeforeTest
    fun grant() {
        MediaStoreSeeder.grantFull()
        staging.mkdirs()
    }

    @AfterTest
    fun cleanUp() {
        created.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        staging.deleteRecursively()
        albums.forEach {
            File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    android.os.Environment.DIRECTORY_DCIM,
                ).parentFile,
                it,
            ).delete()
        }
        scope.cancel()
    }

    private val importer = object : Binding<GalleryImportState, StagedImport> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val grant = GalleryAccess.GRANTED
        override val reaches = setOf(GalleryImportState.GRANTED_VALID_STAGED, GalleryImportState.GRANTED_INVALID_STAGED)

        override fun create(state: GalleryImportState, clauseId: String, log: CallLog): Entered<StagedImport> {
            val bytes = when (state) {
                GalleryImportState.GRANTED_VALID_STAGED -> PhotoLibrary.jpeg
                GalleryImportState.GRANTED_INVALID_STAGED -> PhotoLibrary.notAnImage
            }
            var staged = 0
            val stage = {
                staged++
                listOf(stagedResource("contract-$clauseId-$staged-primary.jpg", "image/jpeg", bytes))
            }
            val library = object : ImportedLibrary {
                // The date the library sorts the item by: the scan's DATE_TAKEN, or — for a file with no date of its own,
                // which the contract's plain JPEG is — the DATE_MODIFIED the import stamped (seconds).
                override suspend fun captureDate(id: AssetId): String? =
                    (dateTaken(id) ?: modified(id)?.times(MILLIS_PER_SECOND))?.let(::iso)
                override val deliveries = this@AndroidImportContractTest.deliveries

                // The column the gallery reader reports as the primary resource's original filename.
                override suspend fun primaryFilename(id: AssetId): String? = column(
                    id,
                    MediaStore.MediaColumns.DISPLAY_NAME,
                )
            }
            // Listened again through this clause's proxy, so what the import tells its owner is this clause's to claim.
            val port = gallery.recorded(log).apply { listen(handlers) }
            return Entered.Ready(StagedImport(port, stage, library))
        }
    }

    @Test
    fun `the MediaStore import satisfies the GalleryImport contract`() = verify(GalleryImportContract, importer)

    @Test
    fun `each original lands once in the camera folder at its capture time`(): Unit = runBlocking {
        for ((fixture, type) in FIXTURES) {
            val ref = ref(fixture)
            val result = gallery.import(
                ImportRequest(ref, listOf(stagedResource(fixture, type, fixture(fixture))), iso(CAPTURED), null),
            )
            val id = assertIs<ImportResult.Imported>(result, "$fixture imports").createdLocalId
            assertEquals(MarkerState.CONFIRMED, deliveries.marker(ref))
            assertEquals(MediaStoreImport.CAMERA_FOLDER, column(id, MediaStore.MediaColumns.RELATIVE_PATH), fixture)
            assertEquals(
                ReceivedPhotoName.mark(fixture, "key-$fixture", ref),
                column(id, MediaStore.MediaColumns.DISPLAY_NAME),
                "the sender's filename is kept, with SnapSync's mark",
            )
            if (fixture in UNDATED) {
                // MediaProvider ignores an app's DATE_TAKEN; only the scan writes it, from the file. A file with no
                // date of its own is left undated and sorts by DATE_MODIFIED, which the import set to its capture time.
                assertEquals(null, dateTaken(id), "$fixture carries no date for the scan to find")
                assertEquals(CAPTURED / MILLIS_PER_SECOND, modified(id), fixture)
            } else {
                assertEquals(CAPTURED, dateTaken(id), "$fixture sorts at its own capture time")
            }
            val read = assertIs<GalleryRead.Read<List<app.snapsync.model.AssetFacts>>>(gallery.assetsById(setOf(id)))
            assertEquals(1, read.value.size, "$fixture is readable through the default gallery, once")
        }
    }

    @Test
    fun `a HEIC Live Photo arrives as ONE JPEG motion photo carrying its video unchanged`(): Unit = runBlocking {
        val ref = ref("live")
        val mov = fixture("iphone.mov")
        val stem = uniqueStem("LIVE")
        val resources = listOf(
            stagedResource("$stem.HEIC", "image/heic", fixture("iphone-live.heic")),
            stagedResource("$stem.MOV", "video/quicktime", mov, ResourceRole.LIVE),
        )
        val id = assertIs<ImportResult.Imported>(
            gallery.import(ImportRequest(ref, resources, iso(CAPTURED), null)),
        ).createdLocalId
        assertEquals(MarkerState.CONFIRMED, deliveries.marker(ref))
        assertEquals(
            "image/jpeg",
            column(id, MediaStore.MediaColumns.MIME_TYPE),
            "re-encoded: Google Photos plays no HEIC motion photo",
        )
        assertEquals(
            ReceivedPhotoName.mark("$stem.jpg", "key-$stem.jpg", ref),
            column(id, MediaStore.MediaColumns.DISPLAY_NAME),
            "the sender's name with SnapSync's mark, and the new extension",
        )
        assertEquals(
            0,
            countNamed(ReceivedPhotoName.mark("$stem.MOV", "key-$stem.MOV", ref)),
            "the movie travels inside the photo, not beside it",
        )
        assertEquals(
            0,
            countNamed(ReceivedPhotoName.mark("$stem.HEIC", "key-$stem.HEIC", ref)),
            "one item, never the still as well",
        )

        val file = bytesOf(id)
        val xmp = assertNotNull(jpegXmp(file), "the JPEG carries XMP")
        assertTrue("Camera:MotionPhoto=\"1\"" in xmp && "GCamera:MicroVideo=\"1\"" in xmp, "both tag sets")
        val video = assertNotNull(locateMotionVideo(xmp, file), "the XMP locates the video")
        assertContentEquals(mov, file.copyOfRange(video.first, video.last + 1), "the MOV is appended as delivered")

        assertEquals(CAPTURED, dateTaken(id), "the capture date and its offset were carried over")
        val exif = ExifInterface(ByteArrayInputStream(file))
        assertEquals(
            ExifInterface.ORIENTATION_NORMAL,
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, -1),
            "the decoder applied it",
        )
        assertEquals("iPhone 15", exif.getAttribute(ExifInterface.TAG_MODEL))
        val latLong = FloatArray(2)
        assertTrue(exif.getLatLong(latLong), "the location travels with the photo")
        assertEquals(LIVE_LATITUDE, latLong[0], DEGREES_TOLERANCE)
        assertEquals(LIVE_LONGITUDE, latLong[1], DEGREES_TOLERANCE)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(file, 0, file.size, bounds)
        assertEquals(LIVE_UPRIGHT_SIZE, bounds.outWidth to bounds.outHeight, "full size, upright")
    }

    @Test
    fun `a JPEG Live Photo keeps its still byte for byte ahead of the XMP`(): Unit = runBlocking {
        val still = fixture("noexif.jpg")
        val mov = fixture("iphone.mov")
        val stem = uniqueStem("LIVEJPEG")
        val resources = listOf(
            stagedResource("$stem.JPG", "image/jpeg", still),
            stagedResource("$stem.MOV", "video/quicktime", mov, ResourceRole.LIVE),
        )
        val ref = ref("jpeg-live")
        val id = assertIs<ImportResult.Imported>(
            gallery.import(ImportRequest(ref, resources, iso(CAPTURED), null)),
        ).createdLocalId
        assertEquals(
            ReceivedPhotoName.mark("$stem.JPG", "key-$stem.JPG", ref),
            column(id, MediaStore.MediaColumns.DISPLAY_NAME),
        )
        assertContentEquals(
            assertNotNull(motionPhotoStill(still, mov.size.toLong())) + mov,
            bytesOf(id),
            "only the XMP segment is new",
        )
    }

    @Test
    fun `a Live Photo whose still cannot carry the motion arrives as its still exactly once`(): Unit = runBlocking {
        // A still that already describes a motion photo cannot be given a second one: the conversion answers "no" before
        // anything is inserted, and the still is imported exactly as before.
        val still = assertNotNull(motionPhotoStill(fixture("noexif.jpg"), videoLength = 10))
        val stem = uniqueStem("FALLBACK")
        val resources = listOf(
            stagedResource("$stem.JPG", "image/jpeg", still),
            stagedResource("$stem.MOV", "video/quicktime", fixture("iphone.mov"), ResourceRole.LIVE),
        )
        val ref = ref("fallback")
        val id = assertIs<ImportResult.Imported>(
            gallery.import(ImportRequest(ref, resources, iso(CAPTURED), null)),
        ).createdLocalId
        assertContentEquals(still, bytesOf(id), "the still, unchanged")
        assertEquals(1, countNamed(ReceivedPhotoName.mark("$stem.JPG", "key-$stem.JPG", ref)), "once")
        assertEquals(MarkerState.CONFIRMED, deliveries.marker(ref))
    }

    @Test
    fun `what a killed import leaves is invisible and absent and cleaned at the next import`(): Unit = runBlocking {
        // An import killed between its pending insert and its publish: the item exists only as a pending row.
        val pending = insertPending("killed.jpg")
        val id = AssetId(ContentUris.parseId(pending).toString())
        val read = assertIs<GalleryRead.Read<List<app.snapsync.model.AssetFacts>>>(gallery.assetsById(setOf(id)))
        assertTrue(read.value.isEmpty(), "a pending item reads as absent, so the startup sweep imports again")

        val ref = ref("after-kill")
        gallery.import(
            ImportRequest(
                ref,
                listOf(stagedResource("next.jpg", "image/jpeg", PhotoLibrary.jpeg)),
                iso(CAPTURED),
                null,
            ),
        )
        assertEquals(0, pendingCount(), "the leftover pending item is deleted before the next import")
    }

    @Test
    fun `a received photo imported into an event album lands only in its folder — once`(): Unit = runBlocking {
        val album = checkNotNull(gallery.createAlbum("import-contract-${System.nanoTime()}"))
        albums += album
        val ref = ref("into-album")
        val result = gallery.import(
            ImportRequest(
                ref,
                listOf(stagedResource("IMG_7.JPG", "image/jpeg", PhotoLibrary.jpeg)),
                iso(CAPTURED),
                album,
            ),
        )
        val id = assertIs<ImportResult.Imported>(result).createdLocalId
        assertEquals(album, column(id, MediaStore.MediaColumns.RELATIVE_PATH), "saved straight into the album's folder")
        assertEquals(0, countNamed("IMG_7.JPG"), "not loose in the camera folder first")
    }

    @Test
    fun `what a killed import into an album leaves is cleaned at the next import`(): Unit = runBlocking {
        val album = checkNotNull(gallery.createAlbum("import-contract-${System.nanoTime()}"))
        albums += album
        insertPending("killed-album.jpg", folder = album)
        gallery.import(
            ImportRequest(
                ref("after-album-kill"),
                listOf(stagedResource("next.jpg", "image/jpeg", PhotoLibrary.jpeg)),
                iso(CAPTURED),
                album,
            ),
        )
        assertEquals(
            0,
            pendingCount(album),
            "the album folder's leftover pending item is deleted before the next import",
        )
    }

    @Test
    fun `a content type that is neither photo nor video is refused for good`(): Unit = runBlocking {
        val ref = ref("pdf")
        val result = gallery.import(
            ImportRequest(
                ref,
                listOf(stagedResource("doc.pdf", "application/pdf", PhotoLibrary.notAnImage)),
                iso(CAPTURED),
                null,
            ),
        )
        val failed = assertIs<ImportResult.Failed>(result)
        assertTrue(failed.consumedResources, "settled, never retried")
        assertIs<ImportResult.Failed>(deliveries.settled(ref), "the refusal is settled")
    }

    private fun ref(name: String) = AssetRef(
        sourceDeviceId = "import-contract",
        sourceAssetId = AssetId("$name-${System.nanoTime()}"),
    )

    private fun stagedResource(name: String, type: String, bytes: ByteArray, role: ResourceRole = ResourceRole.PRIMARY): StagedResource {
        val file = File(staging, "${System.nanoTime()}-$name").apply { writeBytes(bytes) }
        return StagedResource("key-$name", role.wire, type, name, file.absolutePath)
    }

    private fun fixture(name: String): ByteArray =
        checkNotNull(
            javaClass.classLoader?.getResourceAsStream("import/$name"),
        ) { "no fixture $name" }.use { it.readBytes() }

    private fun uriOf(id: AssetId): Uri = MediaStore.Files.getContentUri(
        MediaStore.VOLUME_EXTERNAL_PRIMARY,
        id.value.toLong(),
    )

    private fun column(id: AssetId, name: String): String? =
        context.contentResolver.query(
            uriOf(id),
            arrayOf(name),
            null,
            null,
        )?.use { if (it.moveToFirst()) it.getString(0) else null }

    private fun modified(id: AssetId): Long? = column(id, MediaStore.MediaColumns.DATE_MODIFIED)?.toLongOrNull()

    private fun dateTaken(id: AssetId): Long? = column(id, MediaStore.MediaColumns.DATE_TAKEN)?.toLongOrNull()

    /**
     * A name no earlier run left in the camera folder. MediaStore resolves a collision by renaming (a camera-style
     * `IMG_0001` becomes the next free number), which the spec allows but which would make a name assertion flaky.
     */
    private fun uniqueStem(prefix: String) = "${prefix}_${System.nanoTime()}"

    private fun bytesOf(id: AssetId): ByteArray =
        checkNotNull(context.contentResolver.openInputStream(uriOf(id))) { "no stream for $id" }.use { it.readBytes() }

    private fun countNamed(name: String): Int = context.contentResolver.query(
        MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        arrayOf(MediaStore.MediaColumns._ID),
        "${MediaStore.MediaColumns.DISPLAY_NAME} = ? AND ${MediaStore.MediaColumns.RELATIVE_PATH} = ?",
        arrayOf(name, MediaStoreImport.CAMERA_FOLDER),
        null,
    )?.use { it.count } ?: 0

    private fun insertPending(name: String, folder: String = MediaStoreImport.CAMERA_FOLDER): Uri = checkNotNull(
        context.contentResolver.insert(
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            },
        ),
    ).also { created += it }

    private fun pendingCount(folder: String = MediaStoreImport.CAMERA_FOLDER): Int {
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_ONLY)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.MediaColumns.RELATIVE_PATH} = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(folder))
        }
        return context.contentResolver.query(
            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), arrayOf(MediaStore.MediaColumns._ID), args, null,
        )?.use { it.count } ?: 0
    }

    private companion object {
        /** When every fixture was captured: 2026-07-01 12:34:56 at +02:00. */
        val CAPTURED: Long = Instant.parse("2026-07-01T10:34:56Z").toEpochMilliseconds()

        val FIXTURES = listOf(
            "iphone.heic" to "image/heic",
            "iphone.mov" to "video/quicktime",
            "noexif.jpg" to "image/jpeg",
            "android.mp4" to "video/mp4",
        )

        /** MediaStore's `DATE_MODIFIED` is in seconds. */
        const val MILLIS_PER_SECOND = 1000L

        /** Where `iphone-live.heic` was taken: 48°8'30" N, 11°34'15" E. */
        const val LIVE_LATITUDE = 48.1417f
        const val LIVE_LONGITUDE = 11.5708f
        const val DEGREES_TOLERANCE = 0.001f

        /** `iphone-live.heic` is 128×64 turned a quarter: upright, it is 64 wide and 128 high. */
        val LIVE_UPRIGHT_SIZE = 64 to 128

        /** The fixtures that carry no capture date of their own. */
        val UNDATED = setOf("noexif.jpg")

        fun iso(millis: Long): String = Instant.fromEpochMilliseconds(millis).toString()
    }
}
