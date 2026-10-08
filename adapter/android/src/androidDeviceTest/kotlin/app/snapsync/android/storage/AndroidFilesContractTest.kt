package app.snapsync.android.storage

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
import java.io.File
import kotlin.test.Test
import java.nio.file.Files as Nio

/**
 * [FilesContract] against the real [AndroidFiles] on ART, two temp directories standing for the two areas.
 * [FilesState.DENIED] is a real permission failure — the file's mode stripped, owner included, so this non-root
 * process's `open` answers `EACCES` — which is the case `java.io` would have reported as a missing file.
 * [FilesState.UNAVAILABLE] cannot arise: both areas are app-private directories, always there.
 */
class AndroidFilesContractTest {

    private val binding = object : Binding<FilesState, Files> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(
            FilesState.EMPTY,
            FilesState.HOLDING,
            FilesState.DENIED,
            FilesState.DENIED_DIRECTORY,
            FilesState.READ_ONLY_DIRECTORY,
            FilesState.UNSEARCHABLE_DIRECTORY,
            FilesState.LOOPED_LINK,
        )

        override fun create(state: FilesState, clauseId: String, log: CallLog): Entered<Files> {
            if (state == FilesState.UNAVAILABLE) {
                return Entered.Unreachable(
                    "both areas are app-private directories, always reachable",
                )
            }
            val shared = newTempDirectory()
            val private = newTempDirectory()
            val files = AndroidFiles(sharedRoot = shared, privateRoot = private).recorded(log)
            val path = FilesContract.path(clauseId)
            val directory = File(shared, FilesContract.directory(clauseId))
            if (state != FilesState.EMPTY && state != FilesState.LOOPED_LINK) {
                files.write(FileArea.SHARED, path, FilesContract.seed(clauseId))
            }
            when (state) {
                FilesState.DENIED -> revokeAllAccess(File(shared, path))
                FilesState.DENIED_DIRECTORY -> directory.setReadable(false, false)
                FilesState.READ_ONLY_DIRECTORY -> directory.setWritable(false, false)
                FilesState.UNSEARCHABLE_DIRECTORY -> revokeAllAccess(directory)
                FilesState.LOOPED_LINK -> File(shared, path).toPath().let { link ->
                    Nio.createDirectories(link.parent)
                    Nio.createSymbolicLink(link, link.fileName)
                }
                FilesState.EMPTY, FilesState.HOLDING, FilesState.UNAVAILABLE -> Unit
            }
            return Entered.Ready(files) {
                restoreOwnerAccess(directory)
                restoreOwnerAccess(File(shared, path))
                shared.deleteRecursively()
                private.deleteRecursively()
            }
        }
    }

    @Test
    fun `the Android file system satisfies the Files contract`() = verify(FilesContract, binding)
}
