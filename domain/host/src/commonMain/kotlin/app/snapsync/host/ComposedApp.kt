package app.snapsync.host

import app.snapsync.presentation.onIntent
import app.snapsync.compose.AppCore
import app.snapsync.compose.AppPorts
import app.snapsync.compose.ProcessServices
import app.snapsync.compose.snapSyncApp
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.MutablePendingJoinSource
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.presentation.StatusDiagnostics
import app.snapsync.presentation.StatusSources
import app.snapsync.compose.devHandlers
import app.snapsync.compose.lifecycleHandlers
import app.snapsync.compose.pushHandlers
import app.snapsync.model.EventLinkDelivery
import app.snapsync.model.LinkDelivery
import app.snapsync.model.forwardEventLink
import app.snapsync.model.userActivityParams
import app.snapsync.ports.LinkHandlers
import app.snapsync.ports.UiHandlers
import app.snapsync.model.invocation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The composed app: the core, and the status host over it (`docs/architecture.md`, "One shared
 * composition").
 *
 * [host] is `by lazy`, and touching it is **host assembly**: it installs the permission-grant subscriptions, then
 * builds the host. That is the iOS shell's timing, and it is load-bearing — a cold background wake that merely
 * touches [core] must install no permission collector and run no launch reconcile.
 *
 * The push-registration subscription is the one exception, and it is installed by [snapSyncHost] itself, as the
 * graph is composed — so on every cold start, a background wake's included (see [AppCore.installPushRegistration]).
 */
class ComposedApp internal constructor(
    val core: AppCore,
    private val ports: AppPorts,
    /** The one cutoff formatter the status host and the screen rendering it share. */
    val cutoffFormatter: CutoffFormatter,
    private val assembleHost: () -> StatusContainerHost,
) {
    val host: StatusContainerHost by lazy(assembleHost)

    /**
     * Every read-model [host] observes — for the one host not assembled here: the desktop world harness, which
     * installs no host-assembly subscription (its operator plays the OS) and decorates its command bundle for the inspector,
     * but observes exactly what the phone's host observes. [pending] is that harness's seam.
     */
    fun statusSources(pending: MutablePendingJoinSource = MutablePendingJoinSource()): StatusSources =
        statusSourcesOf(core, ports, pending)
}

/**
 * Compose the app over [ports]: the core from [snapSyncApp] with its push registration installed, and — on first
 * touch of [ComposedApp.host] — the host-assembly subscriptions and the status host, observing every read-model the
 * core exposes.
 *
 * The gallery's and the wake's handlers are registered here too, on composition, for the same reason: an import
 * finishing in a background wake, or the wake itself, must find them. Registering starts nothing — the selection
 * observer opens at host assembly.
 *
 * The push registration is installed HERE, on composition, rather than at host assembly: a process composes its
 * graph on every cold start — the first operating-system entry that reaches the core does it, foreground or
 * background — while it assembles its host only on a foreground launch. A rotated APNs token or a renewed credential
 * learned in a background wake is therefore published from that wake (capability `sync-status`, "Push registration
 * is started by the shared composition"). The installer is idempotent, so nothing re-installs it.
 *
 * A root supplies ports and nothing else. It builds no host, installs no subscription and passes no read-model:
 * a source added to the host is wired here once, and every root's host observes it. That is the drift this
 * function exists to end — the control channel's JVM host once built its status host without the version
 * refusal and never registered for pushes, and nothing said so.
 */
fun snapSyncHost(
    scope: CoroutineScope,
    process: ProcessServices,
    ports: AppPorts,
    /**
     * The one cutoff formatter of this process — built by the root, which read the device zone once, and handed to
     * the platform's UI too, so the screen and the status host render one capture date one way.
     */
    cutoffFormatter: CutoffFormatter,
): ComposedApp {
    val core = snapSyncApp(scope, process, ports)
    // The event ports' ONE registration each, on composition — a background wake's import needs its handlers as much
    // as a foreground launch does. `listen` only registers: the selection observer opens at host assembly below.
    ports.gallery.listen(core.galleryHandlers)
    // On iOS this registration IS the `BGTask` launch handler, which Apple requires before launch finishes — why the
    // root composes at launch. It starts nothing: the handlers run only when the operating system wakes the app.
    ports.wake.listen(core.events.wakeHandlers)
    // The transfer sessions' events — a background relaunch that hands back finished transfers must find these.
    ports.download.listen(core.events.downloadHandlers)
    ports.appUpload.listen(core.events.uploadHandlers)
    core.installPushRegistration()
    val composed = ComposedApp(core, ports, cutoffFormatter) {
        // Host assembly: the permission-grant collectors install ONLY from here (see [ComposedApp]).
        core.installPermissionSubscriptions()
        val host = StatusContainerHost(
            statusSourcesOf(core, ports),
            scope = scope,
            cutoffFormatter = cutoffFormatter,
            commands = core.userCommands,
            queries = core.userQueries,
            // Whose word authorizes a headless join: the build's controls, never the link's (capability
            // `join-event`) — inert on every production build.
            inviteLinkHints = ports.devControls.inviteLinkHints(),
            // Where a bug report goes on this build — the sheet says it (capability `privacy-security`).
            reportDestination = process.reportDestination,
            diagnostics = StatusDiagnostics(
                log = { message -> ports.log.i { message } },
                // `Error`: the threshold at which a Kermit line becomes a crash-reporting event rather than a
                // breadcrumb (capability `privacy-security`) — a command that failed outright is exactly what
                // should reach the operator.
                onIntentError = { throwable -> ports.log.e(throwable) { "user command failed" } },
            ),
        )
        // The platform's UI is shown every state the host reduces, from assembly on, on the UI lane.
        scope.launch(ports.uiLane) { host.container.stateFlow.collect(ports.ui::show) }
        host
    }
    listenToEntries(composed, process, ports)
    // Asked at every launch, a background one included (capability `receiving-photos`, "Registration timing"): the
    // answer arrives through the push handlers just registered, and asking is how a rotated token is learned.
    ports.pushNotifications.register()
    return composed
}

/**
 * The entry ports' ONE registration each, on composition. `listen` only registers: no handler runs and no host is
 * built here. Three handlers are **host-first** — they assemble the status host before anything else, because each
 * is a person reaching the app: `Lifecycle.onForeground` (on the composition lane, before the foreground flow),
 * `Links.onLink` and `Ui.onLive`. Nothing else assembles it: a push token, a silent push, a scheduled or transfer wake
 * builds no host (a cold background start installs no permission-grant subscription).
 */
private fun listenToEntries(composed: ComposedApp, process: ProcessServices, ports: AppPorts) {
    val core = composed.core
    ports.lifecycle.listen(lifecycleHandlers(core, assembleHost = { composed.host }))
    ports.pushNotifications.listen(pushHandlers(core))
    ports.devControls.listen(devHandlers(core))
    ports.links.listen(
        LinkHandlers(
            onLink = { delivery ->
                // Host-first: a link is a person opening the app, whatever the delivery turns out to be.
                val host = composed.host
                onLink(delivery, process, ports) { url -> host.onOpenUrl(url) }
            },
        ),
    )
    ports.ui.listen(
        UiHandlers(
            onIntent = { intent -> composed.host.onIntent(intent) },
            // Idempotent: the host is assembled once; each live screen is shown its current state before its first
            // frame, so no screen ever renders an empty one.
            onLive = { ports.ui.show(composed.host.container.stateFlow.value) },
        ),
    )
}

/**
 * A delivered link, through the pure `model/` filter (capability `join-event`): a web link carrying a URL opens the
 * join gate on it, fragment intact; every other delivery is logged by its outcome, so "we were called" stays
 * distinguishable from "we were never called".
 */
private fun onLink(delivery: LinkDelivery, process: ProcessServices, ports: AppPorts, open: (String) -> Unit) {
    ports.log.invocation(
        process.entryContext,
        delivery.hook,
        params = userActivityParams(delivery.activityType, delivery.url),
        result = { outcome: EventLinkDelivery -> outcome.summary },
    ) {
        forwardEventLink(delivery.isWebLink, delivery.activityType, delivery.url) { url ->
            ports.log.invocation(process.entryContext, "onOpenUrl", params = "url=$url") { open(url) }
        }
    }
}

/**
 * Every read-model the status host reduces over, from the composed [core] and its [ports] — all of them, named
 * explicitly, so no host built from them can observe fewer than another.
 */
private fun statusSourcesOf(
    core: AppCore,
    ports: AppPorts,
    pending: MutablePendingJoinSource = MutablePendingJoinSource(),
): StatusSources = StatusSources(
    sync = core.syncStatusSource,
    permission = core.photoPermission,
    config = core.membership,
    creation = core.creationStatus,
    rename = core.renameStatus,
    download = core.downloadStatusSource,
    attested = core.attested,
    pending = pending,
    versionRefusal = core.versionRefusal,
    appStoreUrl = ports.appStoreUrl,
)
