package app.snapsync.config

import app.snapsync.logging.appMarketingVersion
import app.snapsync.logging.deviceDiagnosticEnvironment
import app.snapsync.model.DiagnosticEnvironment
import app.snapsync.model.StoreKind
import app.snapsync.model.StoreLink
import app.snapsync.model.uploadersCarried
import app.snapsync.ports.BuildInfo

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
    override val diagnostics: DiagnosticEnvironment by lazy {
        deviceDiagnosticEnvironment(uploadersCarried(osSupportsOsDrivenUpload))
    }
}
