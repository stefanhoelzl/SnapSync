package app.snapsync.compose

import app.snapsync.model.CrashEvent
import app.snapsync.model.CrashOptions
import app.snapsync.model.Crumb
import app.snapsync.model.DumpResult
import app.snapsync.model.Handoff
import app.snapsync.ports.CrashHandlers
import app.snapsync.ports.CrashReporter
import app.snapsync.ports.EntryContext
import app.snapsync.ports.MetricHandlers
import app.snapsync.ports.ProcessMetrics
import app.snapsync.ports.SystemUi

// The inert bindings a composition states when the platform offers nothing to bind. They are compositions' choices,
// not ports' content (`ports/` holds interfaces only), so they live with the compositions that choose them.

/** No entry-point context: the world, the harnesses and tests — any process without device logging. */
object NoEntryContext : EntryContext {
    override fun enter(name: String): Boolean = false
    override fun exit(owned: Boolean) = Unit
    override fun current(): String? = null
}

/**
 * Reports nowhere: the crash-reporting binding for a build with no crash reporter linked (Android, until its reporter
 * lands). Such a build also carries no destination, so crash reporting starts nothing and keeps a diagnostic dump on
 * the phone without asking the channel; if anything did reach it, [sendDump] says nothing was sent.
 */
object NoCrashReporter : CrashReporter {
    override fun listen(handlers: CrashHandlers) = Unit
    override fun start(options: CrashOptions) = Unit
    override fun capture(event: CrashEvent) = Unit
    override fun breadcrumb(crumb: Crumb) = Unit
    override fun setContext(name: String, fields: Map<String, String>) = Unit
    override suspend fun sendDump(dump: CrashEvent): DumpResult = DumpResult.NotSent("no crash reporter on this build")
}

/** Delivers nothing, ever: the process-metrics binding for a process with no provider. */
object NoProcessMetrics : ProcessMetrics {
    override fun listen(handlers: MetricHandlers) = Unit
}

/**
 * Hands nothing over — for compositions with no platform UI to reach (the desktop harnesses and the world).
 * [share] and [openUrl] answer [Handoff.Refused], which is exactly what is true there.
 */
object NoSystemUi : SystemUi {
    override suspend fun share(text: String): Handoff = Handoff.Refused("no platform share surface")
    override suspend fun openUrl(url: String): Handoff = Handoff.Refused("no platform to open a link in")
    override fun openSettings() = Unit
}
