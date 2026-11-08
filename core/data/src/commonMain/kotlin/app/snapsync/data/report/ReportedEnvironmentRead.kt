package app.snapsync.data.report

import app.snapsync.domain.report.BuildLabel
import app.snapsync.domain.report.ReportedEnvironment
import app.snapsync.domain.upload.OsSupport
import app.snapsync.format.UploadersCarriedText
import app.snapsync.ports.BuildInfo
import app.snapsync.ports.ExtensionRegistry

/** The build, OS and device a report's state section names — read when a report is assembled. */
interface EnvironmentRead {
    fun environment(): ReportedEnvironment
}

/**
 * The running build's [EnvironmentRead]: its own facts, and which uploaders this OS carries — the app's always, the
 * upload extension's where the OS has the mechanism. Read from [build] and [registry] when asked, never as it is built.
 */
class BuildEnvironment(private val build: BuildInfo, private val registry: ExtensionRegistry) : EnvironmentRead {
    override fun environment(): ReportedEnvironment = ReportedEnvironment(
        build = BuildLabel(build.appVersion, build.buildNumber),
        osVersion = build.osVersion,
        deviceModel = build.deviceModel,
        uploaders = UploadersCarriedText.of(OsSupport.of(registry.supported)),
        uploadBase = build.uploadHost,
        reporterEnvironment = build.reporterEnvironment,
    )
}
