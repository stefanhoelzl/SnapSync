package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.domain.report.DiagnosticKeys
import app.snapsync.levers.Footprint
import app.snapsync.levers.MetricReportKind
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.UiIntent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * **What a report says of a reading the phone could not give** (capability `privacy-security`), read off what the
 * crash reporter's backend received: a reading the platform does not offer is left out, one not yet made is written
 * failed — never as a zero — and a memory reading carries only the figures the platform stated.
 */
class ReportReadingsIntegrationTest {

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A detailed bug report leaves the phone only when the user sends one",
    )
    fun a_report_from_a_phone_that_states_no_memory_footprint_leaves_it_out() = behaviour {
        given {
            joinedEvent()
            step("the platform states no footprint", act = { processInfo.footprint(null) }, until = {})
        }

        lateinit var received: Seen<WorldEvent.DumpReceived>
        whenever { tap(UiIntent.SendDiagnostics(NOTE, "Joined")) }
        then { received = event<WorldEvent.DumpReceived>() }

        assertFalse(DiagnosticKeys.MEMORY_FOOTPRINT_MB in received.value.dump.state, "${received.value.dump.state}")
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "A detailed bug report leaves the phone only when the user sends one",
        scenario = "A member with limited photo access sends a report",
    )
    fun a_report_from_a_limited_member_whose_selection_was_never_read_says_so_rather_than_zero() = behaviour {
        given {
            photoAccess(PhotoGrant.LIMITED)
            joinedEvent()
        }

        lateinit var received: Seen<WorldEvent.DumpReceived>
        whenever { tap(UiIntent.SendDiagnostics(NOTE, "Joined")) }
        then { received = event<WorldEvent.DumpReceived>() }

        assertEquals(
            "failed (the selection has not been read yet)",
            received.value.dump.state[DiagnosticKeys.SELECTION_PHOTOS],
        )
    }

    @Test
    @Verifies(
        spec = "privacy-security",
        requirement = "Automatic failure reports are minimal and anonymous",
        scenario = "An iPhone says an earlier run ended abnormally or froze",
    )
    fun a_memory_reading_without_a_peak_reaches_the_operator_without_one() = behaviour {
        given {
            joinedEvent()
            step("its footprint known without a peak, and sent to the background", act = {
                processInfo.footprint(Footprint(FOOTPRINT_BYTES))
                os.background()
            }, until = { settled { true } })
        }

        lateinit var received: Seen<WorldEvent.EventReceived>
        whenever { processMetrics.deliver(MetricReportKind.ENDED_ABNORMALLY) }
        then { received = event<WorldEvent.EventReceived>() }

        val reading = received.value.event.contexts["process_metrics"].orEmpty()["ownFootprint.0"].orEmpty()
        assertTrue(reading.endsWith("entering background: footprint 64000 kB"), reading)
    }

    private companion object {
        const val NOTE = "photos stopped arriving"
        const val FOOTPRINT_BYTES = 64_000_000L
    }
}
