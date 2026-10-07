package app.snapsync.sentry

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

/**
 * The Sentry seat of the [CrashReporter] port on BOTH platforms (capability `privacy-security`): a translation between
 * the core's crash vocabulary (`model/Crash.kt`) and the Sentry KMP SDK's, and nothing more. One copy, because this is
 * where "nothing unshaped leaves" is enforced.
 *
 * **It decides nothing, and reads nothing of the platform.** Whether this build reports (the DSN), what the build is
 * (release, environment, build number, platform, process), what is redacted and capped, which event is exempt, what a
 * log line becomes — all of that is `:domain:services`' `CrashReporting`, handed in as [CrashOptions] and registered
 * as the port's [CrashHandlers], run from the SDK's `beforeSend`/`beforeBreadcrumb`. What stays here is what only the
 * SDK's seat can know:
 *
 * - **`release` is set explicitly**, because the KMP layer assigns the native release unconditionally from its own
 *   options: leaving it unset *clears* the platform SDK's default and the event ships with no release.
 * - **`dist` is set only when the options carry one** — `null` on iOS on purpose (`model/`'s `crashDist`).
 * - **The options' tags go on the global scope**, which both native SDKs persist into fatal events, so a crash
 *   delivered on a later launch carries the process that crashed.
 * - Off: the SDK's failed-HTTP-request capture (request URLs embed eventIds; the logging seam already reports those
 *   failures, scrubbed), default PII, and on Android screenshot and view-hierarchy attachments. The tap and gesture
 *   breadcrumbs and the SDK's own auto-start are off in this module's Android manifest, which sentry-android reads at
 *   init. ANR reporting stays on: an app frozen until the system closes it is reported (capability
 *   `privacy-security`). The SDK's random per-install `user.id` is the one deliberate identifier (spec: powers
 *   affected-device counts, linked to nothing) — do not scrub it. Release-health sessions are off (a per-launch
 *   usage record). Bugsink ingests errors only, so tracing stays unset and replay stays at its off default.
 *
 * **Idempotent across the whole process**, not just this instance: the SDK hub is process-global, and a second
 * init would reset its scope. One instance per process is the composition's rule (`snapSyncProcess`); the flag
 * keeps the port's contract true regardless.
 *
 * **Nothing unshaped leaves.** An event or breadcrumb that reaches the SDK's hooks before [listen] registered the
 * handlers is dropped rather than sent as it is.
 *
 * **What is asserted, and what is only believed.** `CrashReporterContract` (`:test:contracts`) runs this class, over
 * the real SDK, against a loopback ingest, on the iOS simulator test executable and on the Android emulator. One claim
 * has no host and stays a belief: that the global-scope tags ride a crash delivered on a LATER launch after a real
 * process death — a reading of sentry-cocoa's source (`changes/archive/2026-07-29-add-release-and-process-to-crash-reports`)
 * on iOS, and on Android the cache-and-restart clause `RESTART_CACHED_EVENT_KEEPS_ITS_BUILD` rather than a crash.
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
            options.environment?.let { sdk.environment = it }
            // Only when present: an empty-string release is worse than none, because it creates a release record that
            // looks real. The build number is NOT folded in — it rides as `dist`, the SDK's own release/dist split.
            options.release?.let { sdk.release = it }
            // ⚠️ Only when the options carry one. sentry-cocoa applies the dist option UNCONDITIONALLY at send time, and a
            // crash is delivered on a LATER launch — possibly after the device updated — so on iOS a dist set here would
            // overwrite the build number the crash report recorded when it actually crashed. `crashDist` decides.
            options.dist?.let { sdk.dist = it }
            sdk.sendDefaultPii = false
            // No release-health sessions: one per launch, sent whether or not anything failed — a record of how the
            // app is used, which automatic reports must not carry (capability `privacy-security`). Bugsink drops them
            // anyway; off, they are never created.
            sdk.enableAutoSessionTracking = false
            sdk.enableCaptureFailedRequests = false
            sdk.attachScreenshot = false
            sdk.attachViewHierarchy = false
            sdk.isAnrEnabled = true
            sdk.maxBreadcrumbs = options.maxBreadcrumbs
            sdk.beforeBreadcrumb = { crumb -> handlers?.onBreadcrumb?.invoke(crumb.toCrumb())?.let(crumb::shapedAs) }
            sdk.beforeSend = { event -> handlers?.onEvent?.invoke(event.toCrashEvent())?.let(event::shapedAs) }
        }
        // Which platform and process is reporting, on the global scope the native SDKs persist into fatal events.
        if (options.tags.isNotEmpty()) {
            Sentry.configureScope { scope -> options.tags.forEach { (key, value) -> scope.setTag(key, value) } }
        }
    }

    override fun capture(event: CrashEvent) {
        if (!processStarted) return
        val throwable = event.throwable
        if (throwable != null) {
            Sentry.captureException(throwable) { scope -> scope.carry(event) }
        } else {
            Sentry.captureMessage(event.message.orEmpty()) { scope -> scope.carry(event) }
        }
    }

    override fun breadcrumb(crumb: Crumb) {
        if (!processStarted) return
        Sentry.addBreadcrumb(
            Breadcrumb(
                level = crumb.level.toSentryLevel(),
                message = crumb.message,
                category = crumb.category,
            ).also { b ->
                crumb.data.forEach { (key, value) -> b.setData(key, value) }
            },
        )
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
