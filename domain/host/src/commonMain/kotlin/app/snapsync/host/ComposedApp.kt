package app.snapsync.host

import app.snapsync.compose.AppCore
import app.snapsync.compose.AppPorts
import app.snapsync.compose.ProcessServices
import app.snapsync.compose.followNetworkReturns
import app.snapsync.compose.inbound
import app.snapsync.compose.snapSyncApp
import app.snapsync.entrypoints.HostAssembly
import app.snapsync.inbound.CompositionOnly
import app.snapsync.inbound.Inbound
import app.snapsync.inbound.InboundImplementation
import app.snapsync.ports.DateFormatting
import app.snapsync.ports.UiHandlers
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.DeviceVerification
import app.snapsync.presentation.EventKeyView
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.presentation.StatusDiagnostics
import app.snapsync.presentation.StatusSources
import app.snapsync.presentation.onIntent
import app.snapsync.ui.state.UiIntent
import app.snapsync.ui.state.UiState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * The composed app: the core, and the status host over it (`docs/architecture.md`, "One shared
 * composition").
 *
 * [host] is `by lazy`, and touching it is **host assembly**: it installs the permission-grant subscriptions, then
 * builds the host. That is the iOS shell's timing, and it is load-bearing — a cold background wake that merely
 * touches [core] must install no permission collector and run no launch reconcile.
 *
 * The push-registration subscription and the selection observer are the two exceptions: [snapSyncHost] installs them
 * itself, as the graph is composed — so on every cold start, a background wake's included (see
 * [AppCore.installCompositionSubscriptions]).
 */
class ComposedApp internal constructor(
    val core: AppCore,
    /**
     * What the process set up as the composition's first act — its crash reporting, its metrics handler, its log
     * writers. Exposed for the control channel, which drives a synthetic process-metric report through THIS instance.
     */
    val process: ProcessServices,
    /** The one cutoff formatter the status host and the screen rendering it share. */
    val cutoffFormatter: CutoffFormatter,
    /** The platform's date formatting the screen renders through ([AppPorts.dateFormatting]). */
    val dateFormatting: DateFormatting,
    private val assembleHost: () -> StatusContainerHost,
) {
    val host: StatusContainerHost by lazy(assembleHost)
}

/**
 * Compose the app over [ports]: the core from [snapSyncApp] with its push registration installed, and — on first
 * touch of [ComposedApp.host] — the host-assembly subscriptions and the status host, observing every read-model the
 * core exposes.
 *
 * The gallery's and the wake's handlers are registered here too, on composition, for the same reason: an import
 * finishing in a background wake, or the wake itself, must find them. Registering starts nothing; the selection
 * observer opens right after, on composition, so a background start under a partial grant learns its selection.
 *
 * The push registration is installed HERE, on composition, rather than at host assembly: a process composes its graph
 * on every cold start — the first operating-system entry that reaches the core does it, foreground or background —
 * while it assembles its host only on a foreground launch. A rotated APNs token or a renewed credential learned in a
 * background wake is therefore published from that wake. The installer is idempotent, so nothing re-installs it.
 *
 * A root supplies ports and nothing else. It builds no host, installs no subscription and passes no read-model:
 * a source added to the host is wired here once, and every root's host observes it. That is the drift this
 * function exists to end — the control channel's JVM host once built its status host without the version
 * refusal and never registered for pushes, and nothing said so.
 */
