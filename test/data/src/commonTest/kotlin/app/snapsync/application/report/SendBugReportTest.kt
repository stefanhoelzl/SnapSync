@file:OptIn(EntryPointCall::class)

package app.snapsync.application.report

import app.snapsync.application.EntryPointCall
import app.snapsync.data.crash.CrashChannel
import app.snapsync.data.crash.RecordingReporter
import app.snapsync.data.report.SavedReport
import app.snapsync.domain.crash.CrashShaping
import app.snapsync.domain.report.DiagnosticDump
import app.snapsync.domain.report.DumpResult
import app.snapsync.mock.BuildInfoMock
import app.snapsync.mock.ThreadLabelMock
import app.snapsync.mock.inMemoryFiles
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** A confirmed report goes where the build reports, and is kept on the device where it reports nowhere. */
class SendBugReportTest {

    private val dump = DiagnosticDump("it froze", mapOf("screen" to "Joined"), emptyMap(), "a\n", "e\n")
    private val reporter = RecordingReporter()
    private val files = mutableMapOf<String, ByteArray>()

    private fun send(dsn: String?) = SendBugReport(
        CrashChannel(reporter, BuildInfoMock(dsn = dsn).port(), ThreadLabelMock(), CrashShaping.STANDARD),
        SavedReport(inMemoryFiles(private = files)),
    )

    @Test
    fun `a build that reports hands the report to the channel`() = runTest {
        assertEquals(DumpResult.Queued, send("https://key@ingest/1")(dump))
        assertEquals(listOf(dump.event()), reporter.dumps)
        assertEquals(emptySet(), files.keys)
    }

    @Test
    fun `a build that reports nowhere keeps it, and sends nothing`() = runTest {
        assertEquals(DumpResult.Saved(SavedReport.PATH), send(null)(dump))
        assertEquals(emptyList(), reporter.dumps)
        assertEquals(setOf(SavedReport.PATH), files.keys)
    }
}
