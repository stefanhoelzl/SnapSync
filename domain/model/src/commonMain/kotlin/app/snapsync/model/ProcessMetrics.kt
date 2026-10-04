package app.snapsync.model

import co.touchlab.kermit.Severity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One report about this process's own behaviour, as some platform accounted for it (capability
 * `privacy-security`; the seam is `ports/ProcessMetrics`).
 *
 * **Deliberately an open bag of strings.** The provider flattens whatever the platform serialized,
 * and `:domain` never changes to accommodate a field a future OS adds — it simply appears. There is
 * no window in the type either: a provider whose reports describe an interval sets
 * [PROCESS_METRIC_WINDOW_BEGIN]/[PROCESS_METRIC_WINDOW_END] like any other field, and one whose
 * reports do not simply omits them.
 *
 * ⚠️ **An absent field and a zero field are indistinguishable**, because platforms omit zero-valued
 * counters from their serialized form (measured). Every rule below is written to be correct under
 * that: they fire on *presence of a non-zero value*, and both kinds of absence mean "do not fire".
 * Do not add a rule that needs to tell those two apart — it cannot be answered from a report.
 */
data class ProcessMetricReport(val fields: Map<String, String>)

/** Where the reported period began, when the provider reports periods at all. */
const val PROCESS_METRIC_WINDOW_BEGIN: String = "timeStampBegin"

/** Where the reported period ended. */
const val PROCESS_METRIC_WINDOW_END: String = "timeStampEnd"

/**
 * The dotted key prefix under which process-exit tallies live.
 *
 * Matching by **shape** rather than by a list of counter names is what makes a counter the platform
 * adds appear without a code change — the promise that lets this capability carry no vocabulary pin.
 */
const val PROCESS_EXIT_PREFIX: String = "applicationExitMetrics"

/** The one exit tally that is not a problem: the process ended because it was meant to. */
const val NORMAL_EXIT_SUFFIX: String = "cumulativeNormalAppExitCount"

/** The dotted key prefix under which app-hang durations live. */
const val HANG_HISTOGRAM_PREFIX: String = "applicationResponsivenessMetrics.histogrammedAppHangTime"

/**
 * The hang duration, in milliseconds, at or above which a report is worth reporting.
 *
 * **1 s, and the number is a starting point rather than a finding.** Measured on one device across one
 * day: six hangs, every one between 260 ms and 749 ms — routine jank. One second sits just above that
 * band, so the known-normal baseline stays quiet while anything a user would call "stuck" fires.
 *
 * The platform documents **no** minimum hang duration and no guidance on what is acceptable; the only
 * number it states is that hangs beyond nine seconds are collapsed into a final bucket. So this is
 * calibrated against our own measurement, not against a vendor threshold.
 *
 * ⏰ **Loosen this once hang distributions exist from more than one device.** The design's posture is
 * deliberately tight first — over-report, learn the shape, then narrow — and raising this is a single
 * reviewed edit with data behind it.
 */
const val HANG_CEILING_MS: Long = 1_000L

/**
 * The exit tally that crosses only when **we** made it likely: the system reclaimed the app while it was suspended.
 *
 * iOS ends suspended apps whenever the foreground needs memory, largest first, so a background memory-pressure exit
 * is the platform working as designed — every one of the first four on the board (SNAPSYNC-38, 2026-09/10) came from
 * a day with 1–18 s of background time and a suspended footprint of 28–117 MB. What is ours to answer for is the
 * footprint, so the tally crosses only alongside a suspended footprint above [SUSPENDED_MEMORY_CEILING_KB]. The
 * tally the app causes outright, `cumulativeMemoryResourceLimitExitCount`, is untouched and always crosses.
 */
const val BACKGROUND_MEMORY_PRESSURE_EXIT: String = "backgroundExitData.cumulativeMemoryPressureExitCount"

/** Where the reported period's average suspended footprint lives (`"83574 kB"`). */
const val SUSPENDED_MEMORY_KEY: String = "memoryMetrics.averageSuspendedMemory.averageValue"

/**
 * The average suspended footprint, in kB, above which a background memory-pressure exit is worth reporting.
 *
 * **150 MB, a starting point calibrated like [HANG_CEILING_MS]**: just above the highest value the four
 * memory-pressure reports on the board carried (117 MB), so the measured baseline stays quiet and a footprint
 * that grows past it fires. A footprint the report does not state, or states in a unit not understood here,
 * **crosses** — an unknown cannot show the exit was the platform's alone.
 */
const val SUSPENDED_MEMORY_CEILING_KB: Long = 150_000L

/**
 * One thing to emit for a report — a severity and a line, exactly as [RegistrationOutcome] does it.
 *
 * Emissions rather than a verdict, on purpose: the caller becomes a loop with **no conditional**, so
 * every decision about what is worth reporting sits here in tested code rather than in the adapter or
 * shell that dispatches it.
 */
