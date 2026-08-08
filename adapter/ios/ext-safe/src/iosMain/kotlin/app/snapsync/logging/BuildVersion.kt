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
    return formatBuildVersion(
        short = bundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String,
        build = bundle.objectForInfoDictionaryKey("CFBundleVersion") as? String,
    )
}

/**
 * The banner's version string, with the absent-key decision made **here** rather than at the two
 * `NSBundle` reads.
 *
 * `internal` because it is the only part of [appBuildVersion] a test can pin: the process's own
 * `NSBundle` is whatever the running binary happens to carry, so the read cannot be controlled, but
 * what an *absent* key becomes can — and it matters. A missing key must print `?`, never Kotlin's
 * `"null"`: a banner reading `null(null)` looks like a value that was read and found to be null,
 * where `?(?)` says the key was not there at all.
 */
internal fun formatBuildVersion(short: String?, build: String?): String =
    "${short ?: "?"}(${build ?: "?"})"
