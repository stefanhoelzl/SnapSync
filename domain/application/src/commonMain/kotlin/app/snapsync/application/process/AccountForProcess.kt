package app.snapsync.application.process

import app.snapsync.application.EntryPointCall
import app.snapsync.data.crash.CrashChannel
import app.snapsync.data.process.FootprintTrail
import app.snapsync.data.process.ReadMetrics
import app.snapsync.domain.process.ProcessMetricRules
import app.snapsync.inbound.Field.Companion.of
import app.snapsync.inbound.Log

private val log = Log("processMetrics").unlabelled

/**
 * What a delivered process-metric report does: the three channels, and nothing else.
 *
 * ```
 *   report ──┬─► device log        one line per report, always, on every build
 *            ├─► reporting context the standing account, riding every later event AND any crash
 *            └─► reporting event   one per reason the report crossed on
 * ```
 *
 * What is worth reporting is the domain's ([ProcessMetricRules.account]). The context is attached **first**, so it is
 * standing by when the very event it exists to explain is sent; it carries why each crossing crossed beside the
 * measured fields, and the footprints the app recorded before it was last suspended — the ended process's own last
 * readings, which no report states. A crossing's event is sent HERE rather than born from its log line, because it is
 * filed under the build the report describes, which a line cannot carry; its line is logged first, at `Warn` — an
 * `Error` would send it a second time, filed under the delivering build — so it rides the event as its last breadcrumb.
 *
 * Runs INLINE on the thread the provider delivers on (`ProcessMetrics`: delivery is one-shot, and a process woken
 * briefly may be killed before deferred work runs).
 */
class AccountForProcess(private val footprints: FootprintTrail, private val channel: CrashChannel) {
    @EntryPointCall
    operator fun invoke(read: ReadMetrics) {
        val account = ProcessMetricRules.account(read.report)
        channel.describeProcess(read.report.fields + account.reasons + footprints.fields())
        log.i("process metrics", "period" of account.period, "fields" of read.report.fields.size, "report" of read.text)
        account.notes.forEach { log.i("process metrics delivered later", "note" of it) }
        account.crossings.forEach { reason ->
            log.w("process metric crossed", "reason" of reason.key)
            channel.reportCrossing(reason, read.report)
        }
    }
}
