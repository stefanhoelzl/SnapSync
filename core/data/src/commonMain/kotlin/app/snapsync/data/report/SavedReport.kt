package app.snapsync.data.report

import app.snapsync.domain.report.DiagnosticDump
import app.snapsync.domain.report.DumpResult
import app.snapsync.format.ReportText
import app.snapsync.ports.FileArea
import app.snapsync.ports.FileFailure
import app.snapsync.ports.FileResult
import app.snapsync.ports.Files

/**
 * The bug report a build that reports nowhere keeps: the same sections, written to [PATH] in the app's own files,
 * replacing the report saved before it. It never leaves the phone; a refused write answers [DumpResult.NotSent] with
 * the reason.
 */
class SavedReport(private val files: Files) {
    fun keep(dump: DiagnosticDump): DumpResult =
        when (val written = files.write(FileArea.PRIVATE, PATH, ReportText.saved(dump).encodeToByteArray())) {
            is FileResult.Ok -> DumpResult.Saved(PATH)
            is FileFailure -> DumpResult.NotSent("the report could not be saved: $written")
        }

    companion object {
        /** Where the latest report is kept: the app's own files' PRIVATE area, replaced each time. */
        const val PATH: String = "diagnostic-report.json"
    }
}
