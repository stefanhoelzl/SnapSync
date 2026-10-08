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
import java.io.File
import java.nio.file.Files as Nio
import kotlin.test.Test

/**
 * The `Files` contract against the real [JvmFiles], so every `./gradlew build` runs it beside its iOS binding (which
 * only CI's simulator job runs). Each clause gets its own two directories.
 */
class JvmFilesContractTest {

    private class Areas {
        val shared: File = Nio.createTempDirectory("shared").toFile()
        val private: File = Nio.createTempDirectory("private").toFile()
        val files = JvmFiles(shared, private)
        fun dispose() {
            shared.walkTopDown().forEach { it.setReadable(true) }
            shared.deleteRecursively()
            private.deleteRecursively()
        }
    }

    /** The unentitled case: no shared area at all. */
    private fun unavailable(): Files = JvmFiles(shared = null, private = Nio.createTempDirectory("private").toFile())

    private val files = object : Binding<FilesState, Files> {
        override val host = Host.JVM
        override val kind = BindingKind.Live
        override val reaches = setOf(
            FilesState.EMPTY,
            FilesState.HOLDING,
            FilesState.DENIED,
            FilesState.DENIED_DIRECTORY,
            FilesState.UNAVAILABLE,
        )
        override fun create(state: FilesState, clauseId: String, log: CallLog): Entered<Files> {
            if (state == FilesState.UNAVAILABLE) return Entered.Ready(unavailable().recorded(log))
            val areas = Areas()
            val path = FilesContract.path(clauseId)
            when (state) {
                FilesState.HOLDING -> areas.files.write(FileArea.SHARED, path, FilesContract.seed(clauseId))
                FilesState.DENIED -> {
                    areas.files.write(FileArea.SHARED, path, FilesContract.seed(clauseId))
                    File(areas.shared, path).setReadable(false)
                }
                FilesState.DENIED_DIRECTORY -> {
                    areas.files.write(FileArea.SHARED, path, FilesContract.seed(clauseId))
                    File(areas.shared, FilesContract.directory(clauseId)).setReadable(false)
                }
                FilesState.EMPTY, FilesState.UNAVAILABLE -> Unit
            }
            return Entered.Ready(areas.files.recorded(log), areas::dispose)
        }
    }

    @Test
    fun `the JVM file system satisfies the Files contract`() = verify(FilesContract, files)
}
