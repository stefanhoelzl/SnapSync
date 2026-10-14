@file:OptIn(ExperimentalForeignApi::class)

package app.snapsync.config

import app.snapsync.logging.appMarketingVersion
import app.snapsync.model.Never
import app.snapsync.model.NeverAnswers
import app.snapsync.model.Platform
import app.snapsync.model.Store
import app.snapsync.ports.BuildInfo
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.Foundation.NSBundle
import platform.Foundation.NSProcessInfo
import platform.posix.uname
import platform.posix.utsname

/**
 * The [BuildInfo] port over THIS process's bundle — the generated `Deployment.plist` both bundles carry, the bundle's
 * own version — and the running OS and device. Each process builds its own: the app and the upload extension read
 * their own bundle, so neither can bind the other's answer. Every value is read on first use, once, as a constant of
 * the running build. [bundle] is this process's own, the main bundle, everywhere but a contract binding, which hands in a
 * bundle it built.
 */
@NeverAnswers(
    Never(member = "platform", answer = "Platform.ANDROID"),
)
class IosBuildInfo(private val bundle: NSBundle = NSBundle.mainBundle) : BuildInfo {
    private val deployment by lazy {
        if (bundle == NSBundle.mainBundle) DeploymentValues.main else DeploymentValues.of(bundle)
    }
    override val appVersion: String by lazy { appMarketingVersion() }
    override val buildNumber: String by lazy { bundleValue("CFBundleVersion") }
    override val uploadHost: String by lazy { bakedUploadBase(deployment) }
    override val store: Store? by lazy { bakedAppStoreUrl(deployment)?.let { Store(APP_STORE, it) } }
    override val pushEnvironment: String by lazy { bakedApnsEnv(deployment) }
    override val osVersion: String by lazy { NSProcessInfo.processInfo.operatingSystemVersionString }
    override val deviceModel: String by lazy { hardwareModel() }

    /**
     * `?` deliberately, never a guess: the value is absent only on a build with no readable deployment, which also
     * carries no DSN (the resolver derives both from one channel), so no report is ever filed under it.
     */
    override val reporterEnvironment: String by lazy { deploymentValue("sentryEnvironment", deployment) ?: "?" }
    override val dsn: String? by lazy { bakedSentryDsn(deployment) }
    override val platform: Platform = Platform.IOS

    /** The main bundle's id — an extension's main bundle is its `.appex` — or `null` where there is none (the simulator test executable). */
    override val processId: String? by lazy { bundle.bundleIdentifier?.takeIf { it.isNotBlank() } }
}

/** A key of the process's own bundle, `?` when it carries none — never Kotlin's `"null"`, which reads as a value. */
private fun bundleValue(key: String): String = NSBundle.mainBundle.objectForInfoDictionaryKey(key) as? String ?: "?"

/**
 * The hardware identifier — e.g. `iPhone12,8` — from `uname()`, not the UI framework's model: this module is linked by
 * the extension, where that framework is forbidden, and `uname` gives the answer worth having where the UI framework
 * would only say "iPhone". `?` when the call fails; never a guess.
 */
private fun hardwareModel(): String = memScoped {
    val info = alloc<utsname>()
    if (uname(info.ptr) != 0) return@memScoped "?"
    info.machine.toKString().ifBlank { "?" }
}

/** The store an iPhone build is distributed through, as the update notice names it: a brand, never translated. */
private const val APP_STORE = "App Store"
