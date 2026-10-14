package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.Footprint
import app.snapsync.levers.MetricReportKind
import app.snapsync.levers.ReceivedEvent
import app.snapsync.levers.WorldEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the app makes of the iPhone's reports on its earlier runs (MetricKit's, played by the JVM host's mock): a run the
 * system ended, or a freeze of a second or more, reaches the operator as a report naming why — carrying the app's own
 * last memory readings and never the event's key — and a report with nothing that counts reaches no one. Observed where
 * the operator would see it: on what the crash reporter's backend received.
 */
class ProcessMetricReportIntegrationTest {

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "Automatic failure reports are minimal and anonymous",
        scenario = "An iPhone says an earlier run ended abnormally or froze",
    )
    fun a_run_the_system_ended_reaches_the_operator_with_the_app_s_last_memory_use() = behaviour {
        val key = given { encryptedEventInBackground() }

        lateinit var received: Seen<WorldEvent.EventReceived>
        whenever { processMetrics.deliver(MetricReportKind.ENDED_ABNORMALLY) }
        then {
            received = event<WorldEvent.EventReceived> { it.event.message.endsWith(MEMORY_LIMIT_EXIT) }
        }

        assertCarriesTheReadingNotTheKey(received.value.event, key)
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "Automatic failure reports are minimal and anonymous",
        scenario = "An iPhone says an earlier run ended abnormally or froze",
    )
    fun a_freeze_of_a_second_or_more_reaches_the_operator_with_the_app_s_last_memory_use() = behaviour {
        val key = given { encryptedEventInBackground() }

        lateinit var received: Seen<WorldEvent.EventReceived>
        whenever { processMetrics.deliver(MetricReportKind.FROZE) }
        then { received = event<WorldEvent.EventReceived> { it.event.message.endsWith(HANG) } }

        assertCarriesTheReadingNotTheKey(received.value.event, key)
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "Automatic failure reports are minimal and anonymous",
        scenario = "An iPhone's account with nothing to report",
    )
    fun a_report_with_nothing_that_counts_reaches_no_one() = behaviour {
        given { encryptedEventInBackground() }

        whenever { processMetrics.deliver(MetricReportKind.QUIET) }
        then { never<WorldEvent.EventReceived>() }
    }

    /**
     * Joined to an encrypted event, then sent to the background with a known footprint, which the app records as it
     * leaves. Answers the event's key, as its invite carries it.
     */
    private suspend fun Given.encryptedEventInBackground(): String {
        val event = joinedEvent()
        val key = inviteFor(event).substringAfter("#k=")
        step(
            "its footprint known, and sent to the background",
            act = {
                processInfo.footprint(Footprint(FOOTPRINT_BYTES, peakBytes = PEAK_BYTES))
                os.background()
            },
            until = { settled { true } },
        )
        return key
    }

    private fun assertCarriesTheReadingNotTheKey(event: ReceivedEvent, key: String) {
        val account = event.contexts["process_metrics"].orEmpty()
        val reading = account["ownFootprint.0"].orEmpty()
        assertTrue(
            reading.contains("entering background: footprint 64000 kB, peak 81000 kB"),
            "the app's last memory reading rides the report: $account",
        )
        assertEquals("2412", account["metaData.appBuildVersion"], "with the iPhone's account it explains: $account")
        assertTrue(key.length == KEY_LENGTH, "the invite carried a key: '$key'")
        assertFalse(key in event.toString(), "the event's key is nowhere in the report: $event")
    }

    private companion object {
        const val FOOTPRINT_BYTES = 64_000_000L
        const val PEAK_BYTES = 81_000_000L
        const val KEY_LENGTH = 43
        const val MEMORY_LIMIT_EXIT = "foregroundExitData.cumulativeMemoryResourceLimitExitCount"
        const val HANG = "appHangAtOrAbove1000ms"
    }
}
