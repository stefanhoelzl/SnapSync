package app.snapsync.config

import platform.Foundation.NSBundle

/**
 * The compile-time device-facing backend base this build targets — `BackgroundUploadURLBase` in the
 * process's own `Info.plist`, fed by the one `BACKGROUND_UPLOAD_URL_BASE` xcconfig setting that
 * reaches both targets.
 *
 * PhotoKit validates every upload job's destination against the extension's baked value, so a
 * user-configurable host is impossible by design; this is the authoritative source of the edge URL's
 * host, combined at each composition root with the runtime event id.
 *
 * Consolidated here beside [app.snapsync.logging.appBuildVersion] for the same two reasons: it is read
 * by **both** processes (each `NSBundle.mainBundle` being its own bundle), and neither wiring-only
 * composition root may carry the absent-key defaulting decision — `:app:*` Kotlin is gated to zero
 * decisions, and a named function holding this one fails that gate.
 *
 * Blank when the key is missing, which is a build misconfiguration rather than a state to handle:
 * `buildUploadConfig` treats a blank host exactly as it treats an absent one — nothing to do — so a
 * misconfigured build uploads nowhere rather than somewhere unintended.
 */
fun bakedUploadBase(): String =
    NSBundle.mainBundle.objectForInfoDictionaryKey("BackgroundUploadURLBase") as? String ?: ""
