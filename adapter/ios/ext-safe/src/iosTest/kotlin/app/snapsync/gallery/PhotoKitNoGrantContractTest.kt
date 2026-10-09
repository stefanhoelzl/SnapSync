package app.snapsync.gallery

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.GalleryReaderContract
import app.snapsync.contracts.GalleryReaderState
import app.snapsync.contracts.Host
import app.snapsync.contracts.SeededLibrary
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.model.GalleryAccess
import app.snapsync.ports.GalleryReader
import co.touchlab.kermit.Logger
import kotlin.test.Test

/**
 * The real PhotoKit gallery, live, in the simulator's Kotlin/Native test executable (`docs/architecture.md`). That
 * process has no bundle identifier, so no photo grant can reach it, and the only state it presents is `NO_GRANT`.
 * Every granted state runs in the simulator app instead.
 *
 * A grant here is not assumed. The binding reads the process's real grant, and a process that turned out to hold
 * one would answer `Unreachable` for the state it declares, which the runner reports as `Failed`.
 */
class PhotoKitNoGrantContractTest {

    private val unreachable = "the Kotlin/Native test executable has no bundle identifier, so no photo grant reaches it"

    /** Whether this process holds no grant, as the adapters' own read reports it. */
    private fun holdsNoGrant(): Boolean = currentPhotoPermission().let {
        it == GalleryAccess.NOT_DETERMINED || it == GalleryAccess.DENIED
    }

    private val galleryReader = object : Binding<GalleryReaderState, SeededLibrary<GalleryReader>> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live

        // With no grant, the library refuses this process's writes too (measured on this host: `createAlbum` → null).
        // This process reads refused, as one the member refused does (the Gallery binding's TOKEN_WITHHELD rests on it).
        override val reaches =
            setOf(GalleryReaderState.NO_GRANT, GalleryReaderState.REFUSED, GalleryReaderState.REFUSING_WRITES)

        override fun create(state: GalleryReaderState, clauseId: String, log: CallLog): Entered<SeededLibrary<GalleryReader>> {
            if (state !in reaches || !holdsNoGrant()) return Entered.Unreachable(unreachable)
            if (state == GalleryReaderState.REFUSED && currentPhotoPermission() != GalleryAccess.DENIED) {
                return Entered.Unreachable(unreachable)
            }
            return Entered.Ready(SeededLibrary(IosGalleryReader(Logger.withTag("contract")).recorded(log)))
        }
    }

    @Test
    fun `IosGalleryReader satisfies the GalleryReader contract on this host`() =
        verify(GalleryReaderContract, galleryReader)
}
