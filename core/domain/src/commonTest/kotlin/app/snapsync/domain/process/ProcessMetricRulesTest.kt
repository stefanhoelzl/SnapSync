package app.snapsync.domain.process

import app.snapsync.domain.crash.CrashTags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The process-metric rule (capability `privacy-security`).
 *
 * Everything that DECIDES lives here, so everything that decides is tested here: what a report crosses on, and how a
 * crossing is filed. What is logged of it is `AccountForProcess`'s.
 *
 * Field shapes below are taken from a **real payload measured on device** (SE2, iOS 26.6,
 * 2026-08-28) rather than invented, so a test passing here means the rule handles what the platform
 * actually sends.
 */
class ProcessMetricRulesTest {

    private fun exit(area: String, counter: String, count: Int) =
        "${ProcessMetricRules.EXIT_PREFIX}.$area.$counter" to "$count"

    private fun report(vararg pairs: Pair<String, String>) = ProcessMetricReport(mapOf(*pairs))

    private fun crossings(report: ProcessMetricReport) = ProcessMetricRules.account(report).crossings

    private fun crossing(report: ProcessMetricReport) = crossings(report).singleOrNull()

    // ── The period ──────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a report with only normal exits crosses on nothing, and names its period`() {
        val account = ProcessMetricRules.account(
            report(
                exit("foregroundExitData", ProcessMetricRules.NORMAL_EXIT_SUFFIX, 12),
                ProcessMetricRules.WINDOW_BEGIN to "2026-08-28 00:00:00",
                ProcessMetricRules.WINDOW_END to "2026-08-29 00:00:00",
            ),
        )
        assertEquals(emptyList(), account.crossings)
        assertEquals("2026-08-28 00:00:00 .. 2026-08-29 00:00:00", account.period)
    }

    @Test
    fun `a report that states no whole period says so`() {
        assertEquals("no period reported", ProcessMetricRules.account(report()).period)
        val half = report(ProcessMetricRules.WINDOW_BEGIN to "2026-08-28 00:00:00")
        assertEquals("no period reported", ProcessMetricRules.account(half).period)
    }

    @Test
    fun `a crossing report carries why it crossed, and says its breadcrumbs are the delivering launch's`() {
        val account = ProcessMetricRules.account(
            report(
                exit("foregroundExitData", "cumulativeAppWatchdogExitCount", 1),
                exit("backgroundExitData", "cumulativeAppWatchdogExitCount", 2),
            ),
        )
        assertEquals(
            "backgroundExitData.cumulativeAppWatchdogExitCount,foregroundExitData.cumulativeAppWatchdogExitCount",
            account.reasons["crossing.reasons"],
        )
        assertTrue(account.notes.single().startsWith("the breadcrumbs above are from the launch that DELIVERED"))
    }

    @Test
    fun `a quiet report carries no reason and no note - it reaches no event`() {
        val account = ProcessMetricRules.account(report())
        assertEquals(emptyMap(), account.reasons)
        assertEquals(emptyList(), account.notes)
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
                // A stated footprint over the ceiling, so the background memory-pressure gate is open too.
                val crossed = crossing(
                    report(exit(area, counter, 1), ProcessMetricRules.SUSPENDED_MEMORY_KEY to "200000 kB"),
                )
                assertTrue(crossed != null, "$area.$counter should cross but did not")
                assertEquals("$area.$counter", crossed.key)
            }
        }
    }

    @Test
    fun `a counter this codebase has never heard of still crosses`() {
        // The promise that lets this capability carry no vocabulary pin: matching is by SHAPE, so a
        // counter a future OS adds is reported without any change here.
        val crossed = crossing(report(exit("backgroundExitData", "cumulativeSomethingNewExitCount", 1)))
        assertTrue(crossed != null)
        assertEquals("backgroundExitData.cumulativeSomethingNewExitCount", crossed.key)
    }

    @Test
    fun `several crossing counters produce one emission each - every one naming its own reason`() {
        // One issue per failure mode: a message per reason groups each on its own, and never a combination.
        val crossed = crossings(
            report(
                exit("foregroundExitData", "cumulativeMemoryResourceLimitExitCount", 1),
                exit("foregroundExitData", "cumulativeAbnormalExitCount", 6),
                exit("backgroundExitData", "cumulativeAppWatchdogExitCount", 2),
            ),
        )
        assertEquals(
            listOf(
                "backgroundExitData.cumulativeAppWatchdogExitCount",
                "foregroundExitData.cumulativeAbnormalExitCount",
                "foregroundExitData.cumulativeMemoryResourceLimitExitCount",
            ),
            crossed.map { it.key },
        )
        assertEquals(crossed.map { "process metric crossed: ${it.key}" }, crossed.map(::title))
    }

    @Test
    fun `the crossing title is stable across differing counts so occurrences group`() {
        val one = crossing(report(exit("backgroundExitData", "cumulativeAppWatchdogExitCount", 1)))!!
        val many = crossing(report(exit("backgroundExitData", "cumulativeAppWatchdogExitCount", 14)))!!
        assertEquals(title(one), title(many))
        assertEquals("process metric crossed: backgroundExitData.cumulativeAppWatchdogExitCount", title(one))
    }

    @Test
    fun `a hang and an exit in one report land in different issues`() {
        // The first shape titled a hang as an exit (SNAPSYNC-38).
        val crossed = crossings(
            report(exit("foregroundExitData", "cumulativeAppWatchdogExitCount", 1), *hangBucket(0, 1, "1689 ms")),
        )
        assertEquals(2, crossed.map(::title).distinct().size)
    }

    private fun title(reason: CrossingReason): String? = ProcessMetricRules.crossingEvent(
        reason,
        report(),
        null,
    ).message

    // ── Background memory pressure ────────────────────────────────────────────────────────────────

    private fun memoryPressure(suspended: String?) = crossing(
        report(
            *listOfNotNull(
                exit("backgroundExitData", "cumulativeMemoryPressureExitCount", 1),
                suspended?.let { ProcessMetricRules.SUSPENDED_MEMORY_KEY to it },
            ).toTypedArray(),
        ),
    )

    @Test
    fun `a background memory-pressure exit with the measured suspended footprints does not cross`() {
        // The four SNAPSYNC-38 reports, 2026-09/10: the platform reclaiming a suspended app, nothing of ours.
        for (footprint in listOf("28290 kB", "42277 kB", "83574 kB", "116773 kB")) {
            assertEquals(null, memoryPressure(footprint), footprint)
        }
    }

    @Test
    fun `a background memory-pressure exit crosses once the suspended footprint passes the ceiling`() {
        assertEquals(null, memoryPressure("${ProcessMetricRules.SUSPENDED_MEMORY_CEILING_KB} kB"))
        assertEquals(
            ProcessMetricRules.BACKGROUND_MEMORY_PRESSURE_EXIT,
            memoryPressure("${ProcessMetricRules.SUSPENDED_MEMORY_CEILING_KB + 1} kB")?.key,
        )
        assertEquals(ProcessMetricRules.BACKGROUND_MEMORY_PRESSURE_EXIT, memoryPressure("0.2 GB")?.key)
    }

    @Test
    fun `a background memory-pressure exit with no understood footprint crosses`() {
        // An unknown cannot show the exit was the platform's alone.
        assertEquals(ProcessMetricRules.BACKGROUND_MEMORY_PRESSURE_EXIT, memoryPressure(null)?.key)
        assertEquals(ProcessMetricRules.BACKGROUND_MEMORY_PRESSURE_EXIT, memoryPressure("9 furlongs")?.key)
        assertEquals(ProcessMetricRules.BACKGROUND_MEMORY_PRESSURE_EXIT, memoryPressure("unknown")?.key)
    }

    @Test
    fun `a footprint is read in any decimal unit the platform renders it in`() {
        assertEquals(null, memoryPressure("100000 KB"))
        assertEquals(null, memoryPressure("100 MB"))
        assertEquals(ProcessMetricRules.BACKGROUND_MEMORY_PRESSURE_EXIT, memoryPressure("151 MB")?.key)
    }

    @Test
    fun `a foreground memory-pressure exit crosses whatever the suspended footprint`() {
        val crossed = crossing(
            report(
                exit("foregroundExitData", "cumulativeMemoryPressureExitCount", 1),
                ProcessMetricRules.SUSPENDED_MEMORY_KEY to "1 kB",
            ),
        )
        assertEquals("foregroundExitData.cumulativeMemoryPressureExitCount", crossed?.key)
    }

    @Test
    fun `a gated memory-pressure exit does not hold back another reason`() {
        val crossed = crossings(
            report(
                exit("backgroundExitData", "cumulativeMemoryPressureExitCount", 1),
                exit("backgroundExitData", "cumulativeMemoryResourceLimitExitCount", 1),
                ProcessMetricRules.SUSPENDED_MEMORY_KEY to "83574 kB",
            ),
        )
        assertEquals(listOf("backgroundExitData.cumulativeMemoryResourceLimitExitCount"), crossed.map { it.key })
    }

    @Test
    fun `a counter the platform wrote unreadably is not a crossing`() {
        assertEquals(
            null,
            crossing(report(exit("foregroundExitData", "cumulativeAppWatchdogExitCount", 0).first to "many")),
        )
    }

    @Test
    fun `an absent counter and a zero counter reach the same verdict`() {
        // Platforms omit zero-valued counters from their serialization (measured), so the rule must
        // be correct under both readings — this pins that they agree.
        val absent = crossings(report())
        val zero = crossings(report(exit("foregroundExitData", "cumulativeAppWatchdogExitCount", 0)))
        assertEquals(absent, zero)
        assertEquals(emptyList(), zero)
    }

    @Test
    fun `unrecognised keys ride along without disturbing the verdict`() {
        val crossed = crossings(
            report("someFutureMetric.withA.nestedKey" to "whatever", "metaData.deviceType" to "iPhone12,8"),
        )
        assertEquals(emptyList(), crossed)
    }

    // ── Hangs ─────────────────────────────────────────────────────────────────────────────────────

    private fun hangBucket(index: Int, count: Int, end: String) = arrayOf(
        "${ProcessMetricRules.HANG_HISTOGRAM_PREFIX}.histogramValue.$index.bucketCount" to "$count",
        "${ProcessMetricRules.HANG_HISTOGRAM_PREFIX}.histogramValue.$index.bucketEnd" to end,
    )

    @Test
    fun `the measured routine hang band does not cross`() {
        // The real distribution from the device: six hangs, 260-749 ms. The ceiling is calibrated to
        // leave exactly this quiet.
        val crossed = crossings(
            report(
                *hangBucket(0, 1, "269 ms"),
                *hangBucket(1, 1, "279 ms"),
                *hangBucket(2, 1, "299 ms"),
                *hangBucket(3, 1, "519 ms"),
                *hangBucket(4, 1, "699 ms"),
                *hangBucket(5, 1, "749 ms"),
            ),
        )
        assertEquals(emptyList(), crossed)
    }

    @Test
    fun `a hang at or above the ceiling crosses - below it does not`() {
        assertTrue(crossing(report(*hangBucket(0, 1, "${ProcessMetricRules.HANG_CEILING_MS - 1} ms"))) == null)
        assertTrue(crossing(report(*hangBucket(0, 1, "${ProcessMetricRules.HANG_CEILING_MS} ms"))) != null)
        assertTrue(crossing(report(*hangBucket(0, 1, "${ProcessMetricRules.HANG_CEILING_MS + 1} ms"))) != null)
    }

    @Test
    fun `a bucket nothing landed in is not evidence`() {
        // A histogram declares buckets; only a non-zero count means a hang actually happened.
        assertTrue(crossing(report(*hangBucket(0, 0, "5000 ms"))) == null)
    }

    @Test
    fun `a bucket without a readable count is not evidence`() {
        val end = "${ProcessMetricRules.HANG_HISTOGRAM_PREFIX}.histogramValue.0.bucketEnd" to "5000 ms"
        assertTrue(crossing(report(end)) == null)
        val unreadable = "${ProcessMetricRules.HANG_HISTOGRAM_PREFIX}.histogramValue.0.bucketCount" to "some"
        assertTrue(crossing(report(end, unreadable)) == null)
    }

    @Test
    fun `seconds are understood as well as milliseconds`() {
        assertTrue(crossing(report(*hangBucket(0, 1, "3 sec"))) != null)
        assertTrue(crossing(report(*hangBucket(0, 1, "2 s"))) != null)
        assertTrue(crossing(report(*hangBucket(0, 1, "1.5 sec"))) != null)
        assertTrue(crossing(report(*hangBucket(0, 1, "0.5 sec"))) == null)
    }

    @Test
    fun `an unparseable duration does not testify either way`() {
        // It must not read as a small number and silently suppress a crossing.
        assertTrue(crossing(report(*hangBucket(0, 1, "9 furlongs"))) == null)
        assertTrue(crossing(report(*hangBucket(0, 1, "long"))) == null)
    }

    @Test
    fun `many crossing hang buckets still name one reason`() {
        val crossed = crossing(
            report(*hangBucket(0, 1, "2000 ms"), *hangBucket(1, 3, "4000 ms")),
        )
        assertEquals("appHangAtOrAbove${ProcessMetricRules.HANG_CEILING_MS}ms", crossed?.key)
    }

    @Test
    fun `a report with no hang data at all is quiet`() {
        assertTrue(crossing(report("metaData.osVersion" to "iPhone OS 26.6 (23G71)")) == null)
    }

    // ── The crossing's event ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a crossing's event names the build the report describes - not the build delivering it`() {
        // SNAPSYNC-61: a dev build's day, delivered by TestFlight 0.6 (2369) and filed under it.
        val report = report(
            exit("foregroundExitData", "cumulativeMemoryResourceLimitExitCount", 1),
            ProcessMetricRules.RELEASE_KEY to "0.4",
            ProcessMetricRules.BUILD_KEY to "1",
        )
        val event = ProcessMetricRules.crossingEvent(crossings(report).single(), report, "didReceiveMetricPayloads")
        assertEquals(
            "process metric crossed: foregroundExitData.cumulativeMemoryResourceLimitExitCount",
            event.message,
        )
        assertEquals(
            mapOf(
                CrashTags.FINGERPRINT to event.message,
                CrashTags.ENTRY_POINT to "didReceiveMetricPayloads",
                CrashTags.DESCRIBED_RELEASE to "0.4",
                CrashTags.DESCRIBED_BUILD to "1",
            ),
            event.tags,
        )
    }

    @Test
    fun `a report that names no build leaves its crossing's event the delivering build's`() {
        val report =
            report(exit("backgroundExitData", "cumulativeAppWatchdogExitCount", 1), ProcessMetricRules.BUILD_KEY to " ")
        val event = ProcessMetricRules.crossingEvent(crossings(report).single(), report, null)
        assertEquals(
            mapOf(CrashTags.FINGERPRINT to event.message),
            event.tags,
            "a blank build is no build, and no entry point is none",
        )
    }
}
