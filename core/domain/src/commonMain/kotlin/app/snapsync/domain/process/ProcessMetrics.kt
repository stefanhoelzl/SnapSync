package app.snapsync.domain.process

import app.snapsync.domain.crash.CrashEvent
import app.snapsync.domain.crash.CrashTags
import kotlin.jvm.JvmInline

/**
 * One report about this process's own behaviour, as some platform accounted for it (the seam is
 * `ports/ProcessMetrics`).
 *
 * **Deliberately an open bag of strings.** The provider flattens whatever the platform serialized, and the core never
 * changes to accommodate a field a future OS adds — it simply appears. There is no window in the type either: a
 * provider whose reports describe an interval sets [ProcessMetricRules.WINDOW_BEGIN]/[ProcessMetricRules.WINDOW_END]
 * like any other field, and one whose reports do not simply omits them.
 *
 * ⚠️ **An absent field and a zero field are indistinguishable**, because platforms omit zero-valued counters from their
 * serialized form (measured). Every rule over it fires on *presence of a non-zero value*, and both kinds of absence
 * mean "do not fire". Do not add a rule that needs to tell those two apart — it cannot be answered from a report.
 */
data class ProcessMetricReport(val fields: Map<String, String>)

/** One reason a report crosses on: the platform's own name for it, so a counter a future OS adds is one by itself. */
@JvmInline
value class CrossingReason(val key: String)

/**
 * What a report comes to: the [period] it covers, and every reason it [crossings] on, sorted — a report that crosses on
 * none reaches no one beyond the device log.
 */
class ProcessMetricAccount(val period: String, val crossings: List<CrossingReason>) {
    /**
     * Why it crossed, carried WITH the report in its context — every reason, so each of its events shows what else
     * crossed that day. None for a report that crosses on nothing.
     */
    val reasons: Map<String, String>
        get() = crossings.takeIf { it.isNotEmpty() }
            ?.let { mapOf(REASONS to it.joinToString(",") { reason -> reason.key }) }
            .orEmpty()

    /**
     * What a crossing report says before its crossings: a report arrives on a LATER launch than the one it describes —
     * measured: the day after, ~1 s into whichever launch came next — so its events' breadcrumbs are that delivering
     * launch's lines, never the ended process's. Read as the process's last moments they mislead (SNAPSYNC-38), so the
     * event says so where its trail ends. None for a report that crosses on nothing: it reaches no event.
     */
    val notes: List<String>
        get() = listOf(DELIVERED_LATER).takeIf { crossings.isNotEmpty() }.orEmpty()

    private companion object {
        const val REASONS = "crossing.reasons"

        const val DELIVERED_LATER =
            "the breadcrumbs above are from the launch that DELIVERED this report, not from the process it " +
                "describes; that process's own last memory readings are the context's ownFootprint.* fields"
    }
}

/** The rules over a [ProcessMetricReport]: what is worth reporting, and how a crossing is filed. */
object ProcessMetricRules {
    /** Where the reported period began, when the provider reports periods at all. */
    const val WINDOW_BEGIN: String = "timeStampBegin"

    /** Where the reported period ended. */
    const val WINDOW_END: String = "timeStampEnd"

    /**
     * The dotted key prefix under which process-exit tallies live. Matching by **shape** rather than by a list of
     * counter names is what makes a counter the platform adds appear without a code change.
     */
    const val EXIT_PREFIX: String = "applicationExitMetrics"

    /** The one exit tally that is not a problem: the process ended because it was meant to. */
    const val NORMAL_EXIT_SUFFIX: String = "cumulativeNormalAppExitCount"

    /** The dotted key prefix under which app-hang durations live. */
    const val HANG_HISTOGRAM_PREFIX: String = "applicationResponsivenessMetrics.histogrammedAppHangTime"

