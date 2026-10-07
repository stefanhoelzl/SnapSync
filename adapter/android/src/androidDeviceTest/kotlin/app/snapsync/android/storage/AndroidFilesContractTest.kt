package app.snapsync.android.storage

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.FilesContract
import app.snapsync.contracts.FilesState
import app.snapsync.contracts.Host
import app.snapsync.contracts.verify
import app.snapsync.model.FileArea
import app.snapsync.ports.Files
import java.io.File
import kotlin.test.Test

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
        override val reaches = setOf(FilesState.EMPTY, FilesState.HOLDING, FilesState.DENIED)

        override fun create(state: FilesState, clauseId: String): Entered<Files> {
            if (state == FilesState.UNAVAILABLE) {
                return Entered.Unreachable(
                    "both areas are app-private directories, always reachable",
                )
            }
            val shared = newTempDirectory()
            val private = newTempDirectory()
            val files = AndroidFiles(sharedRoot = shared, privateRoot = private)
            val path = FilesContract.path(clauseId)
            when (state) {
                FilesState.HOLDING -> files.write(FileArea.SHARED, path, FilesContract.seed(clauseId))
                FilesState.DENIED -> {
                    files.write(FileArea.SHARED, path, FilesContract.seed(clauseId))
                    revokeAllAccess(File(shared, path))
                }
                FilesState.EMPTY, FilesState.UNAVAILABLE -> Unit
            }
            return Entered.Ready(files) {
                restoreOwnerAccess(File(shared, path))
                shared.deleteRecursively()
                private.deleteRecursively()
            }
        }
    }

    @Test
    fun `the Android file system satisfies the Files contract`() = verify(FilesContract, binding)
}
