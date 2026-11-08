package app.snapsync.data.process

import app.snapsync.data.crash.CrashChannel
import app.snapsync.domain.crash.CrashEvent
import app.snapsync.domain.crash.CrashOptions
import app.snapsync.domain.crash.CrashShaping
import app.snapsync.domain.crash.Crumb
import app.snapsync.domain.report.DumpHandOff
import app.snapsync.domain.report.DumpResult
import app.snapsync.inbound.CompositionOnly
import app.snapsync.inbound.Log
import app.snapsync.inbound.LogLine
import app.snapsync.inbound.LogWriter
import app.snapsync.inbound.ProcessLogging
import app.snapsync.mock.BuildInfoMock
import app.snapsync.mock.ThreadLabelMock
import app.snapsync.ports.CrashReporter
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** The process's first act, in its order: the log's writers, then crash reporting, then the banner. */
@OptIn(CompositionOnly::class)
class StartProcessTest {

    /** What happened, in order: a written line by its event, and the channel's start. */
    private val happened = mutableListOf<String>()

    private val writer = object : LogWriter {
        override fun write(line: LogLine) {
            happened += line.event
        }
    }

    /** A reporter that says, through the log, when it is started — so the order of the two is observable. */
    private val reporter = object : CrashReporter {
        override fun start(options: CrashOptions, shaping: CrashShaping) {
            happened += STARTED
            Log("reporter").unlabelled.i("reporter started")
        }

        override fun capture(event: CrashEvent) = Unit

        override fun breadcrumb(crumb: Crumb) = Unit

        override fun setContext(name: String, fields: Map<String, String>) = Unit

        override suspend fun sendDump(dump: CrashEvent): DumpHandOff = DumpResult.Queued
    }

    private val build = BuildInfoMock(dsn = "https://key@ingest/1", processId = "app.snapsync").port()

    private val crash = CrashChannel(reporter, build, ThreadLabelMock(), CrashShaping.STANDARD)

    @AfterTest
    fun handBackTheLoggingGlobals() {
        Log.install(emptyList())
    }

    @Test
    fun `the log is installed first, crash reporting starts next, and the banner is the first line after`() {
        StartProcess(ProcessLogging(listOf(writer), sinks = 1), crash, build).run()
        assertEquals(
            listOf(STARTED, "reporter started", "=== process start ===", "upload base"),
            happened,
            "a line the start writes reaches the installed writers, and the banner follows the started channel",
        )
    }

    @Test
    fun `the started process carries the started channel`() {
        val started = StartProcess(ProcessLogging(listOf(writer), sinks = 1), crash, build).run()
        assertSame(crash, started.crash)
    }

    @Test
    fun `a process that supplies no sinks leaves the logging globals alone and still starts`() {
        StartProcess(ProcessLogging(listOf(writer), sinks = 0), crash, build).run()
        assertEquals(listOf(STARTED), happened, "nothing reached a writer it did not install")
    }

    private companion object {
        const val STARTED = "<channel started>"
    }
}
