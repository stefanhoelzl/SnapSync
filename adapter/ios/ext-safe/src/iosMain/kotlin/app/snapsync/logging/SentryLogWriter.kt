package app.snapsync.logging

import app.snapsync.model.redactUuids
import co.touchlab.kermit.LogWriter
import co.touchlab.kermit.Severity
import io.sentry.kotlin.multiplatform.Scope
import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.kotlin.multiplatform.SentryLevel
import io.sentry.kotlin.multiplatform.protocol.Breadcrumb

/**
 * The crash-reporting capture seam (capability `crash-reporting`, design D2): one Kermit writer maps
 * the existing logging surface onto the Sentry channel, so every error a feature already reduces
 * into state and logs is reported without per-call-site instrumentation. `Error`/`Assert` become
 * events (with the throwable when present); everything below rides along as breadcrumbs.
 *
 * Messages are redacted here (and again in `beforeSend`/`beforeBreadcrumb`, which also cover the
 * throwable's own message and the SDK's automatic breadcrumbs): no UUID-shaped token — eventId,
 * device id, membership id — ever leaves the device. Registered only by [SentryDiagnosticsReporter]'s
 * `start()`, so a build without a DSN never constructs it; Sentry calls before/without init are
 * safe no-ops in the underlying SDK either way.
 */
class SentryLogWriter : LogWriter() {

    override fun log(severity: Severity, message: String, tag: String, throwable: Throwable?) {
        val ctx = LogContext.current
        val text = redactUuids(if (ctx != null) "[$ctx] $message" else message)
        when (severity) {
            Severity.Error, Severity.Assert -> {
                // The message travels as an error breadcrumb even when a throwable is captured,
                // so the event keeps the log line that explains it — WITH its `[entryPoint]` prefix.
                Sentry.addBreadcrumb(Breadcrumb(level = SentryLevel.ERROR, message = text, category = tag))
                // The prefix is context about WHICH TRIGGER was running, not about what went wrong — and
                // the backend groups by message text, so leaving it in the message splits one cause into
                // one issue per entry point. It did: a single wrong `Error` in the upload cycle arrived as
                // four issues (`SNAPSYNC-27/28/29/30`), one each for `upload.didComplete`,
                // `pump.onUploadCompleted`, `pump.onSessionEvents` and `url-session.onForeground`. So the
                // event carries the bare redacted message and the entry point rides as a scope-local tag,
                // on BOTH capture paths — one rule is easier to keep true than two, and it costs nothing
                // on the exception path, which groups by stacktrace regardless.
                val withEntryPoint: (Scope) -> Unit = { scope ->
                    if (ctx != null) scope.setTag("entry_point", ctx)
                }
                if (throwable != null) Sentry.captureException(throwable, withEntryPoint)
                else Sentry.captureMessage(redactUuids(message), withEntryPoint)
            }
            else -> Sentry.addBreadcrumb(
                Breadcrumb(level = severity.toSentryLevel(), message = text, category = tag),
            )
        }
    }
}

/**
 * Kermit severity → Sentry level, the whole mapping in one place.
 *
 * Top-level and `internal` so it can be asserted directly: the split above it — `Error`/`Assert`
 * become *events*, everything else a *breadcrumb* — decides whether a failure is reported at all, and
 * a breadcrumb attached to no event is never sent anywhere. A mapping that quietly demoted `Error`
 * would leave the operator's instance looking healthy while the fleet failed, which is precisely the
 * silence this capability exists to break.
 */
internal fun Severity.toSentryLevel(): SentryLevel = when (this) {
    Severity.Verbose, Severity.Debug -> SentryLevel.DEBUG
    Severity.Info -> SentryLevel.INFO
    Severity.Warn -> SentryLevel.WARNING
    Severity.Error, Severity.Assert -> SentryLevel.ERROR
}
