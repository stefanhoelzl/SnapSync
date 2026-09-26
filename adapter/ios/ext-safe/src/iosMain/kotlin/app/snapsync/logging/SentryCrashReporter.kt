package app.snapsync.logging

import app.snapsync.config.bakedSentryEnvironment
import app.snapsync.model.CrashEvent
import app.snapsync.model.CrashLevel
import app.snapsync.model.CrashOptions
import app.snapsync.model.Crumb
import app.snapsync.model.DumpResult
import app.snapsync.ports.CrashHandlers
import app.snapsync.ports.CrashReporter
import io.sentry.kotlin.multiplatform.Scope
import io.sentry.kotlin.multiplatform.Sentry
import io.sentry.kotlin.multiplatform.SentryEvent
import io.sentry.kotlin.multiplatform.SentryLevel
import io.sentry.kotlin.multiplatform.protocol.Breadcrumb
import platform.Foundation.NSBundle

/**
 * The Sentry seat of the [CrashReporter] port (capability `privacy-security`): a translation between the core's
 * crash vocabulary (`model/Crash.kt`) and the Sentry KMP SDK's, and nothing more.
 *
 * **It decides nothing.** Whether this build reports (the DSN), what is redacted and capped, which event is exempt,
 * what a log line becomes — all of that is `:domain:services`' `CrashReporting`, registered here as the port's
 * [CrashHandlers] and run from the SDK's `beforeSend`/`beforeBreadcrumb`. What stays here is what only the SDK's
 * seat can know:
 *
 * - **`release` is set explicitly**, because the KMP layer assigns the native `releaseName` unconditionally from
 *   its own options: leaving it unset *clears* the bundle-derived default and the event ships with no release.
 * - **The `process` tag is this process's own bundle id**, set on the global scope, which the native SDK persists
 *   into fatal events — an extension's main bundle is its `.appex`, so the two processes label themselves.
 * - **`dist` is deliberately not set** — see the comment at that spot in [start].
 * - The SDK's failed-HTTP-request capture is off (request URLs embed eventIds; the logging seam already reports
 *   those failures, scrubbed) and `sendDefaultPii` is off. The SDK's random per-install `user.id` is the one
 *   deliberate identifier (spec: powers affected-device counts, linked to nothing) — do not scrub it. Bugsink
 *   ingests errors only, so tracing stays unset and replay stays at its off default.
 *
 * **Idempotent across the whole process**, not just this instance: the SDK hub is process-global, and a second
 * init would reset its scope. One instance per process is the composition's rule (`snapSyncProcess`); the flag
 * keeps the port's contract true regardless.
 *
 * **Nothing unshaped leaves.** An event or breadcrumb that reaches the SDK's hooks before [listen] registered the
 * handlers is dropped rather than sent as it is.
 *
 * **What is asserted, and what is only believed.** `CrashReporterContract` (`:test:contracts`) runs this class,
 * over the real SDK, on the simulator test executable, against a loopback ingest. Two claims have no host there and
 * stay beliefs, with their evidence:
 * - the global-scope tag and context ride a crash delivered on a LATER launch — a reading of sentry-cocoa's source
 *   (`changes/archive/2026-07-29-add-release-and-process-to-crash-reports`), not a measurement;
 * - the `process` tag itself — the test executable has no bundle identifier (measured 2026-09-23), so there [start]
 *   sets none.
 */
class SentryCrashReporter : CrashReporter {

    /** Where events and breadcrumbs are shaped on their way out — written once, by [listen]. */
    private var handlers: CrashHandlers? = null

    override fun listen(handlers: CrashHandlers) {
        this.handlers = handlers
    }

    override fun start(options: CrashOptions) {
        if (processStarted) return
        processStarted = true
        Sentry.init { sdk ->
            sdk.dsn = options.dsn
            sdk.environment = bakedSentryEnvironment()
            // The version line this build carries. Set only when present and non-blank: an empty-string release is
            // worse than none, because it creates a release record that looks real. The build number is NOT folded
            // in — it rides as `dist` (below), which is the SDK's own release/dist split.
            bundleValue("CFBundleShortVersionString")?.let { sdk.release = it }
            // ⚠️ `dist` is LEFT UNSET ON PURPOSE. It is not symmetrical with `release` above: the native SDK applies
            // the release option only when the event has none, but applies the dist option UNCONDITIONALLY at send
            // time. A crash is cached and delivered on a LATER launch — possibly after the device updated — so a dist
            // we set would overwrite the build number the crash report recorded when it actually crashed. Because
            // dSYMs are resolved as `dsyms-<dist>`, that would silently symbolicate a crash against a DIFFERENT
            // build's symbols. Leaving it unset keeps the value crash-time accurate.
            sdk.sendDefaultPii = false
            sdk.enableCaptureFailedRequests = false
            sdk.maxBreadcrumbs = options.maxBreadcrumbs
            sdk.beforeBreadcrumb = { crumb -> handlers?.onBreadcrumb?.invoke(crumb.toCrumb())?.let(crumb::shapedAs) }
            sdk.beforeSend = { event -> handlers?.onEvent?.invoke(event.toCrashEvent())?.let(event::shapedAs) }
        }
        // Which of the two processes is reporting, on the global scope the native SDK persists into fatal events.
        // The value is the raw bundle id, so the tag claims nothing beyond what it read.
        NSBundle.mainBundle.bundleIdentifier?.takeIf { it.isNotBlank() }?.let { bundleId ->
            Sentry.configureScope { scope -> scope.setTag("process", bundleId) }
        }
    }

