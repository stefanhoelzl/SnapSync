package app.snapsync.data.crash

import app.snapsync.data.report.NO_IDS
import app.snapsync.domain.crash.CrashLevel
import app.snapsync.domain.crash.CrashSections
import app.snapsync.domain.crash.CrashShaping
import app.snapsync.domain.crash.CrashTags
import app.snapsync.domain.crash.ReportedIds
import app.snapsync.domain.process.CrossingReason
import app.snapsync.domain.process.ProcessMetricReport
import app.snapsync.domain.report.DiagnosticDump
import app.snapsync.domain.report.DumpResult
import app.snapsync.domain.report.Fact
import app.snapsync.domain.report.ReportDestination
import app.snapsync.inbound.LogLevel
import app.snapsync.inbound.LogLine
import app.snapsync.inbound.of
import app.snapsync.mock.BuildInfoMock
import app.snapsync.mock.ThreadLabelMock
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The channel translates: it starts where the build reports, and hands each report to the port as the domain shaped it. */
class CrashChannelTest {

    private val reporter = RecordingReporter()
    private val label = ThreadLabelMock().apply { swap("OnForeground") }

    private fun channel(dsn: String? = "https://key@ingest/1") =
        CrashChannel(
            reporter,
            BuildInfoMock(dsn = dsn, processId = "app.snapsync").port(),
            label,
            CrashShaping.STANDARD,
        )

    @Test
    fun `a build that reports starts with its options and the shaping, and sends a report where it reports`() {
        val channel = channel()
        channel.start()
        assertEquals("https://key@ingest/1", assertNotNull(reporter.options).dsn)
        assertSame(CrashShaping.STANDARD, reporter.shaping)
        assertEquals(ReportDestination.DEVELOPER, channel.destination)
    }

    @Test
    fun `a build that reports nowhere starts nothing, routes no line, and keeps a report on the device`() {
        val channel = channel(dsn = null)
        channel.start()
        assertNull(reporter.options)
        assertNull(channel.lines)
        assertEquals(ReportDestination.THIS_DEVICE, channel.destination)
    }

    @Test
    fun `a line is a breadcrumb, and an error an event, labelled by its trigger or the thread's`() {
        val lines = assertNotNull(channel().lines)
        lines.write(LogLine(LogLevel.INFO, "gallery", "OnPush", "enumerated", listOf("n" of 3), null))
        lines.write(LogLine(LogLevel.ERROR, "engine", null, "run failed", emptyList(), null))
        assertEquals(listOf("[OnPush] enumerated n=3", "[OnForeground] run failed"), reporter.crumbs.map { it.message })
        assertEquals("OnForeground", reporter.captured.single().tags[CrashTags.ENTRY_POINT])
    }

    @Test
    fun `every level is one of the channel's four, an assert an error`() {
        val lines = assertNotNull(channel().lines)
        LogLevel.entries.forEach { lines.write(LogLine(it, "t", null, it.name, emptyList(), null)) }
        assertEquals(
            listOf(
                CrashLevel.DEBUG,
                CrashLevel.INFO,
                CrashLevel.WARNING,
                CrashLevel.ERROR,
            ),
            reporter.crumbs.map { it.level },
        )
        assertEquals(listOf("ERROR"), reporter.captured.map { it.message })
    }

    @Test
    fun `describing starts the channel first, then stands the section`() {
        channel().describe(ReportedIds(null, Fact.Failed("no device id stored yet"), "app.snapsync"))
        assertNotNull(reporter.options, "an account that outran the composition must not reach an unstarted channel")
        assertEquals("none", reporter.contexts.getValue(CrashSections.SNAPSYNC)["event"])
    }

    @Test
    fun `a process's account stands as its own section, the channel started first`() {
        channel().describeProcess(mapOf("timeStampBegin" to "x"))
        assertNotNull(reporter.options)
        assertEquals(mapOf("timeStampBegin" to "x"), reporter.contexts[CrashSections.PROCESS_METRICS])
    }

    @Test
    fun `a crossing is one event under the thread's trigger, the channel started first`() {
        channel().reportCrossing(CrossingReason("foregroundExitData.x"), ProcessMetricReport(emptyMap()))
        assertNotNull(reporter.options)
        val event = reporter.captured.single()
        assertEquals("process metric crossed: foregroundExitData.x", event.message)
        assertEquals("OnForeground", event.tags[CrashTags.ENTRY_POINT])
    }

    @Test
    fun `a report is handed over as its one event`() = runTest {
        val dump = DiagnosticDump("it froze", NO_IDS, mapOf("screen" to "Joined"), emptyMap(), "a\n", "e\n")
        assertEquals(DumpResult.Queued, channel().send(dump))
        assertEquals(dump.event(), reporter.dumps.single())
        assertNotNull(reporter.options)
    }
}
