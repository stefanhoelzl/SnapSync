package app.snapsync.permission

import app.snapsync.compose.PermissionAwareAssetPresence
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.ImportedAssetPresenceContract
import app.snapsync.contracts.ImportedAssetPresenceState
import app.snapsync.contracts.PhotoAccess
import app.snapsync.contracts.PhotoAccessContract
import app.snapsync.contracts.PhotoAccessState
import app.snapsync.contracts.SeededLibrary
import app.snapsync.contracts.verify
import app.snapsync.download.PhotoKitAssetPresence
import app.snapsync.gallery.currentPhotoPermission
import app.snapsync.model.PermissionStatus
import app.snapsync.model.Resource
import app.snapsync.ports.ImportedAssetPresence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test

/**
 * The app-only PhotoKit adapters, live, in the simulator's Kotlin/Native test executable (capability
 * `port-contracts`). That process has no bundle identifier, so no photo grant can reach it, and the only
 * state it presents is `NO_GRANT`. Every granted state runs in the simulator app instead.
 */
class PhotoKitNoGrantContractTest {

    private val unreachable = "the Kotlin/Native test executable has no bundle identifier, so no photo grant reaches it"

    private fun holdsNoGrant(): Boolean = currentPhotoPermission().let {
        it == PermissionStatus.NOT_DETERMINED || it == PermissionStatus.DENIED
    }

    private val presence = object : Binding<ImportedAssetPresenceState, SeededLibrary<ImportedAssetPresence>> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(ImportedAssetPresenceState.NO_GRANT)

        override fun create(
            state: ImportedAssetPresenceState,
            clauseId: String,
        ): Entered<SeededLibrary<ImportedAssetPresence>> {
            if (state != ImportedAssetPresenceState.NO_GRANT || !holdsNoGrant()) return Entered.Unreachable(unreachable)
            return Entered.Ready(
                SeededLibrary(
                    PermissionAwareAssetPresence(
                        permission = MutableStateFlow(currentPhotoPermission()),
                        library = PhotoKitAssetPresence(),
                        selection = MutableStateFlow<List<Resource>?>(null),
                    ),
                ),
            )
        }
    }

    private val photoAccess = object : Binding<PhotoAccessState, PhotoAccess> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(PhotoAccessState.NO_GRANT)

        override fun create(state: PhotoAccessState, clauseId: String): Entered<PhotoAccess> {
            if (state != PhotoAccessState.NO_GRANT || !holdsNoGrant()) return Entered.Unreachable(unreachable)
            val adapter = PhotoLibraryPermission()
            return Entered.Ready(PhotoAccess(adapter, adapter))
        }
    }

    @Test
    fun `the grant-aware PhotoKit presence satisfies the ImportedAssetPresence contract on this host`() =
        verify(ImportedAssetPresenceContract, presence)

    @Test
    fun `PhotoLibraryPermission satisfies the PhotoAccess contract on this host`() =
        verify(PhotoAccessContract, photoAccess)
}
