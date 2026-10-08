package app.snapsync.android.gallery

import android.app.Application
import android.content.ContentUris
import android.os.Environment
import android.provider.MediaStore
import app.snapsync.android.permission.AndroidPhotoPermission
import app.snapsync.android.scene.ForegroundActivity
import app.snapsync.android.storage.context
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.ClauseFiles
import app.snapsync.contracts.Entered
import app.snapsync.contracts.FolderAlbumContract
import app.snapsync.contracts.FolderAlbumState
import app.snapsync.contracts.FolderAlbums
import app.snapsync.contracts.GalleryChange
import app.snapsync.contracts.GalleryContract
import app.snapsync.contracts.GalleryReaderContract
import app.snapsync.contracts.GalleryReaderState
import app.snapsync.contracts.GalleryState
import app.snapsync.contracts.Host
import app.snapsync.contracts.PhotoAccess
import app.snapsync.contracts.PhotoAccessContract
import app.snapsync.contracts.PhotoAccessState
import app.snapsync.contracts.PhotoLibrary
import app.snapsync.contracts.SeededLibrary
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.model.AssetId
import app.snapsync.model.GalleryAccess
import app.snapsync.model.ResourceRole
import app.snapsync.model.StagedResource
import app.snapsync.ports.GalleryHandlers
import app.snapsync.ports.GalleryReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.File
import kotlin.test.Test

/**
 * The photo-library contracts against the real MediaStore adapters, under the full grant this APK gives itself.
 *
 * `NO_GRANT` is not reachable here: the grant is the process's, and revoking a runtime permission kills the process
 * holding it — the test run with it. The iOS test executable, which can hold no grant at all, covers those clauses.
 * The collection album clauses are iOS's: an Android album is a folder, and its clauses are [FolderAlbumContract]'s —
 * bound here over photos this APK seeds itself, which are therefore its own to move.
 */
class AndroidGalleryContractTest {

    private val foreground = ForegroundActivity(context.applicationContext as Application)

    private fun permission() = AndroidPhotoPermission(context, foreground)

    private val galleryReader = object : Binding<GalleryReaderState, SeededLibrary<GalleryReader>> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val grant = GalleryAccess.GRANTED
        override val reaches = setOf(
            GalleryReaderState.GRANTED_SEEDED,
            GalleryReaderState.GRANTED_EMPTY_WINDOW,
            GalleryReaderState.GRANTED_SEEDED_IN_A_FOLDER,
            GalleryReaderState.GRANTED_SEEDED_OUTSIDE_THE_DEFAULT_GALLERY,
            GalleryReaderState.GRANTED_SEEDED_EXPORTING,
        )

