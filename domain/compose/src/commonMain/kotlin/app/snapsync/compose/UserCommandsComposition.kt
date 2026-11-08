package app.snapsync.compose

import app.snapsync.application.report.SendBugReport
import app.snapsync.data.report.SavedReport
import app.snapsync.data.system.PhotoAccessAsks
import app.snapsync.data.system.SystemSurfaces
import app.snapsync.domain.invite.InviteLinkVerdict
import app.snapsync.domain.report.ReportOutcome
import app.snapsync.domain.request.AppLinkRequest
import app.snapsync.domain.request.CreateRequest
import app.snapsync.domain.request.JoinRequest
import app.snapsync.domain.request.KeyRestoreRequest
import app.snapsync.domain.request.MembershipReadRequest
import app.snapsync.domain.request.MobileDataRequest
import app.snapsync.domain.request.RenameRequest
import app.snapsync.domain.request.ReportRequest
import app.snapsync.domain.request.SettingsRequest
import app.snapsync.domain.request.ShareRequest
import app.snapsync.domain.request.SwitchRequest
import app.snapsync.entrypoints.OnChoosePhotosTapped
import app.snapsync.entrypoints.OnCreateTapped
import app.snapsync.entrypoints.OnInviteOpened
import app.snapsync.entrypoints.OnOpenLinkTapped
import app.snapsync.entrypoints.OnOpenSettingsTapped
import app.snapsync.entrypoints.OnRequestAccessTapped
import app.snapsync.entrypoints.OnSendDiagnosticsTapped
import app.snapsync.entrypoints.OnShareTapped
import app.snapsync.inbound.Background
import app.snapsync.query.RequestStatuses

/**
 * **What the member can ask for, as the core's entry points** (`docs/architecture.md`, "Commands cross one door"): each
 * an entry point that runs on the composition lane under its own label, holding the process's background time. The
 * host hands them to presentation's door (`UserCommands`) whole; presentation fires them and reads each answer from its
 * status, under the request's id.
 */
class CoreCommands internal constructor(
    val leave: Background<Unit>,
    val create: Background<CreateRequest>,
    val join: Background<JoinRequest>,
    val share: Background<ShareRequest>,
    val requestAccess: Background<Unit>,
    val openSettings: Background<Unit>,
    val openLink: Background<AppLinkRequest>,
    val choosePhotos: Background<Unit>,
    val reconfigure: Background<SettingsRequest>,
    val rename: Background<RenameRequest>,
    val switch: Background<SwitchRequest>,
    val sendDiagnostics: Background<ReportRequest>,
    val setMobileData: Background<MobileDataRequest>,
    val restoreKey: Background<KeyRestoreRequest>,
    val retryMembershipRead: Background<MembershipReadRequest>,
    val inviteOpened: Background<InviteLinkVerdict>,
)

/** The core's commands, over its entry points; the taps handing something to the platform are built here. */
internal fun AppCore.commandsFor(reports: RequestStatuses<ReportOutcome>): CoreCommands {
    val surfaces = SystemSurfaces(ports.systemUi)
    val asks = PhotoAccessAsks(ports.gallery)
    return CoreCommands(
        leave = work.leaveConfirmed,
        create = OnCreateTapped(runtime, createEvent, creationStatus),
        join = work.joinConfirmed,
        share = OnShareTapped(runtime, surfaces),
        requestAccess = OnRequestAccessTapped(runtime, asks),
        openSettings = OnOpenSettingsTapped(runtime, surfaces),
        openLink = OnOpenLinkTapped(runtime, surfaces),
        choosePhotos = OnChoosePhotosTapped(runtime, asks),
        reconfigure = work.settingsChanged,
        rename = work.renameRequested,
        switch = work.inviteForOtherEvent,
        sendDiagnostics = OnSendDiagnosticsTapped(
            runtime,
            assembleBugReport,
            SendBugReport(started.crash, SavedReport(process.files)),
            reports,
        ),
        setMobileData = work.mobileDataChanged,
        restoreKey = work.keyRestoreRequested,
        retryMembershipRead = work.membershipReadRetried,
        inviteOpened = OnInviteOpened(runtime, openedInvites),
    )
}
