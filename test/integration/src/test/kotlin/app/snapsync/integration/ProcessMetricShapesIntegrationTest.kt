package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import kotlin.test.Test

/**
 * What the app makes of an iPhone's report on its earlier runs whatever shape the report takes — MetricKit's payload is
 * an open bag the app reads by shape, so a report may leave out a counter, the build it describes or its period, state
 * a tally as zero, or a quantity in a unit the app does not know. Each report here is handed to the app as the
 * platform's provider hands one (`host.processMetrics`), modelled on the recorded ones, and judged where the operator
 * would see it: on what the crash reporter's backend received.
 *
 * Memory: the iPhone reclaiming a small, suspended app is its own housekeeping, so a background memory-pressure exit
 * counts only when the report shows a suspended footprint above the app's ceiling — or cannot show it was small.
 */
@Verifies(
    spec = "privacy-security",
    requirement = "Automatic failure reports are minimal and anonymous",
    scenario = "An iPhone says an earlier run ended abnormally or froze",
)
class ProcessMetricShapesIntegrationTest {

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "Automatic failure reports are minimal and anonymous",
        scenario = "An iPhone's account with nothing to report",
    )
    fun a_small_suspended_app_the_iphone_reclaimed_for_memory_reaches_no_one() = behaviour {
        whenever { host.processMetrics(report(PRESSURE_EXIT to "2", SUSPENDED to "84920 kB")) }
        then { never<WorldEvent.EventReceived>() }
    }

    @Test
    fun a_suspended_app_the_iphone_reclaimed_while_it_held_more_than_its_ceiling_reaches_the_operator() = behaviour {
        whenever { host.processMetrics(report(PRESSURE_EXIT to "1", SUSPENDED to "160 MB")) }
        then { event<WorldEvent.EventReceived> { it.event.message.endsWith(PRESSURE_REASON) } }
    }

    @Test
    fun a_reclaimed_app_whose_report_states_no_suspended_footprint_reaches_the_operator() = behaviour {
        whenever { host.processMetrics(report(PRESSURE_EXIT to "1")) }
        then { event<WorldEvent.EventReceived> { it.event.message.endsWith(PRESSURE_REASON) } }
    }

    @Test
    fun a_reclaimed_app_whose_suspended_footprint_is_in_a_unit_the_app_does_not_know_reaches_the_operator() =
        behaviour {
            whenever { host.processMetrics(report(PRESSURE_EXIT to "1", SUSPENDED to "84920 KiB")) }
            then { event<WorldEvent.EventReceived> { it.event.message.endsWith(PRESSURE_REASON) } }
        }

    @Test
    fun a_reclaimed_app_whose_suspended_footprint_states_no_amount_reaches_the_operator() = behaviour {
        whenever { host.processMetrics(report(PRESSURE_EXIT to "1", SUSPENDED to "kB")) }
        then { event<WorldEvent.EventReceived> { it.event.message.endsWith(PRESSURE_REASON) } }
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "Automatic failure reports are minimal and anonymous",
        scenario = "An iPhone's account with nothing to report",
    )
    fun exit_tallies_of_zero_or_of_no_number_and_freezes_that_never_happened_reach_no_one() = behaviour {
        whenever {
            host.processMetrics(
                report(
                    "$EXIT.foregroundExitData.cumulativeMemoryResourceLimitExitCount" to "0",
                    "$EXIT.backgroundExitData.cumulativeCPUResourceLimitExitCount" to "none",
                    "$EXIT.backgroundExitData.cumulativeNormalAppExitCount" to "12",
                    // A bucket of two seconds that held no hang, and one whose count the report left out.
                    "$HANG.0.bucketCount" to "0",
                    "$HANG.0.bucketEnd" to "2009 ms",
                    "$HANG.1.bucketEnd" to "3009 ms",
                ),
            )
        }
        then { never<WorldEvent.EventReceived>() }
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "Automatic failure reports are minimal and anonymous",
        scenario = "An iPhone's account with nothing to report",
    )
    fun a_freeze_whose_length_is_in_a_unit_the_app_does_not_know_reaches_no_one() = behaviour {
        whenever { host.processMetrics(report("$HANG.0.bucketCount" to "1", "$HANG.0.bucketEnd" to "2 min")) }
        then { never<WorldEvent.EventReceived>() }
    }

    @Test
    fun a_freeze_measured_in_fractional_seconds_reaches_the_operator() = behaviour {
        whenever { host.processMetrics(report("$HANG.0.bucketCount" to "1", "$HANG.0.bucketEnd" to "1.5 sec")) }
        then { event<WorldEvent.EventReceived> { it.event.message.endsWith(HANG_REASON) } }
    }

    @Test
    fun a_run_ended_by_the_system_reaches_the_operator_from_a_report_naming_neither_its_build_nor_its_period() =
        behaviour {
            whenever {
                host.processMetrics(
                    mapOf(
                        WINDOW_BEGIN to "2026-09-01 00:00:00",
                        "$EXIT.foregroundExitData.cumulativeMemoryResourceLimitExitCount" to "1",
                    ),
                )
            }
            then { event<WorldEvent.EventReceived> { it.event.message.endsWith(MEMORY_LIMIT_REASON) } }
        }

    @Test
    fun a_run_ended_by_the_system_reaches_the_operator_from_a_report_whose_build_is_blank() = behaviour {
        whenever {
            host.processMetrics(
                mapOf(
                    WINDOW_END to "2026-09-02 00:00:00",
                    RELEASE to " ",
                    BUILD to "",
                    "$EXIT.foregroundExitData.cumulativeMemoryResourceLimitExitCount" to "1",
                ),
            )
        }
        then { event<WorldEvent.EventReceived> { it.event.message.endsWith(MEMORY_LIMIT_REASON) } }
    }

    @Test
    fun every_long_named_tally_a_future_platform_adds_reaches_the_operator_as_its_own_report() = behaviour {
        // Names far longer than a breadcrumb may carry, each starting a byte further on, so wherever a cut falls it
        // falls inside a character; and a period as long, from a provider that writes its dates in another script.
        val tallies = listOf("", "a", "ab").map { lead -> lead + LONG }
        whenever {
            host.processMetrics(
                report(*tallies.map { "$EXIT.$it" to "1" }.toTypedArray()) + (WINDOW_BEGIN to LONG),
            )
        }
        then {
            tallies.forEach { tally -> event<WorldEvent.EventReceived> { it.event.message.endsWith(tally) } }
        }
    }

    /**
     * A daily report as the iPhone sends one — its period, the build it describes and the phone — carrying [fields]
     * besides.
     */
    private fun report(vararg fields: Pair<String, String>): Map<String, String> = linkedMapOf(
        WINDOW_BEGIN to "2026-09-01 00:00:00",
        WINDOW_END to "2026-09-02 00:00:00",
        RELEASE to "0.6",
        BUILD to "2412",
        "metaData.deviceType" to "iPhone14,6",
        "metaData.osVersion" to "iPhone OS 26.5.1 (23F80)",
        "memoryMetrics.peakMemoryUsage" to "91669 kB",
    ) + fields

    private companion object {
        const val WINDOW_BEGIN = "timeStampBegin"
        const val WINDOW_END = "timeStampEnd"
        const val RELEASE = "appVersion"
        const val BUILD = "metaData.appBuildVersion"
        const val EXIT = "applicationExitMetrics"
        const val HANG = "applicationResponsivenessMetrics.histogrammedAppHangTime.histogramValue"
        const val SUSPENDED = "memoryMetrics.averageSuspendedMemory.averageValue"
        const val PRESSURE_REASON = "backgroundExitData.cumulativeMemoryPressureExitCount"
        const val PRESSURE_EXIT = "$EXIT.$PRESSURE_REASON"
        const val MEMORY_LIMIT_REASON = "foregroundExitData.cumulativeMemoryResourceLimitExitCount"
        const val HANG_REASON = "appHangAtOrAbove1000ms"

        /** 250 three-byte characters: 750 bytes, past a breadcrumb's 512. */
        val LONG = "€".repeat(250)
    }
}
