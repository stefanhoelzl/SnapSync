package app.snapsync.mock

import app.snapsync.model.Platform
import app.snapsync.model.StoreLink
import app.snapsync.ports.BuildInfo

/**
 * What the running build IS, as a mock (`docs/testing.md`, "Mocks"): the constants a device's bundle carries. Its one
 * lever is [declaredVersion] — a cell the caller holds, so an operator plays a member updating the app in place — read
 * per use, as the backend mock reads the same cell per request. Everything else is fixed at construction: an off-device
 * build knows no device, so its facts read `unknown` unless given them.
 */
class BuildInfoMock(
    /** The backend's device-facing base, carrying exactly one version prefix. */
    val uploadHost: String = "https://in-memory.backend/api/v2",
    val declaredVersion: DeclaredVersion = DeclaredVersion("99.0"),
    /** Where the build reports, or `null` for one that reports nowhere (a dev build keeps its bug report). */
    val dsn: String? = null,
    /** The build's store page — the update-required screen's one remedy. */
    val store: StoreLink? = null,
    /** The push environment the build's tokens belong to. */
    val pushEnvironment: String = "sandbox",
    val buildNumber: String = UNKNOWN,
    val osVersion: String = UNKNOWN,
    val deviceModel: String = UNKNOWN,
    val reporterEnvironment: String = UNKNOWN,
    /** The platform the build is for. There is no JVM value: the JVM world plays a phone. */
    val platform: Platform = Platform.IOS,
    /** The process's own identifier, or `null` for none. */
    val processId: String? = null,
) {
    fun port(): BuildInfo = Face()

    /** The port face a process is handed. */
    internal inner class Face : BuildInfo {
        override val appVersion: String get() = declaredVersion.value.orEmpty()
        override val uploadHost: String = this@BuildInfoMock.uploadHost
        override val store: StoreLink? = this@BuildInfoMock.store
        override val buildNumber: String = this@BuildInfoMock.buildNumber
        override val pushEnvironment: String = this@BuildInfoMock.pushEnvironment
        override val osVersion: String = this@BuildInfoMock.osVersion
        override val deviceModel: String = this@BuildInfoMock.deviceModel
        override val reporterEnvironment: String = this@BuildInfoMock.reporterEnvironment
        override val dsn: String? = this@BuildInfoMock.dsn
        override val platform: Platform = this@BuildInfoMock.platform
        override val processId: String? = this@BuildInfoMock.processId
    }
}

/** What an off-device build reads for a device fact it does not have. */
private const val UNKNOWN = "unknown"
