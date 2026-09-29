package app.snapsync.android.gallery

import android.app.Application
import app.snapsync.android.permission.AndroidPhotoPermission
import app.snapsync.android.scene.ForegroundActivity
import app.snapsync.android.storage.context
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
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
import app.snapsync.contracts.verify
import app.snapsync.model.AssetId
import app.snapsync.model.GalleryAccess
import app.snapsync.ports.GalleryReader
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The photo-library contracts against the real MediaStore adapters, under the full grant this APK gives itself.
 *
 * `NO_GRANT` is not reachable here: the grant is the process's, and revoking a runtime permission kills the process
 * holding it — the test run with it. The iOS test executable, which can hold no grant at all, covers those clauses.
 * The album WRITES are iOS's: Android files no photo into an album.
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
        )

        override fun create(state: GalleryReaderState, clauseId: String): Entered<SeededLibrary<GalleryReader>> {
            val date = PhotoLibrary.window(GalleryReaderContract.name, clauseId).seedDate
            val ids: Set<AssetId> = when (state) {
                GalleryReaderState.NO_GRANT ->
                    return Entered.Unreachable("the grant is the process's, and revoking it kills the process")
                GalleryReaderState.GRANTED_SEEDED_ALBUMS_WRITABLE ->
                    return Entered.Unreachable("Android files no photo into an album: an album is a folder")
                GalleryReaderState.GRANTED_EMPTY_WINDOW -> emptySet()
                GalleryReaderState.GRANTED_SEEDED -> MediaStoreSeeder.seed(MediaStoreSeeder.CAMERA, date)
                GalleryReaderState.GRANTED_SEEDED_IN_A_FOLDER ->
                    MediaStoreSeeder.seed("DCIM/${GalleryReaderContract.title(clauseId)}/", date)
                GalleryReaderState.GRANTED_SEEDED_OUTSIDE_THE_DEFAULT_GALLERY ->
                    MediaStoreSeeder.seed("Pictures/${GalleryReaderContract.title(clauseId)}/", date)
            }
            MediaStoreSeeder.grantFull()
            val reader = AndroidGalleryReader(context, permission()::current)
            return Entered.Ready(SeededLibrary(reader, ids)) { MediaStoreSeeder.delete(ids) }
        }
    }

    private val gallery = object : Binding<GalleryState, GalleryChange> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val grant = GalleryAccess.GRANTED
        override val reaches = setOf(GalleryState.GRANTED)

        override fun create(state: GalleryState, clauseId: String): Entered<GalleryChange> {
            MediaStoreSeeder.grantFull()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val created = mutableSetOf<AssetId>()
            val date = PhotoLibrary.window(GalleryContract.name, clauseId).seedDate
            val subject = GalleryChange(AndroidGallery(context, permission(), scope)) {
                created += MediaStoreSeeder.seed(MediaStoreSeeder.CAMERA, date, count = 1)
            }
            return Entered.Ready(subject) {
                MediaStoreSeeder.delete(created)
                scope.cancel()
            }
        }
    }

    private val photoAccess = object : Binding<PhotoAccessState, PhotoAccess> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(PhotoAccessState.GRANTED)

        override fun create(state: PhotoAccessState, clauseId: String): Entered<PhotoAccess> {
            if (state == PhotoAccessState.NO_GRANT) {
                return Entered.Unreachable("the grant is the process's, and revoking it kills the process")
            }
            MediaStoreSeeder.grantFull()
            return Entered.Ready(PhotoAccess(permission()))
        }
    }

    @Test
    fun `MediaStore satisfies the GalleryReader contract`() = verify(GalleryReaderContract, galleryReader)

    @Test
    fun `MediaStore satisfies the Gallery contract`() = verify(GalleryContract, gallery)

    @Test
    fun `the Android permission adapter satisfies the PhotoAccess contract`() = verify(PhotoAccessContract, photoAccess)
}
