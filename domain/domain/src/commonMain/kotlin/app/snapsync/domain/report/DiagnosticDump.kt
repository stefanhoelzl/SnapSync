package app.snapsync.domain.report

import app.snapsync.domain.crash.CrashEvent

/**
 * One bug report the user confirmed: five labelled sections, sent as a single event ([event]) where the build reports
 * and kept on the device where it does not.
 *
 * Deliberately plain strings and string maps: the transport renders them as structured sections. Everything rides
 * **verbatim** — the user's description is theirs to share, and the report's value is precisely the event, photo and
 * device ids it carries.
 */
class DiagnosticDump(
    /**
     * What the user wrote: what went wrong, and what they were doing. Already trimmed and length-bounded by the sheet
     * that collected it. It is the one section a log tail can never supply, and the transport titles the report with
     * it — so two reports about different problems read as different problems.
     */
    val note: String,
    /** Build, OS, device, membership, tier, permission, backend — the facts a log tail may not hold. */
    val state: Map<String, String>,
    /** Upload and download counts. Five; no row lists (see `CollectDiagnosticDump`). */
    val ledger: Map<String, String>,
    /** The app process's log tail, line-aligned. Empty when unreadable. */
    val appLog: String,
    /** The extension process's log tail, line-aligned. Empty when the extension has never run. */
    val extensionLog: String,
) {
    /**
     * Bytes of **log** carried — what [LOG_BUDGET_BYTES] bounds. The [note] is deliberately excluded: it is bounded to
     * a couple of hundred bytes and counted in the whole-event sum's 4 KB row.
     */
    val logBytes: Int
        get() = appLog.encodeToByteArray().size + extensionLog.encodeToByteArray().size

    /** The sections by name, as the report carries them: sent as an event's contexts, or kept as one file. */
    fun sections(): Map<String, Map<String, String>> = mapOf(
        "note" to mapOf("text" to note),
        "state" to state,
        "ledger" to ledger,
        "app_log" to mapOf("text" to appLog),
        "ext_log" to mapOf("text" to extensionLog),
    )

    /**
     * The report as ONE event, titled by **what the user wrote**, behind [MESSAGE_PREFIX], carrying the sections as
     * **contexts**.
     *
     * The message is the grouping key — Bugsink titles a non-exception issue from the first line of the log message —
     * so reports group **by description**: two about the same problem in the same words collapse, two about different
     * problems stay apart.
     *
     * Contexts, not an attachment and not breadcrumbs, both for measured reasons (2026-07-29, against the real
     * instance): the server drops the `attachment` envelope item entirely, while breadcrumbs are capped at ~100 by the
     * SDK, some 2% of the budget. Context strings came back **byte-identical** at 340 KB each.
     */
    fun event(): CrashEvent = CrashEvent(message = "$MESSAGE_PREFIX $note", contexts = sections())

    companion object {
        /**
         * The **total** bytes of device log one report may carry.
         *
         * It is a hard bound, not a target. The reporting channel's server rejects an event over
         * `EventBounds.MAX_EVENT_BYTES` with a `413`, and the SDK surfaces no transport error, so the report completes
         * and tells the user nothing. The refused report is also not dropped: it stays queued and blocks every later
         * report from the app, across launches, until 30 newer envelopes evict it. The only defence is to stay clear of
         * the ceiling.
         *
         * The report rides in an event with other parts, so this budget is one row of a **whole-event sum**. Every
         * other row is capped too:
         *
         * | part | worst case |
         * |---|---|
         * | these log tails, plus JSON escaping (measured ~1% on log text) | ≈ 707,000 |
         * | note, state, ledger, message (fixed keys; the longest values a 100-char event name and the device facts¹) |
         * ≤ 4,000 |
         * | `MAX_BREADCRUMBS` breadcrumbs × (`BREADCRUMB_TEXT_BYTES`, up to 2× once JSON-escaped, + ~300 B each) | ≤
         * 133,000 |
         * | `process_metrics`, the `snapsync` section, tags, user, release | ≤ 8,000 |
         * | the SDK's own contexts, current-thread stack and debug images (an allowance, not a cap) | ≤ 100,000 |
         * | **total**, against 1,048,576 | **≈ 952,000** |
         *
         * ¹ each device fact at worst `failed (<reason>)`, the reason cut to [Fact.FAILURE_REASON_CHARS]; the worst
         * case is pinned by `CollectDiagnosticDumpTest`.
         *
         * The `CrashReporter` contract's worst-case clause sends this event to an ingest that refuses anything over the
         * ceiling, so an SDK upgrade that grows its share fails a clause rather than a device. Measured 2026-09-23
         * (sentry-cocoa 8.58.2, simulator): **903,912 B** decoded, with log tails deliberately escape-heavy (~10%
         * rather than the ~1% real log text shows) and every breadcrumb all-escapes.
         *
         * Raising this means raising `MAX_EVENT_SIZE` on the reporting server first, which the hosted plan does not
         * allow. The two move together or not at all.
         */
        const val LOG_BUDGET_BYTES: Int = 700_000

        /**
         * What every report's message begins with, ahead of what the user wrote: it makes a report recognisable as one
         * in a list it shares with real crashes — and greppable by the `/bugsink` triage skill, whose "this is not a
         * crash" rule keys on it. Grouping keys off the whole message, so it does not collapse reports into one issue.
         */
        const val MESSAGE_PREFIX: String = "Bug Report:"
    }
}

/** What became of a report. Delivery itself is the channel's business, and never claimed. */
sealed interface DumpResult {
    /** What the user is told became of their report: a hand-off, never a delivery. */
    val outcome: ReportOutcome

    /** Handed to the channel, which queues and retransmits it. Returning does NOT mean it left the device. */
    data object Queued : DumpHandOff {
        override val outcome: ReportOutcome get() = ReportOutcome.SENT
    }

    /** Kept on the device at [path] (in the app's own files), because this build reports nowhere. It never leaves. */
    data class Saved(val path: String) : DumpResult {
        override val outcome: ReportOutcome get() = ReportOutcome.SAVED
    }

    /** Nothing was sent or saved, and [reason] says why (the channel is not running, or the write was refused). */
    data class NotSent(val reason: String) : DumpHandOff {
        override val outcome: ReportOutcome get() = ReportOutcome.NOT_SENT
    }
}

/**
 * What the crash channel did with a report it was handed: [DumpResult.Queued] or [DumpResult.NotSent]. Keeping one on
 * the device ([DumpResult.Saved]) is a build's that reports nowhere, never the channel's.
 */
sealed interface DumpHandOff : DumpResult
