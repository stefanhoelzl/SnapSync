package app.snapsync.logging

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.LogSinkContract
import app.snapsync.contracts.LogSinkState
import app.snapsync.contracts.ThreadLabelContract
import app.snapsync.contracts.ThreadLabelState
import app.snapsync.contracts.WrittenLog
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.verify
import app.snapsync.ports.ThreadLabel
import app.snapsync.testsupport.StderrCapture
import app.snapsync.testsupport.newTempDirectory
import app.snapsync.testsupport.readTextFile
import app.snapsync.testsupport.removeDirectory
import kotlin.test.Test

/**
 * The iOS device-log seams in the simulator's test executable: the per-thread label both processes tag their lines
 * with ([IosThreadLabel]) and the file sink each writes its own log through ([FileLogSink]), over a temp file.
 */
class IosLoggingContractTest {

    private val label = object : Binding<ThreadLabelState, ThreadLabel> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val adapter = IosThreadLabel::class
        override val reaches = setOf(ThreadLabelState.EMPTY)
        override fun create(state: ThreadLabelState, clauseId: String, log: CallLog): Entered<ThreadLabel> =
            Entered.Ready(IosThreadLabel.recorded(log)) { IosThreadLabel.swap(null) }
    }

    private val sink = object : Binding<LogSinkState, WrittenLog> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val adapter = FileLogSink::class
        override val reaches = setOf(LogSinkState.READABLE)
        override fun create(state: LogSinkState, clauseId: String, log: CallLog): Entered<WrittenLog> {
            val dir = newTempDirectory()
            val path = "$dir/debug.log"
            return Entered.Ready(
                WrittenLog(FileLogSink(path).recorded(log)) { readTextFile(path).orEmpty() },
            ) { removeDirectory(dir) }
        }
    }

    /** The one-thread context MetricKit's callbacks claim ([IosThreadEntryContext]): the same promise, on its thread. */
    private val threadContext = object : Binding<EntryContextState, EntryContext> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val adapter = IosThreadEntryContext::class
        override val reaches = setOf(EntryContextState.AMBIENT)
        override fun create(state: EntryContextState, clauseId: String, log: CallLog): Entered<EntryContext> =
            if (state in reaches) {
                Entered.Ready(IosThreadEntryContext.recorded(log))
            } else {
                Entered.Unreachable("an iOS process tags every line with its entry point")
            }
    }

    /**
     * The public `NSLog` channel ([PublicNSLogSink]) read back from this process's stderr, where `NSLog` writes when
     * `CFLOG_FORCE_STDERR` is set — as this test executable's run sets it (`build.gradle.kts`). The stream is redirected
     * into a temp file for the clause and restored after.
     */
    private val nslog = object : Binding<LogSinkState, WrittenLog> {
        override val host = Host.IOS_SIM_KEXE
        override val kind = BindingKind.Live
        override val adapter = PublicNSLogSink::class
        override val reaches = setOf(LogSinkState.READABLE)
        override fun create(state: LogSinkState, clauseId: String, log: CallLog): Entered<WrittenLog> {
            val dir = newTempDirectory()
            val capture = StderrCapture("$dir/stderr.log")
            return Entered.Ready(WrittenLog(PublicNSLogSink().recorded(log)) { capture.read() }) {
                capture.restore()
                removeDirectory(dir)
            }
        }
    }

    @Test
    fun `the per-thread label satisfies the ThreadLabel contract`() = verify(ThreadLabelContract, label)

    @Test
    fun `the one-thread entry context satisfies the EntryContext contract`() = verify(
        EntryContextContract,
        threadContext,
    )

    @Test
    fun `the public NSLog channel satisfies the LogSink contract`() = verify(LogSinkContract, nslog)

    @Test
    fun `the device-log file satisfies the LogSink contract`() = verify(LogSinkContract, sink)
}
