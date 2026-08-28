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

/**
 * The process's **marketing version** alone — the value every versioned backend request declares
 * (capability `min-app-version`).
 *
 * The marketing version and not the build number, deliberately: every build between two releases shares
 * one marketing version, and it is the only version a user can act on. The gate exists so a screen can
 * say "update to 0.12"; a build number cannot carry that sentence.
 *
 * Seated here beside [appBuildVersion] for the same two reasons: **both processes** read it (each
 * `NSBundle.mainBundle` being its own bundle, and the extension declares the version on the byte upload
 * the OS performs), and the absent-key decision is a **decision**, which the zero-decision shell gate
 * forbids a wiring-only root to hold.
 *
 * Blank when the key is missing. That is a build misconfiguration rather than a state to handle: the
 * gate refuses an absent and an unparseable declaration identically, so a misconfigured build is told to
 * update rather than being quietly served.
 */
fun appMarketingVersion(): String =
    NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: ""