    /**
     * The hang duration, in milliseconds, at or above which a report is worth reporting.
     *
     * **1 s, and the number is a starting point rather than a finding.** Measured on one device across one day: six
     * hangs, every one between 260 ms and 749 ms — routine jank. One second sits just above that band, so the
     * known-normal baseline stays quiet while anything a user would call "stuck" fires. The platform documents **no**
     * minimum hang duration; the only number it states is that hangs beyond nine seconds are collapsed into a final
     * bucket.
     *
     * ⏰ **Loosen this once hang distributions exist from more than one device.** Raising this is a single reviewed edit
     * with data behind it.
     */
    const val HANG_CEILING_MS: Long = 1_000L

    /**
     * The exit tally that crosses only when **we** made it likely: the system reclaimed the app while it was suspended.
     *
     * iOS ends suspended apps whenever the foreground needs memory, largest first, so a background memory-pressure exit
     * is the platform working as designed — every one of the first four on the board (SNAPSYNC-38, 2026-09/10) came
     * from a day with 1–18 s of background time and a suspended footprint of 28–117 MB. What is ours to answer for is
     * the footprint, so the tally crosses only alongside a suspended footprint above [SUSPENDED_MEMORY_CEILING_KB]. The
     * tally the app causes outright, `cumulativeMemoryResourceLimitExitCount`, is untouched and always crosses.
     */
    const val BACKGROUND_MEMORY_PRESSURE_EXIT: String = "backgroundExitData.cumulativeMemoryPressureExitCount"

    /** Where the reported period's average suspended footprint lives (`"83574 kB"`). */
    const val SUSPENDED_MEMORY_KEY: String = "memoryMetrics.averageSuspendedMemory.averageValue"

    /**
     * The average suspended footprint, in kB, above which a background memory-pressure exit is worth reporting: **150
     * MB**, just above the highest value the four memory-pressure reports on the board carried (117 MB). A footprint
     * the report does not state, or states in a unit not understood here, **crosses** — an unknown cannot show the exit
     * was the platform's alone.
     */
    const val SUSPENDED_MEMORY_CEILING_KB: Long = 150_000L

    /** Where a report names the version line of the build it describes — not necessarily the build delivering it. */
    const val RELEASE_KEY: String = "appVersion"

    /** Where a report names the build number of the build it describes. */
    const val BUILD_KEY: String = "metaData.appBuildVersion"

    /** What every crossing event is titled with, ahead of its reason: each reason groups as **its own** issue. */
    const val CROSSED: String = "process metric crossed"

    /**
     * Branches never worth carrying, dropped **by default** so including them takes a deliberate act. A call-stack tree
     * is the one thing in a platform report that cannot ride any channel here. Measured: twelve queued diagnostic
     * payloads serialized to 15.1 MB, rolled the 10 MB device log and destroyed the history preceding it, in a call
     * that blocked for 18 s.
     */
    val CALL_STACK_SEGMENTS: Set<String> = setOf("callStackTree")

    /**
     * What [report] comes to: its period, and one crossing **per reason** — any non-normal exit tally present with a
     * non-zero value (a background memory-pressure exit only alongside a suspended footprint above the ceiling), or any
     * hang bucket at or above [HANG_CEILING_MS] that actually happened. Deliberately **every** other non-normal tally,
     * including ones whose meaning is not yet established — including them is how their meaning gets measured. A reason
     * is reported once per report however large its count: a tally is not an error, and crossing is what constitutes
     * one.
     */
    fun account(report: ProcessMetricReport): ProcessMetricAccount = ProcessMetricAccount(
        period = period(report),
        crossings = (exitReasons(report) + hangReasons(report)).sorted().map(::CrossingReason),
    )

    /**
     * The event a crossing [reason] of [report] sends, handled under [trigger]: titled `process metric crossed:
     * <reason>`
     * and filed under the build the report DESCRIBES. A report arrives on a later launch, possibly of another build, so
     * the delivering build's own release and build number would attribute it to a build it never happened on
     * (SNAPSYNC-61). A report naming neither leaves the event filed under the delivering build.
     */
    fun crossingEvent(reason: CrossingReason, report: ProcessMetricReport, trigger: String?): CrashEvent {
        val title = "$CROSSED: ${reason.key}"
        return CrashEvent(
            message = title,
            tags = buildMap {
                put(CrashTags.FINGERPRINT, title)
                trigger?.let { put(CrashTags.ENTRY_POINT, it) }
                report.named(RELEASE_KEY)?.let { put(CrashTags.DESCRIBED_RELEASE, it) }
                report.named(BUILD_KEY)?.let { put(CrashTags.DESCRIBED_BUILD, it) }
            },
        )
    }

