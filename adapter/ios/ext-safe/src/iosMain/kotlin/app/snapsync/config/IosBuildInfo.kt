package app.snapsync.config

import app.snapsync.logging.appBuildVersion
import app.snapsync.logging.appMarketingVersion
import app.snapsync.logging.deviceDiagnosticEnvironment
import app.snapsync.model.DiagnosticEnvironment
import app.snapsync.model.Platform
import app.snapsync.model.StoreKind
import app.snapsync.model.StoreLink
import app.snapsync.model.uploadersCarried
import app.snapsync.ports.BuildInfo
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cValue
import platform.Foundation.NSBundle
import platform.Foundation.NSOperatingSystemVersion
import platform.Foundation.NSProcessInfo

/**
 * The [BuildInfo] port over THIS process's bundle — the generated `Deployment.plist` both bundles carry and the
 * bundle's own version. Each process builds its own: the app and the upload extension read their own bundle, so
 * neither can bind the other's answer. Every value is read on first use, once, as a constant of the running build.
 *
 * [osSupportsOsDrivenUpload] is the root's one OS fact, and [bootLines] its process's banner — both handed in, because
 * they name the process rather than the bundle.
 */
class IosBuildInfo(
    override val osSupportsOsDrivenUpload: Boolean,
    override val bootLines: List<String>,
) : BuildInfo {
    override val appVersion: String by lazy { appMarketingVersion() }
    override val uploadHost: String by lazy { bakedUploadBase() }
    override val store: StoreLink? by lazy { bakedAppStoreUrl()?.let { StoreLink(it, StoreKind.APP_STORE) } }
    override val apnsEnvironment: String by lazy { bakedApnsEnv() }
    override val dsn: String? by lazy { bakedSentryDsn() }
    override val platform: Platform = Platform.IOS

    /** The main bundle's id — an extension's main bundle is its `.appex` — or `null` where there is none (the simulator test executable). */
    override val processId: String? by lazy { NSBundle.mainBundle.bundleIdentifier?.takeIf { it.isNotBlank() } }
    override val diagnostics: DiagnosticEnvironment by lazy {
        deviceDiagnosticEnvironment(uploadersCarried(osSupportsOsDrivenUpload))
    }
}

/**
 * Whether this OS carries the OS-driven upload mechanism at all — the iOS 26.1 background-upload API. The app's root
 * hands it to [IosBuildInfo] (the extension, which runs only where it is carried, hands `true`).
 */
@OptIn(ExperimentalForeignApi::class)
fun osCarriesOsDrivenUpload(): Boolean =
    NSProcessInfo.processInfo.isOperatingSystemAtLeastVersion(
        cValue<NSOperatingSystemVersion> {
            majorVersion = OS_DRIVEN_UPLOAD_MAJOR
            minorVersion = OS_DRIVEN_UPLOAD_MINOR
            patchVersion = 0
        },
    )

private const val OS_DRIVEN_UPLOAD_MAJOR = 26L
private const val OS_DRIVEN_UPLOAD_MINOR = 1L

/**
 * An iOS process's boot banner, the same shape in the app and the upload extension:
 *
 * 1. the process and its build version, so a reader who concatenates the app's and the extension's files can tell
 *    runs apart;
 * 2. [processLines], what only this process states (the extension: where its log is going);
 * 3. the BAKED backend this build talks to. It names the one fact that makes an otherwise-silent failure legible: point
 *    a build at a different backend without a device reset and the ledger still says COMPLETED, so the device uploads
 *    nothing — no error, no failed request. Read beside the cycle's own `enumeration: N seen, X new, Y
 *    already-uploaded`, a changed host beside an unchanged ledger names the cause immediately.
 */
fun iosBootLines(process: String, processLines: List<String> = emptyList()): List<String> =
    listOf("=== $process process start build=${appBuildVersion()} ===") + processLines +
        "[boot] upload base = ${bakedUploadBase()}"