    override fun capture(event: CrashEvent) {
        if (!processStarted) return
        val throwable = event.throwable
        if (throwable != null) Sentry.captureException(throwable) { scope -> scope.carry(event) }
        else Sentry.captureMessage(event.message.orEmpty()) { scope -> scope.carry(event) }
    }

    override fun breadcrumb(crumb: Crumb) {
        if (!processStarted) return
        Sentry.addBreadcrumb(Breadcrumb(level = crumb.level.toSentryLevel(), message = crumb.message, category = crumb.category).also { b ->
            crumb.data.forEach { (key, value) -> b.setData(key, value) }
        })
    }

    /**
     * On the **global** scope, which the native SDK persists into fatal events: a crash captured in this process
     * and delivered on a later launch arrives carrying it. `setContext` REPLACES the named context.
     */
    override fun setContext(name: String, fields: Map<String, String>) {
        if (!processStarted) return
        Sentry.configureScope { scope -> scope.setContext(name, fields) }
    }

    /**
     * One event, its tags and contexts on the capture's own scope — so they ride this event only. The tags reach
     * `beforeSend` before it runs (measured: `ScrubExemptionSdkTest`), which is what lets the handler read the
     * dump's exemption off the event.
     */
    override suspend fun sendDump(dump: CrashEvent): DumpResult {
        if (!processStarted) return DumpResult.NotSent("the channel is not running")
        Sentry.captureMessage(dump.message.orEmpty()) { scope -> scope.carry(dump) }
        return DumpResult.Queued
    }
}

private var processStarted = false

/**
 * Forgets that this process started the channel — for the `CrashReporter` contract's live binding ONLY, which gives
 * every clause a fresh instance of an adapter whose idempotence is process-wide by contract. It closes the SDK and
 * wipes its envelope cache itself; this is the one piece of that state only this file can reach. No composition calls
 * it, and none can: it is `internal` to this module.
 */
internal fun resetProcessStart() {
    processStarted = false
}

private fun Scope.carry(event: CrashEvent) {
    event.tags.forEach { (key, value) -> setTag(key, value) }
    event.contexts.forEach { (name, fields) -> setContext(name, fields) }
}

/** Apple's OWN Info.plist keys only. Deployment values come from `bakedSentryEnvironment` and friends. */
private fun bundleValue(key: String): String? =
    (NSBundle.mainBundle.objectForInfoDictionaryKey(key) as? String)?.takeIf { it.isNotBlank() }

// ---- the translation, both directions: the one place that knows the SDK's event shape ----------------------------

internal fun SentryEvent.toCrashEvent(): CrashEvent = CrashEvent(
    message = message?.message,
    formatted = message?.formatted,
    params = message?.params,
    exceptionValues = exceptions.map { it.value },
    breadcrumbs = breadcrumbs.map { it.toCrumb() },
    tags = tags.toMap(),
)

/** Write back what the handler shaped. Exceptions and breadcrumbs are matched by position (see [CrashEvent]). */
internal fun SentryEvent.shapedAs(shaped: CrashEvent): SentryEvent {
    message = message?.copy(message = shaped.message, formatted = shaped.formatted, params = shaped.params)
    exceptions = exceptions.zip(shaped.exceptionValues) { e, value -> e.copy(value = value) }.toMutableList()
    breadcrumbs.zip(shaped.breadcrumbs).forEach { (crumb, into) -> crumb.shapedAs(into) }
    return this
}

internal fun Breadcrumb.toCrumb(): Crumb = Crumb(
    level = level.toCrashLevel(),
    message = message,
    category = category,
    // String data only: an HTTP status code is a number, no rule reads it, and it stays where it is.
    data = getData().orEmpty().mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap(),
)

internal fun Breadcrumb.shapedAs(shaped: Crumb): Breadcrumb {
    message = shaped.message
    shaped.data.forEach { (key, value) -> setData(key, value) }
    return this
}

private fun SentryLevel?.toCrashLevel(): CrashLevel = when (this) {
    SentryLevel.DEBUG, null -> CrashLevel.DEBUG
    SentryLevel.INFO -> CrashLevel.INFO
    SentryLevel.WARNING -> CrashLevel.WARNING
    SentryLevel.ERROR, SentryLevel.FATAL -> CrashLevel.ERROR
}

private fun CrashLevel.toSentryLevel(): SentryLevel = when (this) {
    CrashLevel.DEBUG -> SentryLevel.DEBUG
    CrashLevel.INFO -> SentryLevel.INFO
    CrashLevel.WARNING -> SentryLevel.WARNING
    CrashLevel.ERROR -> SentryLevel.ERROR
}
