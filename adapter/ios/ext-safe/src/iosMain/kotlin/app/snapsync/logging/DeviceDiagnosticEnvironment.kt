package app.snapsync.logging

import app.snapsync.config.bakedUploadBase
import app.snapsync.config.deploymentValue
import app.snapsync.model.DiagnosticEnvironment
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
 * The build/OS/device facts a diagnostic dump's state section carries (capability
 * `diagnostic-logging`) — read here rather than in the shell, which holds no decisions.
 *
 * The device model comes from `uname()` rather than `UIDevice.model`: this module is linked by the
 * **extension**, where the extension-safety gate forbids the app-only UI framework outright (naming
 * it here, even in prose, fails that guard — it reads source text). `uname` also gives the answer
 * worth having — `iPhone12,8` — where the UI framework's own model string would only say "iPhone".
 */
@OptIn(ExperimentalForeignApi::class)
fun deviceDiagnosticEnvironment(uploadTier: String): DiagnosticEnvironment {
    val bundle = NSBundle.mainBundle
    return DiagnosticEnvironment(
        appVersion = bundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "?",
        buildNumber = bundle.objectForInfoDictionaryKey("CFBundleVersion") as? String ?: "?",
        osVersion = NSProcessInfo.processInfo.operatingSystemVersionString,
        deviceModel = hardwareModel(),
        uploadTier = uploadTier,
        uploadBase = bakedUploadBase(),
        // `"?"` deliberately, NOT the reporter's `"development"` fallback: a dump says what it could
        // read. "Couldn't tell" and "development" are different answers, and collapsing them here would
        // make a dump from a build with no readable deployment claim an environment it never had.
        reporterEnvironment = deploymentValue("sentryEnvironment") ?: "?",
    )
}

/** The hardware identifier — e.g. `iPhone12,8`. `"?"` when the call fails; never a guess. */
@OptIn(ExperimentalForeignApi::class)
private fun hardwareModel(): String = memScoped {
    val info = alloc<utsname>()
    if (uname(info.ptr) != 0) return@memScoped "?"
    info.machine.toKString().ifBlank { "?" }
}
