package app.snapsync.model

import co.touchlab.kermit.Severity

/**
 * The crash-reporting channel's vocabulary (capability `privacy-security`), in the core's own words: what the
 * `CrashReporter` port carries in both directions, so no zone above the adapter names the reporting SDK.
 *
 * Every rule over these values — what is redacted, what is capped, which event is exempt, what a log line
 * becomes — lives in this file as a pure function, and the services wire them as the port's handlers. The adapter
 * only translates between these values and the SDK's own.
 */

/** A breadcrumb's or an event's level, the four the channel distinguishes. */
enum class CrashLevel { DEBUG, INFO, WARNING, ERROR }

/**
 * One breadcrumb: a line of context that rides the NEXT event, never sent on its own.
 *
 * [data] is the breadcrumb's STRING data only. The SDK's automatic breadcrumbs also carry numbers (an HTTP status
 * code); those are not text, no rule reads them, and the adapter leaves them where they are.
 */
data class Crumb(
    val level: CrashLevel,
    val message: String?,
    val category: String? = null,
    val data: Map<String, String> = emptyMap(),
)

/**
 * One event, as the channel sends it or as it is about to leave.
 *
 * Outbound (`CrashReporter.capture`, `sendDump`): a [message] or a [throwable], [tags] and [contexts].
 * Leaving (`CrashHandlers.onEvent`): the adapter fills in what the event carries by then — the SDK's [formatted]
 * rendering, [params], the [exceptionValues] (one per exception, in order) and the attached [breadcrumbs] — and
 * writes back what the handler returns. A captured message's text arrives there as [formatted] (measured on the
 * Sentry SDK: the contract's shaping clauses failed while they read [message] alone), so a handler reads and
 * shapes both. A handler reshapes those fields; it does not add or remove exceptions or
 * breadcrumbs, and the adapter writes them back positionally.
 */
data class CrashEvent(
    val message: String? = null,
    val formatted: String? = null,
    val params: List<String>? = null,
    val throwable: Throwable? = null,
    val exceptionValues: List<String?> = emptyList(),
    val breadcrumbs: List<Crumb> = emptyList(),
    val tags: Map<String, String> = emptyMap(),
    val contexts: Map<String, Map<String, String>> = emptyMap(),
)

/**
 * How the channel starts: where it reports and how many breadcrumbs an event carries. The build facts the adapter
 * reads from its own bundle (release, environment, which process) are not here — they are the platform's.
 */
class CrashOptions(
    val dsn: String,
    /** Pinned, not defaulted: it is a row of the whole-event sum on [DIAGNOSTIC_LOG_BUDGET_BYTES]. */
    val maxBreadcrumbs: Int = MAX_BREADCRUMBS,
)

/** What became of an operator-initiated dump. Delivery itself is the channel's business, and never claimed. */
sealed interface DumpResult {
    /** Handed to the channel, which queues and retransmits it. Returning does NOT mean it left the device. */
    data object Queued : DumpResult

    /** Nothing was sent, and [reason] says why (the channel is not running, or the build reports nowhere). */
    data class NotSent(val reason: String) : DumpResult
}

/** The context section name process metrics ride in, so a reader always finds them in one place. */
const val PROCESS_METRIC_CONTEXT: String = "process_metrics"

/**
 * The marker every operator-initiated report's message begins with, ahead of what the operator wrote.
 *
 * Grouping keys off the message (measured 2026-07-29 — the probe landed as `Log Message: '…'`), so the prefix does
 * NOT collapse reports into one issue; the description after it is what separates them. What the prefix buys is
 * that a report is recognisable as a report in a list it shares with real crashes — and greppable by the
 * `/bugsink` triage skill, whose "this is not a crash" rule keys on it.
 */
const val DIAGNOSTIC_DUMP_MESSAGE_PREFIX: String = "Bug Report:"

/**
 * The operator-initiated dump as ONE event, titled by **what the operator wrote**, behind [DIAGNOSTIC_DUMP_MESSAGE_PREFIX],
 * carrying the five sections as **contexts**.
 *
 * The message is the grouping key — Bugsink titles a non-exception issue from the first line of the log message —
 * so reports group **by description**: two reports about the same problem in the same words collapse, two about
 * different problems stay apart.
 *
 * Contexts, not an attachment and not breadcrumbs, both for measured reasons (2026-07-29, against the real
 * instance): the server drops the `attachment` envelope item entirely, while breadcrumbs are capped at ~100 by the
 * SDK, some 2% of the budget. Context strings came back **byte-identical** at 340 KB each.
 *
 * The dump is NOT scrubbed, and it says so **on the event**: [NON_REDACTED_TAG] is the narrow, deliberate
 * carve-out from the channel's UUID redaction (capability `privacy-security`). A dump is confirmed by the operator
 * and worthless without its ids — including ids the operator quoted in the description. Drop the tag and every
 * future report arrives mangled, with no failing request; `CrashScrubTest` pins both halves.
 */
