package app.snapsync.logging

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.EntryContextContract
import app.snapsync.contracts.EntryContextState
import app.snapsync.contracts.Host
import app.snapsync.contracts.LogSinkContract
import app.snapsync.contracts.LogSinkState
import app.snapsync.contracts.WrittenLog
import app.snapsync.contracts.verify
import app.snapsync.ports.EntryContext
import app.snapsync.testsupport.newTempDirectory
import app.snapsync.testsupport.readTextFile
import app.snapsync.testsupport.removeDirectory
import kotlin.test.Test

/**
 * The iOS device-log seams in the simulator's test executable: the ambient entry context both processes tag their
 * lines with ([IosEntryContext]) and the file sink each writes its own log through ([FileLogSink]), over a temp file.
 */
class IosLoggingContractTest {

    private val context = object : Binding<EntryContextState, EntryContext> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(EntryContextState.AMBIENT)
        override fun create(state: EntryContextState, clauseId: String): Entered<EntryContext> =
            if (state in reaches) {
                Entered.Ready(IosEntryContext)
            } else {
                Entered.Unreachable("an iOS process tags every line with its entry point")
            }
    }

    private val sink = object : Binding<LogSinkState, WrittenLog> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val reaches = setOf(LogSinkState.READABLE)
        override fun create(state: LogSinkState, clauseId: String): Entered<WrittenLog> {
            val dir = newTempDirectory()
            val path = "$dir/debug.log"
            return Entered.Ready(
                WrittenLog(FileLogSink(path)) { readTextFile(path).orEmpty() },
            ) { removeDirectory(dir) }
        }
    }

    @Test
    fun `the ambient entry context satisfies the EntryContext contract`() = verify(EntryContextContract, context)

    @Test
    fun `the device-log file satisfies the LogSink contract`() = verify(LogSinkContract, sink)
}
