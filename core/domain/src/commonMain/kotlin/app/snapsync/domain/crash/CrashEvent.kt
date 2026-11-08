package app.snapsync.domain.crash

// The crash-reporting channel's vocabulary, in the core's own words: what the `CrashReporter` port carries in both
// directions, so no layer above the adapter names the reporting SDK. What a report may leave as is `CrashShaping`; what
// a log line becomes is `LineReport`; the adapter only translates between these values and the SDK's.

/** A breadcrumb's or an event's level, the four the channel distinguishes. */
enum class CrashLevel { DEBUG, INFO, WARNING, ERROR }

/**
 * One breadcrumb: a line of context that rides the NEXT event, never sent on its own.
 *
 * [data] is the breadcrumb's STRING data only. The SDK's automatic breadcrumbs also carry numbers (an HTTP status
 * code); those are not text, nothing reads them, and the adapter leaves them where they are.
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
 * Leaving ([CrashShaping.event], from the SDK's send hook): the adapter fills in what the event carries by then — the
 * SDK's [formatted] rendering, [params], the attached [breadcrumbs], the [tags] the scopes added and the [release] and
 * [dist] it is filed under — and writes back what the shaping returns, [fingerprint] included. A captured message's
 * text arrives there as [formatted] (measured on the Sentry SDK), so the shaping reads and shapes both. The SDK writes
 * neither an event's exceptions nor its contexts back from its send hook (read at sentry-kmp 0.27.0), so nothing here
 * shapes them: what an exception says is fixed at capture ([ReportedFailure]).
 */
data class CrashEvent(
    val message: String? = null,
    val formatted: String? = null,
    val params: List<String>? = null,
    val throwable: Throwable? = null,
    val breadcrumbs: List<Crumb> = emptyList(),
    val tags: Map<String, String> = emptyMap(),
    val contexts: Map<String, Map<String, String>> = emptyMap(),
    /** Leaving only: what the event groups under — empty for the backend's own grouping. */
    val fingerprint: List<String> = emptyList(),
    /**
     * Leaving only: the version line the event is filed under — the channel's own from the build's options unless the
     * event names another ([CrashTags.DESCRIBED_RELEASE]).
     */
    val release: String? = null,
    /** Leaving only: the build number the event is filed under, likewise. */
    val dist: String? = null,
)

/**
 * A logged failure as it is reported: titled by the line that logged it — `ReportedFailure: could not open the
 * database` — with the failure itself chained beneath it as its cause, its type, text and stack intact. The operator's
 * overview then names what happened, never the values it happened with, and one cause stays one issue.
 */
class ReportedFailure(event: String, cause: Throwable) : Exception(event, cause)

/** The tags an event may carry, and the ones that never leave as tags. */
object CrashTags {
    /** The platform a report came from ([app.snapsync.domain.report.Platform.tag]). */
    const val PLATFORM: String = "platform"

    /** The process a report came from: its bundle id on iOS (app or extension), its package on Android. */
    const val PROCESS: String = "process"

    /** The entry point an event's line was written under. */
    const val ENTRY_POINT: String = "entry_point"

    /**
     * What an event groups under, carried from its capture to its leaving — the SDK keeps no fingerprint on a scope, so
     * it rides as a tag and [CrashShaping.event] makes it the event's fingerprint. It never leaves as a tag.
     */
    const val FINGERPRINT: String = "fingerprint"

    /**
     * The build an event is ABOUT, when that is not the build sending it: a process-metric report describes a process
     * that has ended, and arrives on whichever launch came next — a day later, possibly of another build (SNAPSYNC-61).
     * [CrashShaping.event] moves it into the event's own release and build number; it never leaves as a tag.
     */
    const val DESCRIBED_RELEASE: String = "described.release"

    /** The build number half of [DESCRIBED_RELEASE]. */
    const val DESCRIBED_BUILD: String = "described.build"
}

/** The sections a report carries about SnapSync itself, apart from everything the SDK adds. */
object CrashSections {
    /** The ids SnapSync knows — the joined event, the device, the process — on every report ([ReportedIds]). */
    const val SNAPSYNC: String = "snapsync"

    /** The fields of the line an event was born from, ids in full ([LineReport]). */
    const val LINE: String = "snapsync_log"

    /** The process's own account of how it has been behaving, riding every later event (`ProcessMetricRules`). */
    const val PROCESS_METRICS: String = "process_metrics"
}
