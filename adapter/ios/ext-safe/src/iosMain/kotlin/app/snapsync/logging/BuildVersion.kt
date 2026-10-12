package app.snapsync.logging

import platform.Foundation.NSBundle

/**
 * The process's **marketing version** alone — the value every versioned backend request declares.
 *
 * The marketing version and not the build number, deliberately: every build between two releases shares
 * one marketing version, and it is the only version a user can act on. The gate exists so a screen can
 * say "update to 0.12"; a build number cannot carry that sentence.
 *
 * Seated here for two reasons: **both processes** read it (each
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
