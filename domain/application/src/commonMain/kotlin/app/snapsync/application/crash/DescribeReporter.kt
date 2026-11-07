package app.snapsync.application.crash

import app.snapsync.application.EntryPointCall
import app.snapsync.data.crash.CrashChannel
import app.snapsync.data.crash.ReportedIdsRead

/**
 * Stand the ids SnapSync knows — the joined event, the device, the process — on every report this process sends from
 * now on, a crash delivered on a later launch included: at launch and whenever the membership changes in the app, at
 * each invocation in the upload extension.
 */
class DescribeReporter(private val ids: ReportedIdsRead, private val channel: CrashChannel) {
    @EntryPointCall
    operator fun invoke() = channel.describe(ids.now())
}
