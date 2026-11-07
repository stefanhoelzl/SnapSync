package app.snapsync.entrypoints

import app.snapsync.application.EntryPointCall
import app.snapsync.application.event.CreateEvent
import app.snapsync.application.report.SendBugReport
import app.snapsync.domain.event.CreateResult
import app.snapsync.domain.invite.RendersInvite
import app.snapsync.domain.report.ReportOutcome
import app.snapsync.domain.request.AppLinkRequest
import app.snapsync.domain.request.CreateRequest
import app.snapsync.domain.request.ReportRequest
import app.snapsync.domain.request.ShareRequest
import app.snapsync.domain.service.ServiceFault
import app.snapsync.feature.diagnostics.CollectDiagnosticDump
import app.snapsync.inbound.Log
import app.snapsync.inbound.of
import app.snapsync.query.RequestStatusSink
import app.snapsync.scheduling.BackgroundEntryPoint
import app.snapsync.scheduling.Run
import app.snapsync.scheduling.Runtime
import app.snapsync.services.platform.PhotoAccessAsks
import app.snapsync.services.platform.SystemSurfaces

// ── The member's taps that hand something to the platform, or start work no other trigger starts ─────────────────────
//
// Each is a command on the screen's door (presentation's `UserCommands`): fired, never awaited, its work running here
// under the tap's own label. A hand-off to the platform — a sheet, a prompt, the Settings page — is the adapter's to
// put on the main thread; nothing acts on its answer, but a refusal is logged, because the member then tapped and
// nothing happened (`docs/architecture.md`, "Absence is never silent").

/**
 * The member asked to create an event: minted, and answered on the creation status with its id and key — which the
 * screen hands to the join gate a scanned invite takes.
 */
class OnCreateTapped(
    runtime: Runtime,
    private val create: CreateEvent,
    private val statuses: RequestStatusSink<CreateResult>,
) : BackgroundEntryPoint<CreateRequest>(runtime) {
    @OptIn(EntryPointCall::class)
    override suspend fun run(arg: CreateRequest, on: Run) = on.steps {
        statuses.begun(arg.id)
        ensure("answer") { statuses.settle(arg.id, CreateResult.Failed(ServiceFault.UNREACHABLE)) }
        val result = required { create(arg) }
        required { statuses.done(arg.id, result) }
    }
}

/** The member shared the invite: the platform's share sheet. */
class OnShareTapped(runtime: Runtime, private val surfaces: SystemSurfaces) :
    BackgroundEntryPoint<ShareRequest>(runtime) {
    // The one place the invite's text leaves the core: into the platform's share sheet, which shows it to the member.
    @OptIn(RendersInvite::class)
    override suspend fun run(arg: ShareRequest, on: Run) = on.steps {
        required { surfaces.share(arg.text.raw, arg.title) }
    }
}

/** The member opened a page outside the app — the store's, the help page. */
class OnOpenLinkTapped(runtime: Runtime, private val surfaces: SystemSurfaces) : BackgroundEntryPoint<AppLinkRequest>(
    runtime,
) {
    override suspend fun run(arg: AppLinkRequest, on: Run) = on.steps {
        required { surfaces.open(arg) }
    }
}

/** The member opened the app's own Settings page. */
class OnOpenSettingsTapped(runtime: Runtime, private val surfaces: SystemSurfaces) :
    BackgroundEntryPoint<Unit>(runtime) {
    override suspend fun run(arg: Unit, on: Run) = on.steps {
        required { surfaces.openSettings() }
    }
}

/**
 * The member asked for photo access: the platform's question. The screen follows the grant, never this answer — a
 * grant that changed arrives through the grant's own observer.
 */
class OnRequestAccessTapped(runtime: Runtime, private val asks: PhotoAccessAsks) : BackgroundEntryPoint<Unit>(runtime) {
    override suspend fun run(arg: Unit, on: Run) = on.steps {
        required { asks.ask() }
    }
}

/** The member chose to revise a partial grant's selection: the platform's picker; the selection arrives on its own. */
class OnChoosePhotosTapped(runtime: Runtime, private val asks: PhotoAccessAsks) : BackgroundEntryPoint<Unit>(runtime) {
    override suspend fun run(arg: Unit, on: Run) = on.steps {
        required { asks.widen() }
    }
}

/**
 * The member sent a bug report: the diagnostic dump — both device logs read, ~700 KB — sent where the build reports
 * and kept on the device where it does not, answered on the report status with what the member is told.
 */
class OnSendDiagnosticsTapped(
    runtime: Runtime,
    private val dump: CollectDiagnosticDump,
    private val send: SendBugReport,
    private val statuses: RequestStatusSink<ReportOutcome>,
) : BackgroundEntryPoint<ReportRequest>(runtime) {
    private val log = Log("OnSendDiagnosticsTapped")

    @OptIn(EntryPointCall::class)
    override suspend fun run(arg: ReportRequest, on: Run) = on.steps {
        statuses.begun(arg.id)
        ensure("answer") { statuses.settle(arg.id, ReportOutcome.NOT_SENT) }
        val report = required { dump.collect(arg.note, arg.context) }
        val result = required { send(report) }
        log.i("diagnostic dump", "result" of result, "screen" of arg.context.screen)
        required { statuses.done(arg.id, result.outcome) }
    }
}