fun diagnosticDumpEvent(dump: DiagnosticDump): CrashEvent = CrashEvent(
    message = "$DIAGNOSTIC_DUMP_MESSAGE_PREFIX ${dump.note}",
    tags = mapOf(NON_REDACTED_TAG to "1"),
    contexts = mapOf(
        "note" to mapOf("text" to dump.note),
        "state" to dump.state,
        "ledger" to dump.ledger,
        "app_log" to mapOf("text" to dump.appLog),
        "ext_log" to mapOf("text" to dump.extensionLog),
    ),
)

/**
 * A breadcrumb as it may leave: redacted, then its message and string data values bounded **together** by
 * [BREADCRUMB_TEXT_BYTES], message first. This is the breadcrumb row of the whole-event sum (see [MAX_EVENT_BYTES]).
 * The cap comes after the redaction, so it measures what is actually sent. The device log keeps the line in full.
 *
 * It covers the SDK's automatic breadcrumbs too; ours arrive pre-redacted from [loggedCrash].
 */
fun scrubbedCrumb(crumb: Crumb): Crumb {
    val message = crumb.message?.let(::redactUuids)
    val data = crumb.data.mapValues { (_, value) -> redactUuids(value) }
    val capped = capAllUtf8(listOfNotNull(message) + data.values, BREADCRUMB_TEXT_BYTES)
    val cappedData = if (message != null) capped.drop(1) else capped
    return crumb.copy(
        message = if (message != null) capped.first() else null,
        data = data.keys.zip(cappedData).toMap(),
    )
}

/**
 * The last gate before transmission: message, exception values, and attached breadcrumbs.
 *
 * An event that declares itself exempt ([NON_REDACTED_TAG], set only by [diagnosticDumpEvent]) passes through
 * untouched. The exemption is read off the event on purpose: a widened scrub is then safe — an exempt event is
 * skipped whatever this covers. The check comes FIRST, before any redaction, or an exempt event would already be
 * mangled by the time it is recognised.
 *
 * Redacted, then capped at [EVENT_TEXT_BYTES]: not a tight budget (automatic events carry no log tails), but one
 * runaway string must not make an event the ingest refuses, which would block the queue behind it.
 */
fun scrubbedEvent(event: CrashEvent): CrashEvent {
    if (!redactsMessages(event.tags)) return event
    val bounded = { text: String -> capUtf8(redactUuids(text), EVENT_TEXT_BYTES) }
    return event.copy(
        message = event.message?.let(bounded),
        formatted = event.formatted?.let(bounded),
        params = event.params?.map(::redactUuids),
        exceptionValues = event.exceptionValues.map { it?.let(bounded) },
        breadcrumbs = event.breadcrumbs.map(::scrubbedCrumb),
    )
}

/** What one log line becomes on the channel: always a breadcrumb, and an event only at `Error`/`Assert`. */
class LoggedCrash(val crumb: Crumb, val event: CrashEvent?)

/**
 * The logging seam's mapping onto the channel (capability `privacy-security`): every error a feature already
 * reduces into state and logs is reported without per-call-site instrumentation.
 *
 * - `Error`/`Assert` become **events** (with the throwable when present). A breadcrumb attached to no event is
 *   never sent anywhere, so a mapping that quietly demoted `Error` would leave the operator's instance looking
 *   healthy while the fleet failed — the silence this capability exists to break.
 * - The line also travels as an error breadcrumb, so the event keeps the line that explains it — WITH its
 *   `[entryPoint]` prefix.
 * - The event itself carries the **bare** redacted message, and the entry point rides as the `entry_point` tag: the
 *   backend groups by message text, and a prefixed message split one cause into one issue per entry point
 *   (`SNAPSYNC-27/28/29/30`). One rule on both capture paths; the exception path groups by stacktrace regardless.
 * - Everything below `Error` is a breadcrumb only.
 *
 * Messages are redacted here, and again by [scrubbedEvent]/[scrubbedCrumb], which also cover the throwable's own
 * message and the SDK's automatic breadcrumbs: no UUID-shaped token ever leaves the device.
 */
fun loggedCrash(severity: Severity, message: String, tag: String, throwable: Throwable?, entry: String?): LoggedCrash {
    val text = redactUuids(if (entry != null) "[$entry] $message" else message)
    val level = severity.crashLevel()
    val tags = if (entry != null) mapOf("entry_point" to entry) else emptyMap()
    val event = when (level) {
        CrashLevel.ERROR ->
            if (throwable != null) CrashEvent(throwable = throwable, tags = tags)
            else CrashEvent(message = redactUuids(message), tags = tags)
        else -> null
    }
    return LoggedCrash(Crumb(level = level, message = text, category = tag), event)
}

/** Kermit severity → the channel's level, the whole mapping in one place. */
fun Severity.crashLevel(): CrashLevel = when (this) {
    Severity.Verbose, Severity.Debug -> CrashLevel.DEBUG
    Severity.Info -> CrashLevel.INFO
    Severity.Warn -> CrashLevel.WARNING
    Severity.Error, Severity.Assert -> CrashLevel.ERROR
}
