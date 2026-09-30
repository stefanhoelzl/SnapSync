package app.snapsync.android.buildinfo

import android.os.Build
import app.snapsync.model.DiagnosticEnvironment
import app.snapsync.model.uploadersCarried
import app.snapsync.ports.BuildInfo

/**
 * The [BuildInfo] port on Android: what the running build is. The app module's generated `BuildConfig` carries the
 * build's own constants, which no adapter module can read, so the root hands those in ([appVersion], [uploadHost],
 * [bootLines]); what the platform itself answers — the OS release, the device model — this adapter reads.
 *
 * Android has no OS-driven upload mechanism (the app's uploader is its only one), no App Store page, and no crash
 * channel yet, so it reports nowhere.
 */
class AndroidBuildInfo(
    override val appVersion: String,
    /** The build's version code, as the dump names it. */
    private val buildNumber: String,
    override val uploadHost: String,
    override val bootLines: List<String>,
    /**
     * The push environment an FCM token is registered under: the Firebase project that issued it, the one the
     * backend's FCM sender sends under (the port's name is APNs vocabulary; on Android it is the project id).
     */
    override val apnsEnvironment: String,
) : BuildInfo {
    override val appStoreUrl: String? = null

    override val osSupportsOsDrivenUpload: Boolean = false

    override val dsn: String? = null

    override val diagnostics: DiagnosticEnvironment by lazy {
        DiagnosticEnvironment(
            appVersion = appVersion,
            buildNumber = buildNumber,
            osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            uploadTier = uploadersCarried(osSupportsOsDrivenUpload),
            uploadBase = uploadHost,
            reporterEnvironment = "none",
        )
    }
}
