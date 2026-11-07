package app.snapsync.data.report

import app.snapsync.domain.report.DiagnosticDump
import app.snapsync.domain.report.DumpResult
import app.snapsync.mock.inMemoryFiles
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A build that reports nowhere keeps the latest report on the device, in place of the one before. */
class SavedReportTest {

    private fun dump(note: String) = DiagnosticDump(note, NO_IDS, mapOf("screen" to "Joined"), emptyMap(), "a\n", "e\n")

    @Test
    fun `the latest report replaces the earlier one`() {
        val files = mutableMapOf<String, ByteArray>()
        val saved = SavedReport(inMemoryFiles(private = files))
        assertEquals(DumpResult.Saved(SavedReport.PATH), saved.keep(dump("first report")))
        assertEquals(DumpResult.Saved(SavedReport.PATH), saved.keep(dump("second report")))
        val kept = files.getValue(SavedReport.PATH).decodeToString()
        assertTrue("second report" in kept && "first report" !in kept, kept)
    }

    @Test
    fun `a report that cannot be kept says so`() {
        assertIs<DumpResult.NotSent>(SavedReport(inMemoryFiles(private = null)).keep(dump("it froze")))
    }
}