@OptIn(CompositionOnly::class)
fun snapSyncHost(
    scope: CoroutineScope,
    /** The composition lane [scope] runs on — named by the root, which owns that thread (see `AppCore.coreLane`). */
    lane: CoroutineDispatcher,
    /**
     * The CPU lane presentation reduces on (`docs/architecture.md`, "Concurrency and failure") — the root's to name,
     * as it names [lane]: the platform's pool on a phone, and a lane of its own where a host must see every task.
     */
    reduction: CoroutineDispatcher,
    ports: AppPorts,
    /**
     * The one cutoff formatter of this process — built by the root, which read the device zone once, and handed to
     * the platform's UI too, so the screen and the status host render one capture date one way.
     */
    cutoffFormatter: CutoffFormatter,
): ComposedApp {
    // The process first — inside `snapSyncApp`, before anything else in the graph can fail.
    val core = snapSyncApp(scope, lane, ports)
    val process = core.process
    val log = process.logger("app")
    core.installCompositionSubscriptions()
    val composed = ComposedApp(core, process, cutoffFormatter, ports.dateFormatting) {
        // Host assembly: the permission-grant collectors install ONLY from here (see [ComposedApp]).
        core.installPermissionSubscriptions()
        // The network's return resumes the app's work while it is in front.
        core.followNetworkReturns()
        val host = StatusContainerHost(
            statusSourcesOf(core, ports),
            scope = scope,
            reduction = reduction,
            cutoffFormatter = cutoffFormatter,
            commands = core.userCommands,
            queries = core.userQueries,
            // Whose word authorizes a headless join: the build's controls, never the link's — inert on every
            // production build.
            inviteLinkHints = ports.devControls.inviteLinkHints(),
            // Where a bug report goes on this build — the sheet says it.
            reportDestination = process.reportDestination,
            // Which build this is — the app menu's footer.
            build = process.buildLabel,
            // What the event album holds on this phone — read from the photo library.
            albumContents = core.albumContents,
            diagnostics = StatusDiagnostics(
                log = { message -> log.i { message } },
                // `Error`: the threshold at which a Kermit line becomes a crash-reporting event rather than a
                // breadcrumb — a command that failed outright is exactly what should reach the operator.
                onIntentError = { throwable -> log.e(throwable) { "user command failed" } },
            ),
        )
        // The platform's UI is shown every state the host reduces, from assembly on. `show` only hands the state over:
        // the adapter renders it on its own main thread.
        scope.launch { host.screen.collect(ports.ui::show) }
        // A link the app was opened with waits in the core until a screen can open it: from assembly on, it opens.
        scope.launch {
            core.openedLinks.pending.filterNotNull().collect { url ->
                host.onOpenUrl(url)
                core.openedLinks.consume(url)
            }
        }
        host
    }
    // A minted event routes into the host's join gate, so create and a scanned QR take one gate: collected from
    // composition on, so the host is assembled by the time the first one — a tap's — is minted.
    scope.launch { core.mintedEvents.collect { composed.host.onEventCreated(it.eventId, it.linkKey) } }
    // Every event port's ONE delivery each, now that the graph they deliver into is built. The foreground asks for the
    // status host first — a person reached the app — and so does a live screen; nothing else builds it, so a cold
    // background start installs no permission-grant subscription.
    core.inbound(HostFirst(composed))
    ports.ui.inbound(UiHandlers(Intents(composed), LiveScreen(composed)))
    // Asked at every launch, a background one included: the answer arrives through the push handlers just registered,
    // and asking is how a rotated token is learned.
    ports.pushNotifications.register()
    return composed
}

/** The status host's assembly, as the foreground asks for it first. */
private class HostFirst(private val composed: ComposedApp) : HostAssembly {
    override fun assemble() {
        composed.host
    }
}

/** A person's intent on the screen: the status host's intent table decides what it means. */
@OptIn(InboundImplementation::class)
private class Intents(private val composed: ComposedApp) : Inbound<UiIntent, Unit> {
    override fun deliver(arg: UiIntent) = composed.host.onIntent(arg)
}

/**
 * A live screen is about to be built: the status host — assembled first, once — answers its current state, so no
 * screen's first frame is empty. Idempotent: a platform may build the screen more than once.
 */
@OptIn(InboundImplementation::class)
private class LiveScreen(private val composed: ComposedApp) : Inbound<Unit, UiState> {
    override fun deliver(arg: Unit): UiState = composed.host.screen.value
}

/**
 * Every read-model the status host reduces over, from the composed [core] and its [ports] — all of them, named
 * explicitly, so no host built from them can observe fewer than another.
 */
private fun statusSourcesOf(core: AppCore, ports: AppPorts): StatusSources = StatusSources(
    sync = core.syncStatusSource,
    permission = core.photoPermission,
    config = core.membership,
    creation = core.creationStatus,
    rename = core.renameStatus,
    download = core.downloadStatusSource.progress,
    verification = DeviceVerification(core.attested, core.refusal, core.attestationFault, core.serviceFault),
    pending = MutableStateFlow(null),
    versionRefusal = core.versionRefusal,
    network = core.network.status,
    mobileData = core.mobileDataChoice,
    store = ports.process.build.store,
    foreground = core.foregroundReturns,
    eventKey = EventKeyView(core.eventKeyReads.inviteKey, core.eventKeyReads.presence),
)
