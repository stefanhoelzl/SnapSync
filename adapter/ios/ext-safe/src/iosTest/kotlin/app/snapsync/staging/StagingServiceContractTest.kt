package app.snapsync.staging

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.StagedBytesContract
import app.snapsync.contracts.StagedBytesState
import app.snapsync.contracts.verify
import app.snapsync.files.IosFiles
import app.snapsync.model.FileArea
import app.snapsync.services.staging.StagingService
import app.snapsync.testsupport.newTempDirectory
import app.snapsync.testsupport.removeDirectory
import kotlin.test.Test

/**
 * [StagedBytesContract] through [StagingService] over the real [IosFiles], in a directory standing for the App-Group
 * container ([StagedBytesState.UNAVAILABLE] is the unentitled test binary's own, real, missing container).
 */
class StagingServiceContractTest {

    private val binding = object : Binding<StagedBytesState, StagingService> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(StagedBytesState.UNAVAILABLE, StagedBytesState.EMPTY, StagedBytesState.STAGED)

        override fun create(state: StagedBytesState, clauseId: String): Entered<StagingService> {
            if (state == StagedBytesState.UNAVAILABLE) return Entered.Ready(StagingService(IosFiles()))
            val container = newTempDirectory()
            val files = IosFiles(sharedRoot = container, privateRoot = null)
            val staging = StagingService(files)
            if (state == StagedBytesState.STAGED) {
                StagedBytesContract.stagedNames(clauseId).forEach {
                    files.write(FileArea.SHARED, "${staging.stagingRoot()}/$it", "bytes:$it".encodeToByteArray())
                }
            }
            return Entered.Ready(staging) { removeDirectory(container) }
        }
    }

    @Test
    fun `the staging service satisfies the StagingService contract`() = verify(StagedBytesContract, binding)
}
