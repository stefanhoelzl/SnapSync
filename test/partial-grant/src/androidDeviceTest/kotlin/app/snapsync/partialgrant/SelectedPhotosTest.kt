@file:OptIn(CompositionOnly::class)

package app.snapsync.partialgrant

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import app.snapsync.android.gallery.AndroidGallery
import app.snapsync.android.permission.AndroidPhotoPermission
import app.snapsync.android.scene.ForegroundActivity
import app.snapsync.contracts.PhotoLibrary
import app.snapsync.contracts.inboundOf
import app.snapsync.control.Verifies
import app.snapsync.inbound.CompositionOnly
import app.snapsync.ports.GalleryHandlers
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.PlatformAssetId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Android 14's "Select photos and videos" (capability `photo-access`): the grant this APK holds is exactly what that
 * choice leaves, and the gallery reads it as a limited grant whose selection is the member's gallery — a selected photo
 * outside `DCIM` included, which a full grant would never read.
 */
class SelectedPhotosTest {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val seeded = mutableListOf<Uri>()

    @AfterTest
    fun cleanUp() {
        seeded.forEach { runCatching { context.contentResolver.delete(it, null, null) } }
        scope.cancel()
    }

    @Test
    // This adapter's part: the grant reads limited, and the selection is read wherever a photo is stored. That only the
    // selected photos in range are then shared is the core's (`PhotoAccessIntegrationTest`).
    @Verifies(
        spec = "photo-access",
        requirement = "Limited access is a working membership whose scope is the selection",
        scenario = "Android 14 offers a selection",
    )
    fun `the selected photos are the gallery under a selected-photos grant wherever they are stored`(): Unit =
        runBlocking {
            val inCamera = seed("DCIM/Camera/")
            val outsideDcim = seed("Pictures/SelectedPhotosTest/")
            val gallery = AndroidGallery(
                context,
                AndroidPhotoPermission(context, ForegroundActivity(context.applicationContext as Application)),
                scope,
            )
            assertEquals(LibraryGrant.LIMITED, gallery.access(), "a selected-photos grant reads as limited")
            gallery.inbound(GalleryHandlers(onImportPlaceholder = inboundOf { }, onImportSettled = inboundOf { }))
            gallery.observeChanges(true)
            try {
                val selected = withTimeout(SELECTION_MILLIS) { gallery.selection.filterNotNull().first() }
                    .assets.mapTo(mutableSetOf()) { it.asset.id }
                assertTrue(
                    selected.containsAll(setOf(inCamera, outsideDcim)),
                    "the selection holds the photo in DCIM and the one outside it: $selected",
                )
            } finally {
                gallery.observeChanges(false)
            }
        }

    /** A photo this APK saves itself into [folder] — its own, so the partial grant always shows it. */
    private fun seed(folder: String): PlatformAssetId {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "selected-${System.nanoTime()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = checkNotNull(
            resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values),
        ) { "the photo could not be saved into $folder" }
        seeded += uri
        resolver.openOutputStream(uri).use { checkNotNull(it).write(PhotoLibrary.jpeg) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return PlatformAssetId(checkNotNull(uri.lastPathSegment))
    }

    private companion object {
        const val SELECTION_MILLIS = 15_000L
    }
}
