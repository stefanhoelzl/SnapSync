package app.snapsync.composition.app

import app.snapsync.application.event.CreateEvent
import app.snapsync.application.report.AssembleBugReport
import app.snapsync.application.report.DeviceFacts
import app.snapsync.application.report.ReportLedger
import app.snapsync.application.report.ReportLogs
import app.snapsync.application.report.ReportState
import app.snapsync.application.report.SendBugReport
import app.snapsync.composition.process.ProcessGraph
import app.snapsync.data.crash.ReportedIdsRead
import app.snapsync.data.gallery.GrantRead
import app.snapsync.data.links.OpenedInvites
import app.snapsync.data.logs.DeviceLogTails
import app.snapsync.data.network.NetworkReadings
import app.snapsync.data.process.FootprintReading
import app.snapsync.data.process.StartedProcess
import app.snapsync.data.report.BuildEnvironment
import app.snapsync.data.report.DeviceConditionsRead
import app.snapsync.data.report.SavedReport
import app.snapsync.data.sharing.SelectionSnapshots
import app.snapsync.data.system.PhotoAccessAsks
import app.snapsync.data.system.SystemSurfaces
import app.snapsync.data.upload.OsUploader
import app.snapsync.domain.event.CreateResult
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
import app.snapsync.scheduling.Runtime

/**
 * **What the member can ask for, as the core's entry points** (`docs/architecture.md`, "Commands cross one door"): each
 * an entry point that runs on the composition lane under its own label, holding the process's background time. The
 * host graph hands them to presentation's door (`UserCommands`) whole; presentation fires them and reads each answer
 * from its status, under the request's id.
 */
class CommandsGraph(
    runtime: Runtime,
    ports: AppPorts,
    process: ProcessGraph,
    started: StartedProcess,
    work: WorkGraph,
    create: CreateEvent,
    creationStatus: RequestStatuses<CreateResult>,
    diagnostics: DiagnosticsGraph,
    reportStatus: RequestStatuses<ReportOutcome>,
    openedInvites: OpenedInvites,
) {
    private val surfaces: SystemSurfaces = SystemSurfaces(ports.systemUi)
    private val asks: PhotoAccessAsks = PhotoAccessAsks(ports.gallery)

    val leave: Background<Unit> = work.leaveConfirmed
    val create: Background<CreateRequest> = OnCreateTapped(runtime, create, creationStatus)
    val join: Background<JoinRequest> = work.joinConfirmed
    val share: Background<ShareRequest> = OnShareTapped(runtime, surfaces)
    val requestAccess: Background<Unit> = OnRequestAccessTapped(runtime, asks)
    val openSettings: Background<Unit> = OnOpenSettingsTapped(runtime, surfaces)
    val openLink: Background<AppLinkRequest> = OnOpenLinkTapped(runtime, surfaces)
    val choosePhotos: Background<Unit> = OnChoosePhotosTapped(runtime, asks)
    val reconfigure: Background<SettingsRequest> = work.settingsChanged
    val rename: Background<RenameRequest> = work.renameRequested
    val switch: Background<SwitchRequest> = work.inviteForOtherEvent
    val sendDiagnostics: Background<ReportRequest> = OnSendDiagnosticsTapped(
        runtime,
        diagnostics.assemble,
        SendBugReport(started.crash, SavedReport(process.files)),
        reportStatus,
    )
    val setMobileData: Background<MobileDataRequest> = work.mobileDataChanged
    val restoreKey: Background<KeyRestoreRequest> = work.keyRestoreRequested
    val retryMembershipRead: Background<MembershipReadRequest> = work.membershipReadRetried
    val inviteOpened: Background<InviteLinkVerdict> = OnInviteOpened(runtime, openedInvites)
}

/** The bug report's assembly — reads only: this graph's state and the device's, never written to. */
class DiagnosticsGraph(
    ports: AppPorts,
    process: ProcessGraph,
    services: ServicesGraph,
    backend: BackendGraph,
    grant: GrantRead,
    osUploader: OsUploader,
    uploader: UploaderGraph,
    receiving: ReceivingGraph,
    selection: SelectionSnapshots,
) {
    val assemble: AssembleBugReport = AssembleBugReport(
        ids = ReportedIdsRead(services.membership, services.deviceIdentity, ports.process.build),
        state = ReportState(
            environment = BuildEnvironment(ports.process.build, ports.extensionRegistry),
            membership = services.membership,
            grant = grant,
            osUploader = osUploader,
            appAdmission = uploader.admission,
            refusal = backend.attestationStatus,
        ),
        ledger = ReportLedger(uploader.progress, receiving.counts, services.membership),
        device = DeviceFacts(
            network = NetworkReadings(ports.network),
            mobileData = services.mobileData,
            conditions = DeviceConditionsRead(
                ports.deviceConditions,
                ports.process.clock,
                FootprintReading(ports.processInfo),
            ),
            selection = selection,
            grant = grant,
        ),
        // An off-device composition holds no log files, so a report assembled there is honestly empty, not fabricated.
        logs = ReportLogs(DeviceLogTails(process.files)),
    )
}
