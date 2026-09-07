package app.snapsync.model

import co.touchlab.kermit.Severity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The process-metric rule (capability `crash-reporting`).
 *
 * Everything that DECIDES lives here, so everything that decides is tested here. The adapter that
 * feeds this is thin transcription and the dispatch that consumes it is a conditional-free loop —
 * neither is unit-tested, deliberately (spec `module-architecture`: the wiring graph is smoke-tested
 * end to end, not unit-tested), which is exactly why the rule had to hold every decision.
 *
 * Field shapes below are taken from a **real payload measured on device** (SE2, iOS 26.6,
 * 2026-08-28) rather than invented, so a test passing here means the rule handles what the platform
 * actually sends.
 */
class ProcessMetricsTest {

    private fun exit(area: String, counter: String, count: Int) =
        "$PROCESS_EXIT_PREFIX.$area.$counter" to "$count"

    private fun report(vararg pairs: Pair<String, String>) = ProcessMetricReport(mapOf(*pairs))

    private fun crossing(report: ProcessMetricReport) =
        processMetricEmissions(report).singleOrNull { it.severity == Severity.Error }

    // ── Flattening ────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `nested maps become dotted keys`() {
        val flat = flattenToDottedKeys(
            mapOf(
                "applicationExitMetrics" to mapOf(
                    "foregroundExitData" to mapOf("cumulativeAbnormalExitCount" to 6),
                    "backgroundExitData" to emptyMap<String, Any?>(),
                ),
                "appVersion" to "0.1",
            ),
        )
        assertEquals("6", flat["applicationExitMetrics.foregroundExitData.cumulativeAbnormalExitCount"])
        assertEquals("0.1", flat["appVersion"])
        // An empty branch contributes no keys at all, rather than a key holding "{}".
        assertTrue(flat.keys.none { it.startsWith("applicationExitMetrics.backgroundExitData") })
    }

    @Test
    fun `lists are indexed by position and nesting is unbounded`() {
        val flat = flattenToDottedKeys(mapOf("a" to listOf(mapOf("b" to mapOf("c" to 1)), "x")))
        assertEquals("1", flat["a.0.b.c"])
        assertEquals("x", flat["a.1"])
    }

    @Test
    fun `a null leaf is dropped rather than rendered as the string null`() {
        // "null" would be indistinguishable from a platform that really reported that text, and
        // absence already has a defined meaning for a report.
        val flat = flattenToDottedKeys(mapOf("terminationReason" to null, "signal" to 6))
        assertTrue("terminationReason" !in flat)
        assertEquals("6", flat["signal"])
    }

    // ── The routine line ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `a report with only normal exits emits one Info line and no crossing`() {
        val emissions = processMetricEmissions(
            report(
                exit("foregroundExitData", NORMAL_EXIT_SUFFIX, 12),
                PROCESS_METRIC_WINDOW_BEGIN to "2026-08-28 00:00:00",
                PROCESS_METRIC_WINDOW_END to "2026-08-29 00:00:00",
            ),
        )
        assertEquals(1, emissions.size)
        assertEquals(Severity.Info, emissions.single().severity)
        assertTrue(emissions.single().reasons.isEmpty())
    }

    @Test
    fun `an empty report still emits its line - so nothing-wrong differs from nothing-arrived`() {
        val emissions = processMetricEmissions(report())
        assertEquals(1, emissions.size)
        assertEquals(Severity.Info, emissions.single().severity)
    }

    @Test
    fun `the line names the reported period when there is one`() {
        val emissions = processMetricEmissions(
            report(
                PROCESS_METRIC_WINDOW_BEGIN to "2026-08-28 00:00:00",
                PROCESS_METRIC_WINDOW_END to "2026-08-29 00:00:00",
            ),
        )
        assertTrue("2026-08-28 00:00:00 .. 2026-08-29 00:00:00" in emissions.single().message)
    }

    // ── Crossings ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `every non-normal exit counter crosses - foreground and background`() {
        val counters = listOf(
            "cumulativeMemoryResourceLimitExitCount",
            "cumulativeMemoryPressureExitCount",
            "cumulativeAppWatchdogExitCount",
            "cumulativeBackgroundTaskAssertionTimeoutExitCount",
            "cumulativeCPUResourceLimitExitCount",
            "cumulativeBadAccessExitCount",
            "cumulativeIllegalInstructionExitCount",
            "cumulativeSuspendedWithLockedFileExitCount",
            "cumulativeAbnormalExitCount",
        )
        for (area in listOf("foregroundExitData", "backgroundExitData")) {
            for (counter in counters) {
                val crossed = crossing(report(exit(area, counter, 1)))
                assertTrue(crossed != null, "$area.$counter should cross but did not")
                assertEquals(listOf("$area.$counter"), crossed.reasons)
            }
        }
    }

    @Test
    fun `a counter this codebase has never heard of still crosses`() {
        // The promise that lets this capability carry no vocabulary pin: matching is by SHAPE, so a
        // counter a future OS adds is reported without any change here.
        val crossed = crossing(report(exit("backgroundExitData", "cumulativeSomethingNewExitCount", 1)))
        assertTrue(crossed != null)
        assertEquals(listOf("backgroundExitData.cumulativeSomethingNewExitCount"), crossed.reasons)
    }

    @Test
    fun `several crossing counters produce exactly one emission naming all of them`() {
        // The report is the unit. Per-counter events would split one cause across issues.
        val emissions = processMetricEmissions(
            report(
                exit("foregroundExitData", "cumulativeMemoryResourceLimitExitCount", 1),
                exit("foregroundExitData", "cumulativeAbnormalExitCount", 6),
                exit("backgroundExitData", "cumulativeAppWatchdogExitCount", 2),
            ),
        )
        val crossed = emissions.filter { it.severity == Severity.Error }
        assertEquals(1, crossed.size)
        assertEquals(3, crossed.single().reasons.size)
    }

    @Test
    fun `the crossing message is stable across differing counts so occurrences group`() {
        val one = crossing(report(exit("backgroundExitData", "cumulativeAppWatchdogExitCount", 1)))
        val many = crossing(report(exit("backgroundExitData", "cumulativeAppWatchdogExitCount", 14)))
        assertEquals(one?.message, many?.message)
        assertEquals(PROCESS_METRIC_CROSSED_MESSAGE, one?.message)
    }

    @Test
    fun `an absent counter and a zero counter reach the same verdict`() {
        // Platforms omit zero-valued counters from their serialization (measured), so the rule must
        // be correct under both readings — this pins that they agree.
        val absent = processMetricEmissions(report())
        val zero = processMetricEmissions(report(exit("foregroundExitData", "cumulativeAppWatchdogExitCount", 0)))
        assertEquals(absent.map { it.severity }, zero.map { it.severity })
    }

    @Test
    fun `unrecognised keys ride along without disturbing the verdict`() {
        val emissions = processMetricEmissions(
            report("someFutureMetric.withA.nestedKey" to "whatever", "metaData.deviceType" to "iPhone12,8"),
        )
        assertEquals(1, emissions.size)
        assertEquals(Severity.Info, emissions.single().severity)
    }

    // ── Hangs ─────────────────────────────────────────────────────────────────────────────────────

    private fun hangBucket(index: Int, count: Int, end: String) = arrayOf(
        "$HANG_HISTOGRAM_PREFIX.histogramValue.$index.bucketCount" to "$count",
        "$HANG_HISTOGRAM_PREFIX.histogramValue.$index.bucketEnd" to end,
    )

    @Test
    fun `the measured routine hang band does not cross`() {
        // The real distribution from the device: six hangs, 260-749 ms. The ceiling is calibrated to
        // leave exactly this quiet.
        val emissions = processMetricEmissions(
            report(
                *hangBucket(0, 1, "269 ms"), *hangBucket(1, 1, "279 ms"), *hangBucket(2, 1, "299 ms"),
                *hangBucket(3, 1, "519 ms"), *hangBucket(4, 1, "699 ms"), *hangBucket(5, 1, "749 ms"),
            ),
        )
        assertEquals(1, emissions.size)
    }

    @Test
    fun `a hang at or above the ceiling crosses - below it does not`() {
        assertTrue(crossing(report(*hangBucket(0, 1, "${HANG_CEILING_MS - 1} ms"))) == null)
        assertTrue(crossing(report(*hangBucket(0, 1, "$HANG_CEILING_MS ms"))) != null)
        assertTrue(crossing(report(*hangBucket(0, 1, "${HANG_CEILING_MS + 1} ms"))) != null)
    }

    @Test
    fun `a bucket nothing landed in is not evidence`() {
        // A histogram declares buckets; only a non-zero count means a hang actually happened.
        assertTrue(crossing(report(*hangBucket(0, 0, "5000 ms"))) == null)
    }

    @Test
    fun `seconds are understood as well as milliseconds`() {
        assertTrue(crossing(report(*hangBucket(0, 1, "3 sec"))) != null)
    }

    @Test
    fun `an unparseable duration does not testify either way`() {
        // It must not read as a small number and silently suppress a crossing.
        assertTrue(crossing(report(*hangBucket(0, 1, "9 furlongs"))) == null)
    }

    @Test
    fun `many crossing hang buckets still name one reason`() {
        val crossed = crossing(
            report(*hangBucket(0, 1, "2000 ms"), *hangBucket(1, 3, "4000 ms")),
        )
        assertEquals(1, crossed?.reasons?.size)
    }

    @Test
    fun `a report with no hang data at all is quiet`() {
        assertTrue(crossing(report("metaData.osVersion" to "iPhone OS 26.6 (23G71)")) == null)
    }
}

/** Call-stack pruning — the one branch that must never reach any channel (see [CALL_STACK_SEGMENTS]). */
class ProcessMetricsCallStackTest {

    @Test
    fun `a call stack tree is dropped by default - at any depth`() {
        val flat = flattenToDottedKeys(
            mapOf(
                "crashDiagnostics" to listOf(
                    mapOf(
                        "callStackTree" to mapOf("callStacks" to List(19) { mapOf("frames" to List(351) { "x" }) }),
                        "diagnosticMetaData" to mapOf("signal" to 6, "exceptionType" to 10),
                    ),
                ),
            ),
        )
        assertTrue(flat.keys.none { "callStackTree" in it }, "a call stack survived: ${flat.keys.take(3)}")
        assertEquals("6", flat["crashDiagnostics.0.diagnosticMetaData.signal"])
        assertEquals("10", flat["crashDiagnostics.0.diagnosticMetaData.exceptionType"])
    }

    @Test
    fun `dropping is the default - so forgetting it cannot happen`() {
        assertTrue("callStackTree" in CALL_STACK_SEGMENTS)
        val flat = flattenToDottedKeys(mapOf("callStackTree" to mapOf("a" to 1)))
        assertTrue(flat.isEmpty())
    }
}
