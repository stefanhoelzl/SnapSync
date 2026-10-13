package app.snapsync.partialgrant

import app.snapsync.ports.GalleryReader
import app.snapsync.contracts.SeededLibrary
import app.snapsync.contracts.GalleryReaderState
import app.snapsync.contracts.GalleryReaderContract
import app.snapsync.android.gallery.AndroidGalleryReader
import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import app.snapsync.android.gallery.AndroidGallery
import app.snapsync.android.permission.AndroidPhotoPermission
import app.snapsync.android.scene.ForegroundActivity
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.GalleryChange
import app.snapsync.contracts.GalleryContract
import app.snapsync.contracts.GalleryState
import app.snapsync.contracts.Host
import app.snapsync.contracts.PhotoAccess
import app.snapsync.contracts.PhotoAccessContract
import app.snapsync.contracts.PhotoAccessState
import app.snapsync.contracts.PhotoLibrary
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.ports.PlatformAssetId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The photo-library ports under a partial grant, on the emulator: an APK that declares only
 * `READ_MEDIA_VISUAL_USER_SELECTED`, which its installation grants — what the selection sheet's "Select photos" leaves —
 * and that never holds more. [PartialGrantContractTest] runs it. In this module's main code because only the main
 * compilation sees `:adapter:android`; the module is test-only and never linked into a build.
 */
internal class PartialGrantRun {

    private val context: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val foreground by lazy { ForegroundActivity(context.applicationContext as Application) }
    private val seeded = mutableSetOf<PlatformAssetId>()

    private fun permission() = AndroidPhotoPermission(context, foreground)

    private val gallery = object : Binding<GalleryState, GalleryChange> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val adapter = AndroidGallery::class
        override val reaches = setOf(GalleryState.PARTIAL)

        override fun create(state: GalleryState, clauseId: String, log: CallLog): Entered<GalleryChange> {
            if (state !in reaches) return Entered.Unreachable("this APK holds a partial grant, and only that")
            check(holds(SELECTION) && !holds(Manifest.permission.READ_MEDIA_IMAGES)) { "this APK holds no partial grant" }
            val selection = setOf(seedOwnPhoto(clauseId))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val adapter = AndroidGallery(context, permission(), scope).recorded(log)
            return Entered.Ready(GalleryChange(adapter, selection) {}) {}
        }
    }

    private val photoAccess = object : Binding<PhotoAccessState, PhotoAccess> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val adapter = AndroidGallery::class
        override val reaches = setOf(PhotoAccessState.PARTIAL)

        override fun create(state: PhotoAccessState, clauseId: String, log: CallLog): Entered<PhotoAccess> {
            if (state !in reaches) return Entered.Unreachable("this APK holds a partial grant, and only that")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            return Entered.Ready(PhotoAccess(AndroidGallery(context, permission(), scope).recorded(log)))
        }
    }

    /** The library reader over the same grant read the composition hands it: the permission adapter's. */
    private val galleryReader = object : Binding<GalleryReaderState, SeededLibrary<GalleryReader>> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val adapter = AndroidGalleryReader::class
        override val reaches = setOf(GalleryReaderState.PARTIAL)

        override fun create(state: GalleryReaderState, clauseId: String, log: CallLog): Entered<SeededLibrary<GalleryReader>> {
            if (state !in reaches) return Entered.Unreachable("this APK holds a partial grant, and only that")
            return Entered.Ready(SeededLibrary(AndroidGalleryReader(context, permission()::current).recorded(log)))
        }
    }

    fun run() {
        try {
            verify(GalleryContract, gallery)
            verify(PhotoAccessContract, photoAccess)
            verify(GalleryReaderContract, galleryReader)
        } finally {
            seeded.forEach { id ->
                context.contentResolver.delete(
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY).buildUpon()
                        .appendPath(id.value).build(),
                    null,
                    null,
                )
            }
        }
    }

    private fun holds(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** A photo this APK saves itself into the default gallery — its own, so a partial grant always shows it. */
    private fun seedOwnPhoto(clauseId: String): PlatformAssetId {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "partial-${System.nanoTime()}.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Camera/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = checkNotNull(
            resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values),
        ) { "$clauseId: the photo could not be saved" }
        resolver.openOutputStream(uri).use { checkNotNull(it).write(PhotoLibrary.jpeg) }
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        return PlatformAssetId(uri.lastPathSegment!!).also { seeded += it }
    }

    private companion object {
        const val SELECTION = Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
    }
}
