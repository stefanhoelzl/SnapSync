package app.snapsync.application.report

import app.snapsync.application.EntryPointCall
import app.snapsync.data.crash.ReportedIdsRead
import app.snapsync.domain.report.DiagnosticDump
import app.snapsync.domain.report.ReportContext

/**
 * Assemble one bug report the user confirmed: SnapSync's ids, the [state] the log may not hold, the [ledger]'s counts,
 * the [device]'s facts and both [logs]' tails — each section read by its own part, in that order.
 *
 * **Reads only.** Nothing here writes a ledger row, a download row, a config or an identity: the report is a
 * projection of the app's state and the device's, taken at the moment the user confirms.
 *
 * **What a report may hold** is bounded by kind, not by source: this app's own state and the device's settings and
 * conditions — never a photo or what it shows, another app's data, a location beyond the time zone, contacts, free
 * storage or the device's name. A new field is tested against that bound (decision record
 * `changes/archive/2026-10-05-device-state-in-bug-reports`).
 *
 * **Every fact is best-effort, and none is caught.** Every read answers its failure as a value — a store that cannot be
 * read writes its facts `failed (<reason>)`, never zero, which is a real answer — and a device read that stalls is
 * bounded and written failed. Neither is the end of the report. A fact the platform does not have is left out.
 */
class AssembleBugReport(
    private val ids: ReportedIdsRead,
    private val state: ReportState,
    private val ledger: ReportLedger,
    private val device: DeviceFacts,
    private val logs: ReportLogs,
) {
    /**
     * @param note what the user wrote — already trimmed and bounded by the sheet that collected it, and carried
     *   unchanged, so the cap has one owner.
     * @param context what the user was looking at — the surface's **opaque label** and what it **showed**, both
     *   supplied by presentation and recorded verbatim.
     */
    @EntryPointCall
    suspend operator fun invoke(note: String, context: ReportContext): DiagnosticDump {
        val facts = device.read()
        val (app, extension) = logs.read()
        return DiagnosticDump(
            note = note,
            ids = ids.now(),
            state = state.section(context) + facts,
            ledger = ledger.section(),
            appLog = app,
            extensionLog = extension,
        )
    }
}
