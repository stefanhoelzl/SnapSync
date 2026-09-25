@file:OptIn(ExperimentalForeignApi::class)

package app.snapsync.files

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.FilesContract
import app.snapsync.contracts.FilesState
import app.snapsync.contracts.Host
import app.snapsync.contracts.verify
import app.snapsync.model.FileArea
import app.snapsync.ports.Files
import app.snapsync.testsupport.newTempDirectory
import app.snapsync.testsupport.removeDirectory
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.chmod
import kotlin.test.Test

/**
 * [FilesContract] against the real [IosFiles], two temp directories standing for the two areas. [FilesState.DENIED]
 * is a real permission failure (mode 000: `NSCocoaErrorDomain` 257 over `EACCES`, measured on this executable
 * 2026-09-23 by the config contract), and [FilesState.UNAVAILABLE] this unentitled binary's real, missing App-Group
 * container.
 */
class IosFilesContractTest {

    private val binding = object : Binding<FilesState, Files> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(FilesState.EMPTY, FilesState.HOLDING, FilesState.DENIED, FilesState.UNAVAILABLE)

        override fun create(state: FilesState, clauseId: String): Entered<Files> {
            if (state == FilesState.UNAVAILABLE) return Entered.Ready(IosFiles())
            val shared = newTempDirectory()
            val private = newTempDirectory()
            val files = IosFiles(sharedRoot = shared, privateRoot = private)
            val path = FilesContract.path(clauseId)
            when (state) {
                FilesState.HOLDING -> files.write(FileArea.SHARED, path, FilesContract.seed(clauseId))
                FilesState.DENIED -> {
                    files.write(FileArea.SHARED, path, FilesContract.seed(clauseId))
                    chmod("$shared/$path", NO_PERMISSIONS)
                }
                FilesState.EMPTY, FilesState.UNAVAILABLE -> Unit
            }
            return Entered.Ready(files) {
                chmod("$shared/$path", OWNER_READ_WRITE)
                removeDirectory(shared)
                removeDirectory(private)
            }
        }
    }

    @Test
    fun `the iOS file system satisfies the Files contract`() = verify(FilesContract, binding)

    private companion object {
        const val NO_PERMISSIONS: UShort = 0u
        const val OWNER_READ_WRITE: UShort = 0x180u // 0600
    }
}
