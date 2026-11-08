package app.snapsync.composition.app

import app.snapsync.composition.process.ProcessGraph
import app.snapsync.composition.process.startProcess
import app.snapsync.entrypoints.OnBackground
import app.snapsync.entrypoints.OnChangedElsewhere
import app.snapsync.entrypoints.OnDownloadCompleted
import app.snapsync.entrypoints.OnDownloadFinished
import app.snapsync.entrypoints.OnDownloadSessionEvents
import app.snapsync.entrypoints.OnForeground
import app.snapsync.entrypoints.OnImportPlaceholder
import app.snapsync.entrypoints.OnImportSettled
import app.snapsync.entrypoints.OnLink
import app.snapsync.entrypoints.OnScheduledWake
import app.snapsync.entrypoints.OnSessionDrained
import app.snapsync.entrypoints.OnUploadFinished
import app.snapsync.entrypoints.OnUploadSessionEvents
import app.snapsync.inbound.CompositionOnly
import app.snapsync.inbound.Inbound
import app.snapsync.ports.DatabaseHandlers
import app.snapsync.ports.DateFormatting
import app.snapsync.ports.DownloadHandlers
import app.snapsync.ports.GalleryHandlers
import app.snapsync.ports.LifecycleHandlers
import app.snapsync.ports.LinkHandlers
import app.snapsync.ports.ProcessMetricPayload
import app.snapsync.ports.TransferHandlers
import app.snapsync.ports.UiHandlers
import app.snapsync.ports.WakeHandlers
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.scheduling.Runtime
import app.snapsync.scheduling.RuntimePorts
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * The composed app — its graph, and the status host over it (`docs/architecture.md`, "The composition layer"). [host]
 * is the host assembly's: touching it assembles the host first if no trigger has, which is the iOS shell's timing and
 * is load-bearing — a cold background wake that only reaches [core] installs no permission collector and runs no
 * launch reconcile.
 */
class ComposedApp(
    val core: AppGraph,
    private val graph: HostGraph,
    /** The one cutoff formatter the status host and the screen rendering it share. */
    val cutoffFormatter: CutoffFormatter,
    /** The platform's date formatting the screen renders through ([AppPorts.dateFormatting]). */
    val dateFormatting: DateFormatting,
) {
    /**
     * What a delivered process-metric report does — the handler the process's first act registered. Exposed for the
     * control channel, which drives a synthetic report through THIS instance.
     */
    val onMetricReport: Inbound<ProcessMetricPayload, Unit> = core.process.onMetricReport

    /** The one status host, assembled first if no trigger has. */
    val host: StatusContainerHost get() = graph.assembly.host
}

/**
 * **The ONE app composition** (`docs/architecture.md`, "The composition layer"): every root — the phone's, the JVM's,
 * the rig's — calls this, so a wiring difference between binaries is impossible rather than undetected. Straight-line:
 * the process first (crash reporting before anything else in the graph can fail), the graph, the subscriptions every
 * start installs (a background one included), then every event port's one delivery each, now that the graph they
 * deliver into is built. Host assembly is not here: a person's start runs it (`AssembleHost`).
 *
 * The push registration and the selection observer install on composition rather than at host assembly: a process
 * composes on every cold start, foreground or background, so a rotated token or a renewed credential learned in a
 * background wake is published from that wake, and a background start under a partial grant learns its selection.
 */
@OptIn(CompositionOnly::class)
fun composeApp(
    scope: CoroutineScope,
    /** The composition lane [scope] runs on — named by the root, which owns that thread. */
    lane: CoroutineDispatcher,
    /** The CPU lane presentation reduces on — the root's to name, as it names [lane]. */
    reduction: CoroutineDispatcher,
    ports: AppPorts,
    /** The one cutoff formatter of this process — built by the root, which read the device zone once. */
    cutoffFormatter: CutoffFormatter,
): ComposedApp {
    val runtime = Runtime(
        scope.coroutineContext[Job],
        lane,
        RuntimePorts(ports.backgroundTime, ports.process.threadLabel, ports.process.entryEvents),
    )
    val process = ProcessGraph(ports.process, runtime)
    val app = AppGraph(ports, runtime, process, startProcess(ports.process, process), lane)
    val host = HostGraph(app, ports, app.commands, cutoffFormatter, scope, reduction)
    app.startSubscriptions.install()
    ports.gallery.inbound(
        GalleryHandlers(
            OnImportPlaceholder(runtime, app.receiving.markers),
            OnImportSettled(runtime, app.receiving.markers),
        ),
    )
    ports.wake.inbound(WakeHandlers(OnScheduledWake(runtime, app.work.wakeWork)))
    ports.download.inbound(
        DownloadHandlers(
            OnDownloadFinished(runtime, app.receiving.stage, app.receiving.record, app.work.downloadStaged),
            OnDownloadCompleted(runtime, app.receiving.free),
            OnDownloadSessionEvents(runtime, app.downloadDrains, app.work.wakeWork),
            OnSessionDrained(runtime, app.downloadDrains),
        ),
    )
    ports.uploadSession.inbound(
        TransferHandlers(
            OnUploadFinished(runtime, app.uploader.recordFinished, app.uploader.admission, app.work.topUp),
            OnUploadSessionEvents(runtime, app.uploadDrains, app.work.wakeWork),
            OnSessionDrained(runtime, app.uploadDrains),
        ),
    )
    ports.lifecycle.inbound(
        LifecycleHandlers(
            OnForeground(
                runtime,
                app.backend.serviceConditions,
                app.foreground,
                app.services.pushTokens,
                app.work.prelude,
                host.assembly,
                app.network.foreground,
                app.work.foregroundWork,
                app.work.wakeWork,
            ),
            OnBackground(runtime, app.foreground, app.network.foreground, app.footprints),
        ),
    )
    ports.pushNotifications.inbound(app.pushHandlers)
    ports.links.inbound(LinkHandlers(OnLink(runtime, app.linkReading, app.openedInvites)))
    // What the upload extension commits reaches the app's readers of that store; the extension registers no handler.
    ports.databases.inbound(DatabaseHandlers(OnChangedElsewhere(runtime, app.status.changedElsewhere)))
    // A person's taps, and a live screen's first frame — each assembling the host first if nothing has.
    ports.ui.inbound(UiHandlers(host.intents, host.screen))
    // Asked at every launch, a background one included: asking is how a rotated token is learned.
    ports.pushNotifications.register()
    return ComposedApp(app, host, cutoffFormatter, ports.dateFormatting)
}
