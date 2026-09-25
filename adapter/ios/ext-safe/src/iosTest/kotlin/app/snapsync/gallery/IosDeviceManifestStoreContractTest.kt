package app.snapsync.gallery

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.DeviceManifestStoreContract
import app.snapsync.contracts.DeviceManifestStoreState
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.verify
import app.snapsync.files.IosFiles
import app.snapsync.services.manifest.DeviceManifestService
import app.snapsync.ports.DeviceManifestStore
import app.snapsync.testsupport.newTempDirectory
import app.snapsync.testsupport.removeDirectory
import kotlin.test.Test

/**
 * The App-Group manifest record, live (`docs/architecture.md`). Readable states get a fresh container
 * directory, seeded through the adapter's own write so the layout is the adapter's, not this test's.
 * [DeviceManifestStoreState.UNAVAILABLE] is the DEFAULT container, which this unentitled executable's
 * App-Group lookup answers with `nil` — the degraded, never-raising cache the port promises.
 */
class IosDeviceManifestStoreContractTest {

    private val binding = object : Binding<DeviceManifestStoreState, DeviceManifestStore> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(
            DeviceManifestStoreState.UNAVAILABLE,
            DeviceManifestStoreState.EMPTY,
            DeviceManifestStoreState.HOLDING,
        )

        override fun create(state: DeviceManifestStoreState, clauseId: String): Entered<DeviceManifestStore> {
            if (state == DeviceManifestStoreState.UNAVAILABLE) return Entered.Ready(DeviceManifestService(IosFiles()))
            val dir = newTempDirectory()
            if (state == DeviceManifestStoreState.HOLDING) {
                DeviceManifestService(IosFiles(dir, null)).saveLastUploaded(DeviceManifestStoreContract.seedJson(clauseId))
            }
            return Entered.Ready(DeviceManifestService(IosFiles(dir, null))) { removeDirectory(dir) }
        }
    }

    @Test
    fun `the App-Group manifest record satisfies the DeviceManifestStore contract`() =
        verify(DeviceManifestStoreContract, binding)
}
