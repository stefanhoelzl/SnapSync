package app.snapsync.android.buildinfo

import android.os.Build
import app.snapsync.model.DiagnosticEnvironment
import app.snapsync.model.Platform
import app.snapsync.model.StoreKind
import app.snapsync.model.StoreLink
import app.snapsync.model.uploadersCarried
import app.snapsync.ports.BuildInfo

/**
 * The [BuildInfo] port on Android: what the running build is. The app module's generated `BuildConfig` carries the
 * build's own constants, which no adapter module can read, so the root hands those in ([appVersion], [uploadHost],
 * [bootLines], [playStoreUrl]); what the platform itself answers — the OS release, the device model — this adapter reads.
 *
 * Android has no OS-driven upload mechanism (the app's uploader is its only one). Its store is Google Play — known only
 * once the deployment names the listing's page. Where it reports crashes to is `BuildConfig`'s too — present only on a
 * distributed build.
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
    /**
     * The build's Google Play page, EMPTY until the listing is public (production launch) — the update notice then
     * offers no store at all, which beats an offer that lands on Play's "not found" (capability `app-update-required`).
     */
    playStoreUrl: String,
    /** The application's package name — the one process Android runs this app in. */
    override val processId: String,
    /** The build's crash-reporting destination, blank for a build that reports nowhere (every build but a distributed one). */
    dsn: String,
    /** The environment the build's crash reports are filed under (`production`, `development`). */
    private val reporterEnvironment: String,
) : BuildInfo {
    override val store: StoreLink? = playStoreUrl.takeIf { it.isNotEmpty() }?.let {
        StoreLink(
            it,
            StoreKind.GOOGLE_PLAY,
        )
    }

    override val platform: Platform = Platform.ANDROID

    override val osSupportsOsDrivenUpload: Boolean = false

    override val dsn: String? = dsn.takeIf { it.isNotBlank() }

    override val diagnostics: DiagnosticEnvironment by lazy {
        DiagnosticEnvironment(
            appVersion = appVersion,
            buildNumber = buildNumber,
            osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            uploadTier = uploadersCarried(osSupportsOsDrivenUpload),
            uploadBase = uploadHost,
            reporterEnvironment = reporterEnvironment,
        )
    }
}
