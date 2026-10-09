package app.snapsync.permission

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
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.gallery.IosGallery
import app.snapsync.gallery.IosGalleryReader
import app.snapsync.gallery.currentPhotoPermission
import app.snapsync.model.GalleryAccess
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test

/**
 * The app-only photo-permission adapter, live, in the simulator's Kotlin/Native test executable (capability
 * `docs/architecture.md`). That process has no bundle identifier, so no photo grant can reach it, and the only
 * state it presents is `NO_GRANT`. Every granted state runs in the simulator app instead.
 */
class PhotoKitNoGrantContractTest {

    private val unreachable = "the Kotlin/Native test executable has no bundle identifier, so no photo grant reaches it"

    private fun holdsNoGrant(): Boolean = currentPhotoPermission().let {
        it == GalleryAccess.NOT_DETERMINED || it == GalleryAccess.DENIED
    }

    private val photoAccess = object : Binding<PhotoAccessState, PhotoAccess> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live

        // This process reads refused, as one the member refused does (the Gallery binding's TOKEN_WITHHELD rests on it).
        override val reaches = setOf(PhotoAccessState.NO_GRANT, PhotoAccessState.REFUSED)

        override fun create(state: PhotoAccessState, clauseId: String, log: CallLog): Entered<PhotoAccess> {
            val holds = when (state) {
                PhotoAccessState.NO_GRANT -> holdsNoGrant()
                PhotoAccessState.REFUSED -> currentPhotoPermission() == GalleryAccess.DENIED
                else -> false
            }
            if (!holds) return Entered.Unreachable(unreachable)
            val adapter = PhotoLibraryPermission().recorded(log)
            return Entered.Ready(PhotoAccess(adapter))
        }
    }

    /** The app's gallery in a process refused photo access: PhotoKit withholds even the change token (measured). */
    private val gallery = object : Binding<GalleryState, GalleryChange> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(GalleryState.TOKEN_WITHHELD)

        override fun create(state: GalleryState, clauseId: String, log: CallLog): Entered<GalleryChange> {
            if (state !in reaches || currentPhotoPermission() != GalleryAccess.DENIED) {
                return Entered.Unreachable(
                    unreachable,
                )
            }
            val reader = IosGalleryReader(Logger.withTag("contract"))
            val gallery = IosGallery(reader, PhotoLibraryPermission(), CoroutineScope(Dispatchers.Default))
            return Entered.Ready(GalleryChange(gallery.recorded(log)) {})
        }
    }

    @Test
    fun `PhotoLibraryPermission satisfies the PhotoAccess contract on this host`() =
        verify(PhotoAccessContract, photoAccess)

    @Test
    fun `the gallery satisfies the Gallery contract on this host`() = verify(GalleryContract, gallery)
}
