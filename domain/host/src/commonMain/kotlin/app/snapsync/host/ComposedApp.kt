package app.snapsync.host

import app.snapsync.compose.AppCore
import app.snapsync.compose.AppPorts
import app.snapsync.compose.snapSyncApp
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.MutablePendingJoinSource
import app.snapsync.presentation.StatusContainerHost
import app.snapsync.presentation.StatusDiagnostics
import app.snapsync.presentation.StatusSources
import kotlinx.coroutines.CoroutineScope

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
 * The gallery's handlers are registered here too, on composition, for the same reason: an import finishing in a
 * background wake must find them. Registering starts nothing — the selection observer opens at host assembly.
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
fun snapSyncHost(scope: CoroutineScope, ports: AppPorts): ComposedApp {
    val core = snapSyncApp(scope, ports)
    // The event ports' ONE registration each, on composition — a background wake's import needs its handlers as much
    // as a foreground launch does. `listen` only registers: the selection observer opens at host assembly below.
    ports.gallery.listen(core.galleryHandlers)
    core.installPushRegistration()
    val formatter = CutoffFormatter(now = ports.displayClock::now, zone = ports.timeZone.current())
    return ComposedApp(core, ports, formatter) {
        // Host assembly: the permission-grant collectors install ONLY from here (see [ComposedApp]).
        core.installPermissionSubscriptions()
        StatusContainerHost(
            statusSourcesOf(core, ports),
            scope = scope,
            cutoffFormatter = formatter,
            commands = core.userCommands,
            queries = core.userQueries,
            // Whose word authorizes a headless join: the root's, never the link's (capability `join-event`).
            inviteLinkHints = ports.rigSwitches.inviteLinkHints,
            diagnostics = StatusDiagnostics(
                log = { message -> ports.log.i { message } },
                // `Error`: the threshold at which a Kermit line becomes a crash-reporting event rather than a
                // breadcrumb (capability `privacy-security`) — a command that failed outright is exactly what
                // should reach the operator.
                onIntentError = { throwable -> ports.log.e(throwable) { "user command failed" } },
            ),
        )
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
    permission = ports.photoAccess.permission,
    config = ports.configSource.config,
    creation = core.creationStatus,
    rename = core.renameStatus,
    download = core.downloadStatusSource,
    attested = core.attestation.attested,
    pending = pending,
    versionRefusal = core.versionGate.refusal,
    appStoreUrl = ports.appStoreUrl,
)
