@file:OptIn(ExperimentalForeignApi::class)

package app.snapsync.files

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.FilesContract
import app.snapsync.contracts.FilesState
import app.snapsync.contracts.Host
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.model.FileArea
import app.snapsync.ports.Files
import app.snapsync.testsupport.newTempDirectory
import app.snapsync.testsupport.removeDirectory
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.posix.chmod
import platform.posix.symlink
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
        override val reaches = setOf(
            FilesState.EMPTY,
            FilesState.HOLDING,
            FilesState.DENIED,
            FilesState.DENIED_DIRECTORY,
            FilesState.UNAVAILABLE,
            FilesState.READ_ONLY_DIRECTORY,
            FilesState.UNSEARCHABLE_DIRECTORY,
            FilesState.LOOPED_LINK,
        )

        override fun create(state: FilesState, clauseId: String, log: CallLog): Entered<Files> {
            if (state == FilesState.UNAVAILABLE) return Entered.Ready(IosFiles())
            val shared = newTempDirectory()
            val private = newTempDirectory()
            val files = IosFiles(sharedRoot = shared, privateRoot = private).recorded(log)
            val path = FilesContract.path(clauseId)
            val directory = "$shared/${FilesContract.directory(clauseId)}"
            if (state != FilesState.EMPTY && state != FilesState.LOOPED_LINK) {
                files.write(FileArea.SHARED, path, FilesContract.seed(clauseId))
            }
            when (state) {
                FilesState.DENIED -> chmod("$shared/$path", NO_PERMISSIONS)
                FilesState.DENIED_DIRECTORY -> chmod(directory, OWNER_SEARCH_ONLY)
                FilesState.READ_ONLY_DIRECTORY -> chmod(directory, OWNER_READ_SEARCH)
                FilesState.UNSEARCHABLE_DIRECTORY -> chmod(directory, NO_PERMISSIONS)
                FilesState.LOOPED_LINK -> {
                    NSFileManager.defaultManager.createDirectoryAtPath(directory, true, null, null)
                    symlink("file.bin", "$shared/$path")
                }
                FilesState.EMPTY, FilesState.HOLDING, FilesState.UNAVAILABLE -> Unit
            }
            return Entered.Ready(files) {
                chmod(directory, OWNER_ALL)
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
        const val OWNER_ALL: UShort = 0x1C0u // 0700
        const val OWNER_READ_SEARCH: UShort = 0x140u // 0500
        const val OWNER_SEARCH_ONLY: UShort = 0x40u // 0100
    }
}
