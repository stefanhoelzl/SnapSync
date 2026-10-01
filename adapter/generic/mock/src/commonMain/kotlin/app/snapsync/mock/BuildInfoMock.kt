package app.snapsync.mock

import app.snapsync.model.DiagnosticEnvironment
import app.snapsync.model.Platform
import app.snapsync.model.StoreLink
import app.snapsync.ports.BuildInfo

/**
 * What the running build IS, as a mock (`docs/testing.md`, "Mocks"): the constants a device's bundle carries. Its one
 * lever is [declaredVersion] — a cell the caller holds, so an operator plays a member updating the app in place — read
 * per use, as the backend mock reads the same cell per request. Everything else is fixed at construction: an off-device
 * build carries no OS-driven upload mechanism, and no device facts for a dump or boot banner unless given them.
 */
class BuildInfoMock(
    /** The backend's device-facing base, carrying exactly one version prefix. */
    val uploadHost: String = "https://in-memory.backend/api/v2",
    val declaredVersion: DeclaredVersion = DeclaredVersion("99.0"),
    /** Where the build reports, or `null` for one that reports nowhere (a dev build keeps its bug report). */
    val dsn: String? = null,
    /** The build's store page — the update-required screen's one remedy. */
    val store: StoreLink? = null,
    /** The APNs environment the build's push tokens belong to. */
    val apnsEnvironment: String = "sandbox",
    val bootLines: List<String> = emptyList(),
    /** The platform the build is for. There is no JVM value: the JVM world plays a phone. */
    val platform: Platform = Platform.IOS,
    /** The process's own identifier, or `null` for none. */
    val processId: String? = null,
    /** The build/OS/device facts a dump and a crash report carry. */
    val diagnostics: DiagnosticEnvironment = DiagnosticEnvironment.UNKNOWN,
) {
    fun port(): BuildInfo = object : BuildInfo {
        override val appVersion: String get() = declaredVersion.value.orEmpty()
        override val uploadHost: String = this@BuildInfoMock.uploadHost
        override val store: StoreLink? = this@BuildInfoMock.store
        override val apnsEnvironment: String = this@BuildInfoMock.apnsEnvironment
        override val osSupportsOsDrivenUpload: Boolean = false
        override val diagnostics: DiagnosticEnvironment = this@BuildInfoMock.diagnostics
        override val dsn: String? = this@BuildInfoMock.dsn
        override val bootLines: List<String> = this@BuildInfoMock.bootLines
        override val platform: Platform = this@BuildInfoMock.platform
        override val processId: String? = this@BuildInfoMock.processId
    }
}