    private fun period(report: ProcessMetricReport): String {
        val end = report.fields[WINDOW_END]
        return report.fields[WINDOW_BEGIN]?.let { begin -> end?.let { "$begin .. $it" } } ?: NO_PERIOD
    }

    private fun ProcessMetricReport.named(key: String): String? = fields[key]?.takeIf { it.isNotBlank() }

    /** Every non-normal exit tally carrying a non-zero count, named by its own key's leaf. */
    private fun exitReasons(report: ProcessMetricReport): List<String> = report.fields.entries
        .filter { (key, value) -> key.startsWith("$EXIT_PREFIX.") && !key.endsWith(NORMAL_EXIT_SUFFIX) }
        .filter { (_, value) -> counted(value) }
        .map { (key, _) -> key.removePrefix("$EXIT_PREFIX.") }
        .filterNot { it == BACKGROUND_MEMORY_PRESSURE_EXIT && suspendedFootprintWithinCeiling(report) }

    private fun counted(value: String?): Boolean = (value?.let { it.trim().toLongOrNull() } ?: 0L) > 0L

    /** Whether the report states a suspended footprint at or below the ceiling; an unstated one is not. */
    private fun suspendedFootprintWithinCeiling(report: ProcessMetricReport): Boolean =
        report.fields[SUSPENDED_MEMORY_KEY]?.let { quantity(it, KILOBYTES) }?.let { it <= SUSPENDED_MEMORY_CEILING_KB }
            ?: false

    /**
     * Hang buckets that both happened and reached the ceiling. A histogram bucket is two fields — how many hangs landed
     * in it, and how long the bucket runs to — so a bucket is only evidence when its count is non-zero. The bucket's
     * END is compared, so "at or above the ceiling" means the hang could have been that long: the conservative reading.
     */
    private fun hangReasons(report: ProcessMetricReport): List<String> {
        val crossed = report.fields.entries
            .filter { (key, _) -> key.startsWith("$HANG_HISTOGRAM_PREFIX.") && key.endsWith(END) }
            .any { (key, end) -> happened(report.fields[key.removeSuffix(END) + ".bucketCount"], end) }
        return listOfNotNull("appHangAtOrAbove${HANG_CEILING_MS}ms".takeIf { crossed })
    }

    /** Whether a bucket held a hang ([count]) that reached the ceiling ([end]); an unknown duration never does. */
    private fun happened(count: String?, end: String): Boolean {
        val millis = quantity(end, MILLISECONDS)
        return counted(count) && millis != null && millis >= HANG_CEILING_MS
    }

    /**
     * A platform-rendered quantity (`"83574 kB"`, `"749 ms"`, `"3 sec"`) in the unit [scale] counts in, or `null` when
     * it is not one understood here. Returning `null` rather than guessing is load-bearing: an unrecognised unit must
     * not silently read as a small number and suppress a crossing.
     */
    private fun quantity(raw: String, scale: Map<String, Double>): Long? {
        val text = raw.trim()
        val number = text.takeWhile { it.isDigit() || it == '.' }
        val factor = scale[text.removePrefix(number).trim()]
        return number.toDoubleOrNull()?.let { amount -> factor?.let { (amount * it).toLong() } }
    }

    /** Decimal units, as the platform's information-storage measurements are. */
    private val KILOBYTES = mapOf("kB" to 1.0, "KB" to 1.0, "MB" to 1_000.0, "GB" to 1_000_000.0)

    private val MILLISECONDS = mapOf("ms" to 1.0, "s" to 1_000.0, "sec" to 1_000.0)

    private const val NO_PERIOD = "no period reported"

    /** A histogram bucket's end, the key it is found by. */
    private const val END = ".bucketEnd"
}
