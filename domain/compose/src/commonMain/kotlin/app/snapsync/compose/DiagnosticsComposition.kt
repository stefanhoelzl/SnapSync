package app.snapsync.compose

import app.snapsync.application.report.AssembleBugReport
import app.snapsync.application.report.DeviceFacts
import app.snapsync.application.report.ReportLedger
import app.snapsync.application.report.ReportLogs
import app.snapsync.application.report.ReportState
import app.snapsync.data.crash.ReportedIdsRead
import app.snapsync.data.logs.DeviceLogTails
import app.snapsync.data.network.NetworkReadings
import app.snapsync.data.process.FootprintReading
import app.snapsync.data.report.DeviceConditionsRead
import app.snapsync.domain.report.BuildLabel
import app.snapsync.domain.report.ReportedEnvironment
import app.snapsync.domain.upload.OsSupport
import app.snapsync.format.UploadersCarriedText
import app.snapsync.ports.BuildInfo
import app.snapsync.ports.ExtensionRegistry

/**
 * The bug report's assembly — reads only: this graph's state and the device's, never written to. Built on first use
 * by [AppCore.assembleBugReport], so a build whose member never reports never builds it.
 *
 * Its own file rather than `AppCore`'s body for the reason [AppNetwork] gives: `AppCore` is measured, and the `compose`
 * tier's `LargeClass` ceiling is what keeps it from absorbing every composition in the graph.
 */
internal fun AppCore.assembleBugReportFor(): AssembleBugReport = AssembleBugReport(
    ids = ReportedIdsRead(services.membership, services.deviceIdentity, ports.process.build),
    state = ReportState(
        environment = reportedEnvironment(ports.process.build, ports.extensionRegistry),
        membership = services.membership,
        grant = grant,
        osUploader = osUploader,
        appAdmission = appUploader.admission,
        refusal = attestationStatus,
    ),
    ledger = ReportLedger(appUploader.progress, receiving.counts, services.membership),
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

/**
 * The build, OS and device a report's state section names: the build's own facts, and which uploaders this OS carries
 * — the app's always, the upload extension's where the OS has the mechanism.
 */
internal fun reportedEnvironment(build: BuildInfo, registry: ExtensionRegistry): ReportedEnvironment =
    ReportedEnvironment(
        build = BuildLabel(build.appVersion, build.buildNumber),
        osVersion = build.osVersion,
        deviceModel = build.deviceModel,
        uploaders = UploadersCarriedText.of(OsSupport.of(registry.supported)),
        uploadBase = build.uploadHost,
        reporterEnvironment = build.reporterEnvironment,
    )