        override fun create(state: GalleryReaderState, clauseId: String, log: CallLog): Entered<SeededLibrary<GalleryReader>> {
            val date = PhotoLibrary.window(GalleryReaderContract.name, clauseId).seedDate
            val ids: Set<AssetId> = when (state) {
                GalleryReaderState.NO_GRANT, GalleryReaderState.REFUSING_WRITES ->
                    return Entered.Unreachable("the grant is the process's, and revoking it kills the process")
                GalleryReaderState.GRANTED_SEEDED_COLLECTION_ALBUMS ->
                    return Entered.Unreachable(
                        "an Android album is the folder a photo lives in, not a collection: FolderAlbumContract",
                    )
                GalleryReaderState.GRANTED_EMPTY_WINDOW -> emptySet()
                GalleryReaderState.GRANTED_SEEDED, GalleryReaderState.GRANTED_SEEDED_EXPORTING ->
                    MediaStoreSeeder.seed(MediaStoreSeeder.CAMERA, date)
                GalleryReaderState.GRANTED_SEEDED_IN_A_FOLDER ->
                    MediaStoreSeeder.seed("DCIM/${GalleryReaderContract.title(clauseId)}/", date)
                GalleryReaderState.GRANTED_SEEDED_OUTSIDE_THE_DEFAULT_GALLERY ->
                    MediaStoreSeeder.seed("Pictures/${GalleryReaderContract.title(clauseId)}/", date)
            }
            MediaStoreSeeder.grantFull()
            val reader = AndroidGalleryReader(context, permission()::current)
            val scratch = File(context.cacheDir, "export-$clauseId").apply { mkdirs() }
            val files = ClauseFiles({ File(scratch, it).absolutePath }, { File(it).takeIf(File::exists)?.readBytes() })
            return Entered.Ready(SeededLibrary(reader, ids, files)) {
                MediaStoreSeeder.delete(ids)
                scratch.deleteRecursively()
            }
        }
    }

    private val gallery = object : Binding<GalleryState, GalleryChange> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val grant = GalleryAccess.GRANTED
        override val reaches = setOf(GalleryState.GRANTED)

        override fun create(state: GalleryState, clauseId: String, log: CallLog): Entered<GalleryChange> {
            if (state !in reaches) {
                return Entered.Unreachable(
                    "this binding holds the full grant: a grant short of it is another APK's",
                )
            }
            MediaStoreSeeder.grantFull()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val created = mutableSetOf<AssetId>()
            val date = PhotoLibrary.window(GalleryContract.name, clauseId).seedDate
            val subject = GalleryChange(AndroidGallery(context, permission(), scope).recorded(log)) {
                created += MediaStoreSeeder.seed(MediaStoreSeeder.CAMERA, date, count = 1)
            }
            return Entered.Ready(subject) {
                MediaStoreSeeder.delete(created)
                scope.cancel()
            }
        }
    }

    private val folderAlbums = object : Binding<FolderAlbumState, FolderAlbums> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val grant = GalleryAccess.GRANTED
        override val reaches = setOf(FolderAlbumState.GRANTED_OWN_PHOTOS_SEEDED)

        override fun create(state: FolderAlbumState, clauseId: String, log: CallLog): Entered<FolderAlbums> {
            MediaStoreSeeder.grantFull()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val imported = mutableSetOf<AssetId>()
            val gallery = AndroidGallery(context, permission(), scope).recorded(log).apply {
                listen(
                    GalleryHandlers(
                        onChanged = {},
                        onImportPlaceholder = { _, id -> imported += id },
                        onImportSettled = { _, _ -> },
                    ),
                )
            }
            val seeded = MediaStoreSeeder.seed(
                MediaStoreSeeder.CAMERA,
                PhotoLibrary.window(FolderAlbumContract.name, clauseId).seedDate,
            )
            val staging = File(context.filesDir, "folder-album-staging").apply { mkdirs() }
            val stage = {
                val file = File(staging, "${System.nanoTime()}.jpg").apply { writeBytes(PhotoLibrary.jpeg) }
                listOf(
                    StagedResource(
                        "key-$clauseId",
                        ResourceRole.PRIMARY.wire,
                        "image/jpeg",
                        "IMG_0001.JPG",
                        file.absolutePath,
                    ),
                )
            }
            return Entered.Ready(FolderAlbums(gallery, seeded, stage)) {
                MediaStoreSeeder.delete(seeded)
                imported.forEach { id ->
                    context.contentResolver.delete(
                        ContentUris.withAppendedId(
                            MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                            id.value.toLong(),
                        ),
                        null,
                        null,
                    )
                }
                staging.deleteRecursively()
                // The clause's album folders, now empty: named for the contract, so nothing else is touched.
                File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "SnapSync")
                    .listFiles { f -> f.name.startsWith(FolderAlbumContract.title(clauseId)) }
                    ?.forEach { it.delete() }
                scope.cancel()
            }
        }
    }

    private val photoAccess = object : Binding<PhotoAccessState, PhotoAccess> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(PhotoAccessState.GRANTED)

        override fun create(state: PhotoAccessState, clauseId: String, log: CallLog): Entered<PhotoAccess> {
            if (state !in reaches) {
                return Entered.Unreachable(
                    "this binding holds the full grant: a grant short of it is another APK's",
                )
            }
            if (state == PhotoAccessState.NO_GRANT) {
                return Entered.Unreachable("the grant is the process's, and revoking it kills the process")
            }
            MediaStoreSeeder.grantFull()
            return Entered.Ready(PhotoAccess(permission().recorded(log)))
        }
    }

    @Test
    fun `MediaStore satisfies the GalleryReader contract`() = verify(GalleryReaderContract, galleryReader)

    @Test
    fun `MediaStore satisfies the Gallery contract`() = verify(GalleryContract, gallery)

    @Test
    fun `MediaStore’s event-album folders satisfy the FolderAlbum contract`() = verify(
        FolderAlbumContract,
        folderAlbums,
    )

    @Test
    fun `the Android permission adapter satisfies the PhotoAccess contract`() = verify(PhotoAccessContract, photoAccess)
}
