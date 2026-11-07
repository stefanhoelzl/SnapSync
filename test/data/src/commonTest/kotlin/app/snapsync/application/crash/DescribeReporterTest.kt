@file:OptIn(EntryPointCall::class)

package app.snapsync.application.crash

import app.snapsync.application.EntryPointCall
import app.snapsync.application.membership.Device
import app.snapsync.data.crash.CrashChannel
import app.snapsync.data.crash.RecordingReporter
import app.snapsync.data.crash.ReportedIdsRead
import app.snapsync.data.membership.EVENT
import app.snapsync.data.membership.enrolled
import app.snapsync.domain.crash.CrashSections
import app.snapsync.domain.crash.CrashShaping
import app.snapsync.mock.BuildInfoMock
import app.snapsync.mock.ThreadLabelMock
import kotlin.test.Test
import kotlin.test.assertEquals

/** Every report names the event and the device it is about, as they stand when the reporter is described. */
class DescribeReporterTest {

    @Test
    fun `the section follows the membership`() {
        val device = Device()
        val reporter = RecordingReporter()
        val build = BuildInfoMock(dsn = "https://key@ingest/1").port()
        val describe = DescribeReporter(
            ReportedIdsRead(device.membership, device.identity, build),
            CrashChannel(reporter, build, ThreadLabelMock(), CrashShaping.STANDARD),
        )
        describe()
        assertEquals("none", reporter.contexts.getValue(CrashSections.SNAPSYNC)["event"])
        device.membership.enter(enrolled())
        describe()
        assertEquals(EVENT, reporter.contexts.getValue(CrashSections.SNAPSYNC)["event"])
    }
}