data class ProcessMetricEmission(
    val severity: Severity,
    val message: String,
    /** Why this crossed, for the report's context. Null on the routine per-report line. */
    val reason: String? = null,
)

/**
 * What every crossing's message starts with; the reason follows, so each reason groups as **its own** issue.
 *
 * One fixed message for every crossing (the first shape) put a hang under an issue titled as an exit and made the
 * issue list one undifferentiated entry. A message per reason has neither problem and none of the explosion a
 * message naming a report's *combination* of reasons would: each crossing names exactly one, and a counter a
 * future OS adds becomes a new issue by itself.
 */
const val PROCESS_METRIC_CROSSED_PREFIX: String = "process metric crossed: "

/**
 * What to emit for [report]: always one line describing it, plus one crossing line **per reason** it crosses on.
 *
 * A crossing is: any non-normal exit tally present with a non-zero value (a background memory-pressure exit only
 * alongside a suspended footprint above [SUSPENDED_MEMORY_CEILING_KB]), or any hang bucket at or above
 * [HANG_CEILING_MS] that actually happened. Deliberately **every** other non-normal tally, including ones whose
 * meaning is not yet established — including them is how their meaning gets measured, and excluding them
 * preserves the unknown indefinitely.
 *
 * A reason is reported once per report however large its count, so a relaunch storm is still one event naming
 * the reason, its count in the report. This is not suppression: a tally is not an error, and crossing is what
 * constitutes one. Nothing is ever withheld once this function has decided it.
 */
fun processMetricEmissions(report: ProcessMetricReport): List<ProcessMetricEmission> {
    val line = ProcessMetricEmission(
        severity = Severity.Info,
        message = "process metrics: ${describe(report)}",
    )
    val crossings = (exitReasons(report) + hangReasons(report)).sorted().map { reason ->
        ProcessMetricEmission(severity = Severity.Error, message = PROCESS_METRIC_CROSSED_PREFIX + reason, reason = reason)
    }
    return listOf(line) + crossings
}

/** Every non-normal exit tally carrying a non-zero count, named by its own key's leaf. */
private fun exitReasons(report: ProcessMetricReport): List<String> =
    report.fields.entries
        .filter { (key, _) -> key.startsWith("$PROCESS_EXIT_PREFIX.") }
        .filterNot { (key, _) -> key.endsWith(NORMAL_EXIT_SUFFIX) }
        .filter { (_, value) -> (value.trim().toLongOrNull() ?: 0L) > 0L }
        .map { (key, _) -> key.removePrefix("$PROCESS_EXIT_PREFIX.") }
        .filterNot { it == BACKGROUND_MEMORY_PRESSURE_EXIT && suspendedFootprintWithinCeiling(report) }

/** Whether the report states a suspended footprint at or below the ceiling; an unstated one is not. */
private fun suspendedFootprintWithinCeiling(report: ProcessMetricReport): Boolean {
    val kilobytes = kilobytesOf(report.fields[SUSPENDED_MEMORY_KEY]) ?: return false
    return kilobytes <= SUSPENDED_MEMORY_CEILING_KB
}

/**
 * A platform-rendered quantity of memory (`"83574 kB"`) as kB, or `null` when it is not one understood here — the
 * same posture as [millisOf]. Decimal units, as the platform's information-storage measurements are.
 */
private fun kilobytesOf(raw: String?): Long? {
    val text = raw?.trim() ?: return null
    val number = text.takeWhile { it.isDigit() || it == '.' }
    val amount = number.toDoubleOrNull() ?: return null
    return when (text.removePrefix(number).trim()) {
        "kB", "KB" -> amount.toLong()
        "MB" -> (amount * 1_000).toLong()
        "GB" -> (amount * 1_000_000).toLong()
        else -> null
    }
}

/**
 * Hang buckets that both happened and reached the ceiling.
 *
 * A histogram bucket is two fields — how many hangs landed in it, and how long the bucket runs to —
 * so a bucket is only evidence when its count is non-zero. The bucket's END is compared, so "at or
 * above the ceiling" means the hang could have been that long, which is the conservative reading for
 * a leading indicator.
 */
private fun hangReasons(report: ProcessMetricReport): List<String> {
    val buckets = report.fields.keys
        .filter { it.startsWith("$HANG_HISTOGRAM_PREFIX.") && it.endsWith(".bucketEnd") }
        .map { it.removeSuffix(".bucketEnd") }
    val crossed = buckets.filter { bucket ->
        val count = report.fields["$bucket.bucketCount"]?.trim()?.toLongOrNull() ?: 0L
        val end = millisOf(report.fields["$bucket.bucketEnd"])
        count > 0L && end != null && end >= HANG_CEILING_MS
    }
    return if (crossed.isEmpty()) emptyList() else listOf("appHangAtOrAbove${HANG_CEILING_MS}ms")
}

