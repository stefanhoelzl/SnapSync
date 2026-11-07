package app.snapsync.data.crash

import app.snapsync.domain.crash.CrashEvent
import app.snapsync.domain.crash.CrashOptions
import app.snapsync.domain.crash.CrashShaping
import app.snapsync.domain.crash.Crumb
import app.snapsync.domain.report.DumpHandOff
import app.snapsync.domain.report.DumpResult
import app.snapsync.ports.CrashReporter

/** A reporter that records what reaches it, and what it was started with. */
internal class RecordingReporter : CrashReporter {
    val crumbs = mutableListOf<Crumb>()
    val captured = mutableListOf<CrashEvent>()
    val contexts = mutableMapOf<String, Map<String, String>>()
    val dumps = mutableListOf<CrashEvent>()
    var options: CrashOptions? = null
    var shaping: CrashShaping? = null

    override fun start(options: CrashOptions, shaping: CrashShaping) {
        this.options = options
        this.shaping = shaping
    }

    override fun capture(event: CrashEvent) {
        captured += event
    }

    override fun breadcrumb(crumb: Crumb) {
        crumbs += crumb
    }

    override fun setContext(name: String, fields: Map<String, String>) {
        contexts[name] = fields
    }

    override suspend fun sendDump(dump: CrashEvent): DumpHandOff = DumpResult.Queued.also { dumps += dump }
}
