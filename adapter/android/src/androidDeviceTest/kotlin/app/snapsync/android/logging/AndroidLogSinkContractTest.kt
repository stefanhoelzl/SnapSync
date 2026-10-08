package app.snapsync.android.logging

import app.snapsync.android.storage.newTempDirectory
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.LogSinkContract
import app.snapsync.contracts.LogSinkState
import app.snapsync.contracts.WrittenLog
import app.snapsync.contracts.verify
import java.io.File
import kotlin.test.Test

/** The app's own log file — [FileLogSink], what a bug report's log reads — against the [LogSinkContract]. */
class AndroidLogSinkContractTest {

    private val binding = object : Binding<LogSinkState, WrittenLog> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val reaches = setOf(LogSinkState.READABLE)
        override fun create(state: LogSinkState, clauseId: String): Entered<WrittenLog> {
            val dir = newTempDirectory()
            val file = File(dir, "debug.log")
            return Entered.Ready(WrittenLog(FileLogSink(file)) { file.takeIf(File::exists)?.readText().orEmpty() }) {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun `the app’s log file satisfies the LogSink contract`() = verify(LogSinkContract, binding)
}
