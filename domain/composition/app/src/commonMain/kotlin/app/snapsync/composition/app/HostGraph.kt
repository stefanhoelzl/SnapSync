package app.snapsync.composition.app

import app.snapsync.data.process.BuildRead
import app.snapsync.entrypoints.AssembleHost
import app.snapsync.entrypoints.HostFacts
import app.snapsync.entrypoints.HostIntents
import app.snapsync.entrypoints.HostParts
import app.snapsync.entrypoints.HostScreen
import app.snapsync.entrypoints.NetworkReturns
import app.snapsync.entrypoints.OnNetworkReturned
import app.snapsync.entrypoints.PermissionSubscriptions
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.presentation.DeviceVerification
import app.snapsync.presentation.EventKeyView
import app.snapsync.presentation.PlatformCommands
import app.snapsync.presentation.RequestStatusFlows
import app.snapsync.presentation.ScreenQueries
import app.snapsync.presentation.UserCommand
import app.snapsync.presentation.UserCommands
import app.snapsync.scheduling.ScreenDisplay
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope

/**
 * **The status host's wiring** — everything it reads, the door its taps go through, and its assembly. Nothing here
 * builds the host: [assembly] does, once, when a person reaches the app (`AssembleHost`), so a cold background wake
 * assembles nothing and installs no permission subscription.
 */
class HostGraph(
    app: AppGraph,
    ports: AppPorts,
    commands: CommandsGraph,
    cutoffFormatter: CutoffFormatter,
    scope: CoroutineScope,
    reduction: CoroutineDispatcher,
) {
    /** Everything the status host reads from the app's graph — all of it, named explicitly. */
    val queries: ScreenQueries = ScreenQueries(
        sharing = app.sharingProgress,
        receiving = app.downloadProgress,
        grant = app.photoGrant.state,
        membership = app.membership,
        key = EventKeyView(app.keyQuery.presence, app.keyQuery),
        invites = app.invitesQuery.state,
        creation = app.creationStatus.status,
        requests = RequestStatusFlows(
            app.membershipStatuses.join.status,
            app.membershipStatuses.settings.status,
            app.membershipStatuses.switch.status,
            app.membershipStatuses.rename.status,
            app.membershipStatuses.read.status,
            app.reportStatus.status,
        ),
        verification = DeviceVerification(app.attested, app.refusal, app.attestationFault, app.serviceFault),
        versionRefusal = app.versionRefusal,
        network = app.network.query,
        mobileData = app.mobileData.query,
        foreground = app.foregroundReturns,
        inFront = app.inFront,
    )

    /** The core's commands, as the screen's door: each fired, never awaited. */
    val door: UserCommands = UserCommands(
        leave = UserCommand(commands.leave),
        create = UserCommand(commands.create),
        join = UserCommand(commands.join),
        platform = PlatformCommands(
            share = UserCommand(commands.share),
            requestAccess = UserCommand(commands.requestAccess),
            openSettings = UserCommand(commands.openSettings),
            openLink = UserCommand(commands.openLink),
            choosePhotos = UserCommand(commands.choosePhotos),
        ),
        reconfigure = UserCommand(commands.reconfigure),
        rename = UserCommand(commands.rename),
        switch = UserCommand(commands.switch),
        sendDiagnostics = UserCommand(commands.sendDiagnostics),
        setMobileData = UserCommand(commands.setMobileData),
        restoreKey = UserCommand(commands.restoreKey),
        retryMembershipRead = UserCommand(commands.retryMembershipRead),
        inviteOpened = UserCommand(commands.inviteOpened),
    )

    /** The host's assembly — run by the foreground, a live screen or a tap, and by nothing else. */
    val assembly: AssembleHost = AssembleHost(
        permissions = PermissionSubscriptions(
            app.observation,
            app.photoGrant,
            app.work.uploaderChange,
            app.work.grantChanged,
            app.album.accessChanges,
            app.gather.onAccessChanged,
            app.selection,
            app.library,
            app.work.selectionAnswerable,
        ),
        network = NetworkReturns(
            app.observation,
            app.network.status,
            OnNetworkReturned(app.runtime, app.work.foregroundWork, app.work.wakeWork),
        ),
        parts = HostParts(queries, app.shareableCount, app.joinDetails, door, cutoffFormatter, scope, reduction),
        facts = HostFacts(BuildRead(ports.process.build), app.started.crash, app.album.filing),
        display = ScreenDisplay(app.runtime, ports.ui),
    )

    /** A person's taps, handed to the status host's intent table. */
    val intents: HostIntents = HostIntents(assembly)

    /** A live screen's first frame. */
    val screen: HostScreen = HostScreen(assembly)
}
