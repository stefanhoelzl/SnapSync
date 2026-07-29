package app.snapsync.logging

import app.snapsync.model.redactUuids
import app.snapsync.ports.CrashReporting
import co.touchlab.kermit.Logger
import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.kotlin.multiplatform.SentryEvent
import io.sentry.kotlin.multiplatform.protocol.Breadcrumb
import platform.Foundation.NSBundle

/**
 * The Sentry seat of the [CrashReporting] port (capability `crash-reporting`) — a NO-OP unless this
 * process's bundle carries a `SENTRY_DSN`. Only CI Release archives bake the DSN (capability
 * `ios-testflight-delivery`), so dev-sideload/simulator builds never start the SDK and never open a
 * connection to the reporting host. `SENTRY_ENVIRONMENT` is baked beside it (`production` in CI;
 * the `Config.xcconfig` default is `development`, so a deliberately DSN-injected dev build — the
 * on-device verification path — reports honestly).
 *
 * Idempotent across the whole process, not just this instance (the port contract): in the app
 * process both `snapSyncApp` and the app-driven tier's `uploadCore` start the port, and the roots
 * construct their adapters independently — a second [start] must not re-init the SDK or register a
 * duplicate writer (which would double every event). Composition runs on the main thread, so a
 * plain flag suffices.
 *
 * What leaves the device is bounded here, not at call sites: every outgoing message field is
 * scrubbed of UUID-shaped tokens ([redactUuids]), the SDK's failed-HTTP-request capture is off
 * (request URLs embed eventIds; the Kermit seam already logs those failures — scrubbed), and
 * `sendDefaultPii` is off. The SDK's random per-install `user.id` is the one deliberate exception
 * (spec: powers affected-device counts, linked to nothing) — do not scrub it. Bugsink ingests
 * errors only, so tracing stays unset and replay stays at its off default.
 *
 * **Which build and which process** an event came from is pinned here too, and the three fields do
 * NOT behave alike (decision record: `changes/archive/…-add-release-and-process-to-crash-reports`):
 * - `release` is set **explicitly** because the KMP layer assigns the native `releaseName`
 *   unconditionally from its own options, so leaving it unset *clears* the bundle-derived default
 *   the native SDK had already computed and the event ships with no release at all.
 * - The `process` tag is derived from **this process's own bundle** rather than passed in: an
 *   extension's main bundle is its `.appex`, so the two processes label themselves. The app process
 *   constructs this adapter at more than one site, and since [start] is idempotent process-wide a
 *   hand-supplied identity that disagreed between them would silently lose a coin toss.
 * - `dist` is **deliberately not set** — see the comment at that spot in [start].
 */
class SentryCrashReporting : CrashReporting {

    override fun start() {
        if (processStarted) return
        val dsn = bundleValue("SENTRY_DSN") ?: return
        processStarted = true
        Sentry.init { options ->
            options.dsn = dsn
            options.environment = bundleValue("SENTRY_ENVIRONMENT") ?: "development"
            // The version line this build carries. Set only when present and non-blank: an
            // empty-string release is worse than none, because it creates a release record that
            // looks real. The build number is NOT folded in — it rides as `dist` (below), which is
            // the SDK's own release/dist split.
            bundleValue("CFBundleShortVersionString")?.let { options.release = it }
            // ⚠️ `options.dist` is LEFT UNSET ON PURPOSE. It is not an oversight and it is not
            // symmetrical with `release` above: the native SDK applies the release option only when
            // the event has none, but applies the dist option UNCONDITIONALLY at send time. A crash
            // is cached and delivered on a LATER launch — possibly after the device updated — so a
            // dist we set would overwrite the build number the crash report recorded when it
            // actually crashed. Because dSYMs are resolved as `dsyms-<dist>`, that would silently
            // symbolicate a crash against a DIFFERENT build's symbols: plausible frames, wrong
            // answers, nothing signalling the mismatch. Leaving it unset is what keeps the value
            // crash-time accurate (the SDK falls back to the report's own recorded build number).
            options.sendDefaultPii = false
            options.enableCaptureFailedRequests = false
            options.beforeBreadcrumb = { crumb -> scrubbedBreadcrumb(crumb) }
            options.beforeSend = { event -> scrubbedEvent(event) }
        }
        // Which of the two processes is reporting. Set on the global scope, which the native SDK
        // persists into fatal events, so it survives a crash rather than only labelling handled
        // ones. The value is the raw bundle id, so the tag claims nothing beyond what it read.
        NSBundle.mainBundle.bundleIdentifier?.takeIf { it.isNotBlank() }?.let { bundleId ->
            Sentry.configureScope { scope -> scope.setTag("process", bundleId) }
        }
        // Appended, not set: the shell's Logger.setLogWriters(...) already ran, and this writer
        // exists only in a DSN-carrying process.
        Logger.addLogWriter(SentryLogWriter())
    }
}

private var processStarted = false

private fun bundleValue(key: String): String? =
    (NSBundle.mainBundle.objectForInfoDictionaryKey(key) as? String)?.takeIf { it.isNotBlank() }

/** Covers the SDK's automatic breadcrumbs too — ours arrive pre-scrubbed from [SentryLogWriter]. */
internal fun scrubbedBreadcrumb(crumb: Breadcrumb): Breadcrumb {
    crumb.message = crumb.message?.let(::redactUuids)
    crumb.getData()?.forEach { (key, value) ->
        if (value is String) crumb.setData(key, redactUuids(value))
    }
    return crumb
}

/** The last gate before transmission: message, exception values, and attached breadcrumbs. */
internal fun scrubbedEvent(event: SentryEvent): SentryEvent {
    event.message = event.message?.let { m ->
        m.copy(
            message = m.message?.let(::redactUuids),
            formatted = m.formatted?.let(::redactUuids),
            params = m.params?.map(::redactUuids),
        )
    }
    event.exceptions = event.exceptions
        .map { it.copy(value = it.value?.let(::redactUuids)) }
        .toMutableList()
    event.breadcrumbs.forEach { scrubbedBreadcrumb(it) }
    return event
}
