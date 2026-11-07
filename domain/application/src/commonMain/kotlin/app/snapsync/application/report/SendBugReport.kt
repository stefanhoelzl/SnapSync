package app.snapsync.application.report

import app.snapsync.application.EntryPointCall
import app.snapsync.data.crash.CrashChannel
import app.snapsync.data.report.SavedReport
import app.snapsync.domain.report.DiagnosticDump
import app.snapsync.domain.report.DumpResult
import app.snapsync.domain.report.ReportDestination

/**
 * Send one bug report the user confirmed where this build reports — handed to the channel, which queues it — and keep
 * it on the device where the build reports nowhere, in place of the one kept before. What became of it is the answer
 * the user is told ([DumpResult.outcome]), never that it reached anyone.
 */
class SendBugReport(private val channel: CrashChannel, private val saved: SavedReport) {
    @EntryPointCall
    suspend operator fun invoke(dump: DiagnosticDump): DumpResult = when (channel.destination) {
        ReportDestination.DEVELOPER -> channel.send(dump)
        ReportDestination.THIS_DEVICE -> saved.keep(dump)
    }
}
