package app.snapsync.mock

import app.snapsync.model.DiagnosticEnvironment
import app.snapsync.ports.BuildInfo

/**
 * A [BuildInfo] over fixed values — what an off-device composition's build IS, for a test that composes a process.
 * Every member is the value given; nothing is read from anywhere.
 */
fun fixedBuildInfo(
    uploadHost: String = "https://in-memory.backend/api/v2",
    appVersion: String = "99.0",
    dsn: String? = null,
    appStoreUrl: String? = null,
    apnsEnvironment: String = "sandbox",
    osSupportsOsDrivenUpload: Boolean = false,
    bootLines: List<String> = emptyList(),
): BuildInfo = FixedBuildInfo(
    appVersion, uploadHost, appStoreUrl, apnsEnvironment, osSupportsOsDrivenUpload, DiagnosticEnvironment.UNKNOWN, dsn,
    bootLines,
)

private class FixedBuildInfo(
    override val appVersion: String,
    override val uploadHost: String,
    override val appStoreUrl: String?,
    override val apnsEnvironment: String,
    override val osSupportsOsDrivenUpload: Boolean,
    override val diagnostics: DiagnosticEnvironment,
    override val dsn: String?,
    override val bootLines: List<String>,
) : BuildInfo
