package app.snapsync.domain.report

import kotlin.test.Test
import kotlin.test.assertEquals

/** The build's facts a report and the app menu read: its label, and where its reports go. */
class ReportFactsTest {

    @Test
    fun `an unknown build says so rather than naming a number nobody built`() {
        assertEquals(BuildLabel("unknown", "unknown"), BuildLabel.UNKNOWN)
    }

    @Test
    fun `a report goes to the developer or stays on this device, and nowhere else`() {
        assertEquals(listOf(ReportDestination.DEVELOPER, ReportDestination.THIS_DEVICE), ReportDestination.entries)
    }
}
