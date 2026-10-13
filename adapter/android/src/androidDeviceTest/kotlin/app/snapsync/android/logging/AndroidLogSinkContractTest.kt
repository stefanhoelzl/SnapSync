package app.snapsync.android.logging

import app.snapsync.android.storage.newTempDirectory
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.LogSinkContract
import app.snapsync.contracts.LogSinkState
import app.snapsync.contracts.WrittenLog
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import java.io.File
import kotlin.test.Test

/** The app's own log file — [FileLogSink], what a bug report's log reads — against the [LogSinkContract]. */
class AndroidLogSinkContractTest {

    private val binding = object : Binding<LogSinkState, WrittenLog> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val adapter = FileLogSink::class
        override val reaches = setOf(LogSinkState.READABLE)
        override fun create(state: LogSinkState, clauseId: String, log: CallLog): Entered<WrittenLog> {
            val dir = newTempDirectory()
            val file = File(dir, "debug.log")
            val sink = FileLogSink(file).recorded(log)
            return Entered.Ready(WrittenLog(sink) { file.takeIf(File::exists)?.readText().orEmpty() }) {
                dir.deleteRecursively()
            }
        }
    }

    @Test
    fun `the app’s log file satisfies the LogSink contract`() = verify(LogSinkContract, binding)

    /**
     * [LogcatSink] — what `adb logcat` shows of the app — read back as logcat holds it: this process's own lines, which
     * an app may read without any permission. Cleared first, so a clause sees only its own.
     */
    private val logcat = object : Binding<LogSinkState, WrittenLog> {
        override val host = Host.ANDROID_EMU
        override val kind = BindingKind.Live
        override val adapter = LogcatSink::class
        override val reaches = setOf(LogSinkState.READABLE)
        override fun create(state: LogSinkState, clauseId: String, log: CallLog): Entered<WrittenLog> {
            logcat("-c")
            return Entered.Ready(WrittenLog(LogcatSink().recorded(log)) { logcat("-d", "-v", "raw", "-s", "SnapSync") })
        }
    }

    @Test
    fun `logcat satisfies the LogSink contract`() = verify(LogSinkContract, logcat)

    private fun logcat(vararg args: String): String {
        val process = ProcessBuilder(listOf("logcat") + args).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return out
    }
}
