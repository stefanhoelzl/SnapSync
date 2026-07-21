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
 */
class SentryCrashReporting : CrashReporting {

    override fun start() {
        if (processStarted) return
        val dsn = bundleValue("SENTRY_DSN") ?: return
        processStarted = true
        Sentry.init { options ->
            options.dsn = dsn
            options.environment = bundleValue("SENTRY_ENVIRONMENT") ?: "development"
            options.sendDefaultPii = false
            options.enableCaptureFailedRequests = false
            options.beforeBreadcrumb = { crumb -> scrubbedBreadcrumb(crumb) }
            options.beforeSend = { event -> scrubbedEvent(event) }
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
