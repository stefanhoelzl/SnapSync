package app.snapsync.composition

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
 * The composed app: the core, and the status host over it (spec `module-architecture`, "One shared
 * composition").
 *
 * [host] is `by lazy`, and touching it is **host assembly**: it installs the permission and push-registration
 * subscriptions, then builds the host. That is the iOS shell's timing, and it is load-bearing — a cold background
 * wake that merely touches [core] must install no collector and run no launch reconcile.
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
     * installs no subscription (its operator plays the OS) and decorates its command bundle for the inspector,
     * but observes exactly what the phone's host observes. [pending] is that harness's seam.
     */
    fun statusSources(pending: MutablePendingJoinSource = MutablePendingJoinSource()): StatusSources =
        statusSourcesOf(core, ports, pending)
}

/**
 * Compose the app over [ports]: the core from [snapSyncApp], and — on first touch of [ComposedApp.host] — the
 * host-assembly subscriptions and the status host, observing every read-model the core exposes.
 *
 * A root supplies ports and nothing else. It builds no host, installs no subscription and passes no read-model:
 * a source added to the host is wired here once, and every root's host observes it. That is the drift this
 * function exists to end — the control channel's JVM host once built its status host without the version
 * refusal and never registered for pushes, and nothing said so.
 */
fun snapSyncHost(scope: CoroutineScope, ports: AppPorts): ComposedApp {
    val core = snapSyncApp(scope, ports)
    val formatter = CutoffFormatter(now = ports.displayClock::now, zone = ports.timeZone.current())
    return ComposedApp(core, ports, formatter) {
        // Host assembly: the two collectors install ONLY from here (see [ComposedApp]).
        core.installPermissionSubscriptions()
        core.installPushRegistration()
        StatusContainerHost(
            statusSourcesOf(core, ports),
            scope = scope,
            cutoffFormatter = formatter,
            commands = core.userCommands,
            queries = core.userQueries,
            diagnostics = StatusDiagnostics(
                log = { message -> ports.log.i { message } },
                // `Error`: the threshold at which a Kermit line becomes a crash-reporting event rather than a
                // breadcrumb (capability `crash-reporting`) — a command that failed outright is exactly what
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
