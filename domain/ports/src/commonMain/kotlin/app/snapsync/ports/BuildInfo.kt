package app.snapsync.ports

import app.snapsync.model.Platform
import app.snapsync.model.Store

/**
 * **What the running build is** — the constants its bundle carries, and the few facts of the running OS a process
 * reads once (`docs/architecture.md`, "Ports are the I/O boundary named for the need"). One per process: the app and
 * the upload extension each read their OWN bundle, so neither can bind the other's answer.
 *
 * A port rather than plain values handed to a composition, so every composition bundle holds ports and nothing else
 * ([Port]). Each member is read where it is used; none may change under a running process except [appVersion] off
 * device, where an operator plays a member updating the app in place.
 */
interface BuildInfo : Port {
    /** The build's marketing version, declared on every backend request and on the byte upload. */
    val appVersion: String

    /**
     * The backend's device-facing base the build uploads to, carrying exactly one version prefix. Blank when the build
     * carries none, which the upload cycle's gate treats as "cannot upload".
     */
    val uploadHost: String

    /**
     * This build's store page — the App Store on iOS, Google Play on Android — or `null` when it carries none: the one
     * remedy the update-required screen offers.
     */
    val store: Store?

    /** The build number the store and the dump name it by (`CFBundleVersion` on iOS, the version code on Android). */
    val buildNumber: String

    /**
     * The push environment this build's tokens belong to: the APNs environment on iOS (`sandbox` or `production`,
     * fixed at compile time), the Firebase project that issues them on Android.
     */
    val pushEnvironment: String

    /** The running OS's version, as the platform states it. */
    val osVersion: String

    /** The device's model, as the platform names it (`iPhone12,8`, `samsung SM-A405FN`). */
    val deviceModel: String

    /** The environment this build's crash reports are filed under (`production`, `development`), or `?` unread. */
    val reporterEnvironment: String

    /** Where this build reports crashes to, or `null` for a build that reports nowhere. */
    val dsn: String?

    /** Which platform the build is for — the crash channel's `platform` tag. */
    val platform: Platform

    /**
     * This process's own identifier as the platform names it — the bundle id on iOS (the app's, or the upload
     * extension's `.appex`), the package name on Android — or `null` where the process has none (the iOS simulator test
     * executable). The crash channel's `process` tag, which tells the app's reports from the extension's.
     */
    val processId: String?
}
