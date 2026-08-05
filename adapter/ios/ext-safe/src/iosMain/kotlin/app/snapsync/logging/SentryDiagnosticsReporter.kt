package app.snapsync.logging

import app.snapsync.model.DiagnosticDump
import app.snapsync.model.NON_REDACTED_TAG
import app.snapsync.model.redactUuids
import app.snapsync.model.redactsMessages
import app.snapsync.ports.DiagnosticsReporter
import co.touchlab.kermit.Logger
import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.kotlin.multiplatform.SentryEvent
import io.sentry.kotlin.multiplatform.protocol.Breadcrumb
import platform.Foundation.NSBundle

/**
 * The Sentry seat of the [DiagnosticsReporter] port (capability `crash-reporting`) — a NO-OP unless this
 * process's bundle carries a `SENTRY_DSN`. Only CI Release archives bake the DSN (capability
 * `ios-testflight-delivery`), so dev-sideload/simulator builds never start the SDK and never open a
 * connection to the reporting host. `SENTRY_ENVIRONMENT` is baked beside it (`production` in CI;
 * the `Config.xcconfig` default is `development`, so a deliberately DSN-injected dev build — the
 * on-device verification path — reports honestly).
 *
 * Idempotent across the whole process, not just this instance (the port contract): in the app
 * process both `snapSyncApp` and the app-driven tier's `uploadCore` start the port, and the roots
 * construct their adapters independently — a second [start] must not re-init the SDK or register a
 * duplicate writer (which would double every event). Composition runs on the app's serial composition lane —
 * one thread, though no longer the main one (spec `module-architecture`) — so a plain flag
 * suffices.
 *
 * What leaves the device is bounded here, not at call sites: every outgoing message field is
 * scrubbed of UUID-shaped tokens ([redactUuids]) **unless the event declares itself exempt**
 * ([NON_REDACTED_TAG] — only the operator-initiated dump does), the SDK's failed-HTTP-request capture is off
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
class SentryDiagnosticsReporter : DiagnosticsReporter {

    override val isConfigured: Boolean get() = bundleValue("SENTRY_DSN") != null

    /**
     * The operator-initiated dump (capability `diagnostic-logging`): ONE event titled by **what the
     * operator wrote**, behind a fixed marker prefix, carrying the five sections as **contexts**.
     *
     * The message is the grouping key — Bugsink titles a non-exception issue from the first line of
     * the log message — so reports group **by description**: two reports about the same problem in
     * the same words collapse, two about different problems stay apart. That reverses the constant
     * message this shipped with, and deliberately: a constant was right while the payload was only a
     * log, because nothing distinguished two dumps; once a person writes what went wrong, collapsing
     * them hides the only fact that does. The prefix keeps every report identifiable as an operator
     * report rather than a thrown error sitting in the same unresolved list.
     *
     * Contexts, not an attachment and not breadcrumbs, both for measured reasons (2026-07-29, against
     * the real instance): the server drops the `attachment` envelope item entirely — the event would
     * arrive and the log would not — while breadcrumbs are capped at ~100 by the SDK, some 2% of the
     * budget. Context strings, by contrast, came back **byte-identical** at 340 KB each.
     *
     * The dump is NOT scrubbed, and it says so **on the event**: [NON_REDACTED_TAG] is the narrow,
     * deliberate carve-out from this channel's UUID redaction (capability `crash-reporting`). A dump
     * is confirmed by the operator and worthless without its ids — including ids the operator quoted
     * in the description, which now rides in the message the scrub would otherwise reach. Automatic
     * events, sent without anyone's knowledge, carry no tag and stay redacted. `DumpScrubExemptionTest`
     * pins BOTH halves: that this sets the tag, and that `scrubbedEvent` consults it.
     */
    override fun send(dump: DiagnosticDump) {
        if (!isConfigured) return
        Sentry.captureMessage("$DIAGNOSTIC_DUMP_MESSAGE_PREFIX ${dump.note}") { scope ->
            // The exemption, declared by the event itself rather than inferred from where the payload
            // sits. Drop this line and every future report arrives mangled — with no failing request.
            scope.setTag(NON_REDACTED_TAG, "1")
            scope.setContext("note", mapOf("text" to dump.note))
            scope.setContext("state", dump.state)
            scope.setContext("ledger", dump.ledger)
            scope.setContext("app_log", mapOf("text" to dump.appLog))
            scope.setContext("ext_log", mapOf("text" to dump.extensionLog))
        }
    }

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

/**
 * The marker every operator-initiated report's message begins with, ahead of what the operator wrote.
 *
 * Grouping keys off the message (measured 2026-07-29 — the probe landed as `Log Message: '…'`), so
 * the prefix does NOT collapse reports into one issue; the description after it is what separates
 * them. What the prefix buys is that a report is recognisable as a report in a list it shares with
 * real crashes — and greppable by the `/bugsink` triage skill, whose "this is not a crash" rule keys
 * on it.
 */
internal const val DIAGNOSTIC_DUMP_MESSAGE_PREFIX: String = "Bug Report:"

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

/**
 * The last gate before transmission: message, exception values, and attached breadcrumbs.
 *
 * An event that declares itself exempt ([NON_REDACTED_TAG], set by [SentryDiagnosticsReporter.send])
 * passes through untouched. The exemption is read off the event on purpose: it used to hold only
 * because this function reached message text and not context sections, so a well-meaning "we missed a
 * field" change would have emptied every future dump with no failing test and no visible error. Now a
 * widened scrub is safe — an exempt event is skipped whatever this covers.
 */
internal fun scrubbedEvent(event: SentryEvent): SentryEvent {
    if (!redactsMessages(event.tags.orEmpty())) return event
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
