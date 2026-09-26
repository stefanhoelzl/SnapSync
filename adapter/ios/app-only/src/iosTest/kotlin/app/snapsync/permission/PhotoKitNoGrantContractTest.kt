package app.snapsync.permission

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.PhotoAccess
import app.snapsync.contracts.PhotoAccessContract
import app.snapsync.contracts.PhotoAccessState
import app.snapsync.contracts.verify
import app.snapsync.gallery.currentPhotoPermission
import app.snapsync.model.GalleryAccess
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
        override val reaches = setOf(PhotoAccessState.NO_GRANT)

        override fun create(state: PhotoAccessState, clauseId: String): Entered<PhotoAccess> {
            if (state != PhotoAccessState.NO_GRANT || !holdsNoGrant()) return Entered.Unreachable(unreachable)
            val adapter = PhotoLibraryPermission()
            return Entered.Ready(PhotoAccess(adapter))
        }
    }

    @Test
    fun `PhotoLibraryPermission satisfies the PhotoAccess contract on this host`() =
        verify(PhotoAccessContract, photoAccess)
}