/**
 * A platform-rendered duration (`"749 ms"`, `"3 sec"`) as milliseconds, or `null` when it is neither.
 *
 * Returning `null` rather than guessing is load-bearing: an unrecognised unit must not silently read
 * as a small number and suppress a crossing. An unparseable duration simply does not testify.
 */
private fun millisOf(raw: String?): Long? {
    val text = raw?.trim() ?: return null
    val number = text.takeWhile { it.isDigit() || it == '.' }
    val amount = number.toDoubleOrNull() ?: return null
    return when (text.removePrefix(number).trim()) {
        "ms" -> amount.toLong()
        "s", "sec" -> (amount * 1_000).toLong()
        else -> null
    }
}

/**
 * The report's period and **every field it carries**, sorted so two reports read comparably.
 *
 * The fields are here and not merely counted because a report that does NOT cross reaches no other
 * channel: the context only rides a transmitted event, so a quiet report's contents would exist
 * nowhere. That was measured the hard way — a 73-field window covering a deliberate experiment
 * arrived, did not cross, and left behind only the number 73, which is the "absence is never silent"
 * rule failing in the one channel meant to guarantee it.
 *
 * Size is bounded and already accepted: ~50–100 fields is a few KB, three orders of magnitude under
 * the call stacks this design excludes outright.
 */
private fun describe(report: ProcessMetricReport): String {
    val begin = report.fields[PROCESS_METRIC_WINDOW_BEGIN]
    val end = report.fields[PROCESS_METRIC_WINDOW_END]
    val period = if (begin != null && end != null) "$begin .. $end" else "no period reported"
    // ONE JSON object, keys sorted. Not `key=value` pairs: platform values contain spaces
    // (`118417 kB`, `2026-09-13 00:00:00`), so a space-joined line did not split back into fields —
    // measured by parsing one and getting it wrong. JSON is unambiguous, needs no invented escaping,
    // and `jq` reads it straight out of a pulled log.
    // `sortedBy` + `associate`, not `toSortedMap()`: the latter is JVM-only, and this is commonMain.
    // `associate` keeps insertion order, and JsonObject renders in the order it is given.
    val fields = JsonObject(report.fields.entries.sortedBy { it.key }.associate { it.key to JsonPrimitive(it.value) })
    return "$period, ${report.fields.size} field(s) | $fields"
}

/**
 * Branches never worth carrying, dropped **by default** so including them takes a deliberate act.
 *
 * A call-stack tree is the one thing in a platform report that cannot ride any channel here. Measured:
 * twelve queued diagnostic payloads serialized to 15.1 MB, rolled the 10 MB device log and destroyed
 * the history preceding it, in a call that blocked for 18 s. A single one is 314 KB across 19 thread
 * stacks and carries 20 binary UUIDs — UUID-shaped, so the reporting scrub would replace exactly the
 * field offline symbolication resolves against.
 *
 * Defaulting to dropping rather than requiring callers to remember is the point: the failure mode is
 * silent and expensive, so the safe reading is the one you get for free.
 */
val CALL_STACK_SEGMENTS: Set<String> = setOf("callStackTree")

/**
 * Flatten a platform's nested serialization into the dotted keys a [ProcessMetricReport] carries.
 *
 * Here and not in the adapter because it is a **decision** — how deeply to descend, what a leaf is,
 * how a key is spelled — and adapters are thin transcription that no test reaches. The adapter's job
 * shrinks to handing over whatever the platform gave it, already converted to Kotlin collections.
 *
 * Leaves render with [toString], so numbers, booleans and platform-rendered quantities (`"749 ms"`)
 * all arrive as text — which is what lets the vocabulary stay open. Lists are indexed by position,
 * matching how histogram buckets already key themselves.
 *
 * A `null` value is **dropped rather than rendered**, because `"null"` would be indistinguishable
 * from a platform that genuinely reported that string, and absence already has a defined meaning
 * here (see [ProcessMetricReport]).
 */
fun flattenToDottedKeys(
    nested: Map<String, Any?>,
    dropSegments: Set<String> = CALL_STACK_SEGMENTS,
): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    fun walk(prefix: String, value: Any?) {
        when (value) {
            null -> Unit
            is Map<*, *> -> value.forEach { (k, v) ->
                if (k.toString() !in dropSegments) walk(join(prefix, k.toString()), v)
            }
            is List<*> -> value.forEachIndexed { index, v -> walk(join(prefix, index.toString()), v) }
            else -> out[prefix] = value.toString()
        }
    }
    // The drop applies at the ROOT as well as at every nesting level. Checking only nested keys
    // would leave a top-level call-stack branch intact — which is exactly the shape a per-diagnostic
    // payload has, so the omission would have been invisible until 300 KB arrived.
    nested.forEach { (key, value) -> if (key !in dropSegments) walk(key, value) }
    return out
}

private fun join(prefix: String, segment: String): String =
    if (prefix.isEmpty()) segment else "$prefix.$segment"
