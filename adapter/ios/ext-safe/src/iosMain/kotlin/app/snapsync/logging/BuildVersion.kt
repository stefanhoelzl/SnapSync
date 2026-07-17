package app.snapsync.logging

import platform.Foundation.NSBundle

/**
 * The current process's short-version(build) — e.g. `0.1.0(214)` — for the boot banner each
 * composition root emits (capability `diagnostic-logging`, D5). Consolidated here beside the
 * device-log writers so both processes' banners format identically and neither wiring-only root
 * carries the absent-key defaulting decision; extension-safe (reads only the process's own
 * `NSBundle`).
 */
fun appBuildVersion(): String {
    val bundle = NSBundle.mainBundle
    val short = bundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: "?"
    val build = bundle.objectForInfoDictionaryKey("CFBundleVersion") as? String ?: "?"
    return "$short($build)"
}
