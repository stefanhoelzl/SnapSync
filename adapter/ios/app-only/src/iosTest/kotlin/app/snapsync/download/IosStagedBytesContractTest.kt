@file:OptIn(ExperimentalForeignApi::class)

package app.snapsync.download

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.StagedBytesContract
import app.snapsync.contracts.StagedBytesState
import app.snapsync.contracts.verify
import app.snapsync.ports.StagedBytes
import app.snapsync.testsupport.newTempDirectory
import app.snapsync.testsupport.removeDirectory
import app.snapsync.testsupport.writeTextFile
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import kotlin.test.Test

/**
 * The App-Group staged bytes, live (capability `port-contracts`). Readable states get a fresh container
 * directory; [StagedBytesState.UNAVAILABLE] is the adapter's DEFAULT container, which this unentitled
 * executable's App-Group lookup answers with `nil`.
 */
class IosStagedBytesContractTest {

    private val binding = object : Binding<StagedBytesState, StagedBytes> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(StagedBytesState.UNAVAILABLE, StagedBytesState.EMPTY, StagedBytesState.STAGED)

        override fun create(state: StagedBytesState, clauseId: String): Entered<StagedBytes> {
            if (state == StagedBytesState.UNAVAILABLE) return Entered.Ready(IosStagedBytes())
            val container = newTempDirectory()
            val bytes = IosStagedBytes { container }
            if (state == StagedBytesState.STAGED) {
                val root = bytes.stagingRoot()
                NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null)
                StagedBytesContract.stagedNames(clauseId).forEach { writeTextFile("$root/$it", "bytes:$it") }
            }
            return Entered.Ready(bytes) { removeDirectory(container) }
        }
    }

    @Test
    fun `the App-Group staging directory satisfies the StagedBytes contract`() = verify(StagedBytesContract, binding)
}
