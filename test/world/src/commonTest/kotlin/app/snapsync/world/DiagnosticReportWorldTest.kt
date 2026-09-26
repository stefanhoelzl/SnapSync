package app.snapsync.world

import app.snapsync.model.ReportDestination
import app.snapsync.model.SAVED_DIAGNOSTIC_REPORT_PATH
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bug report over the real composition (capability `privacy-security`): a build that reports nowhere keeps the
 * report on the device and sends nothing; a distributed build sends it and keeps nothing.
 */
class DiagnosticReportWorldTest {

    @Test
    fun a_build_that_reports_nowhere_keeps_the_report_on_the_device_and_sends_nothing() = worldTest {
        val w = World(this, dsn = null)
        assertEquals(ReportDestination.THIS_DEVICE, w.process.reportDestination)

        w.userCommands.sendDiagnostics("photos stopped arriving", "Joined")

        val saved = w.privateFiles[SAVED_DIAGNOSTIC_REPORT_PATH]?.decodeToString()
        assertTrue(saved != null && "photos stopped arriving" in saved, "the report is kept on the device: $saved")
        assertTrue(w.diagnosticsSent.value.isEmpty(), "and nothing left the phone")
        assertTrue(!w.diagnosticsStarted.value, "and no reporting channel was ever started")
    }

    @Test
    fun a_distributed_build_sends_the_report_and_keeps_nothing() = worldTest {
        val w = World(this)
        assertEquals(ReportDestination.DEVELOPER, w.process.reportDestination)

        w.userCommands.sendDiagnostics("photos stopped arriving", "Joined")

        assertEquals(1, w.diagnosticsSent.value.size)
        assertTrue(SAVED_DIAGNOSTIC_REPORT_PATH !in w.privateFiles)
    }
}
