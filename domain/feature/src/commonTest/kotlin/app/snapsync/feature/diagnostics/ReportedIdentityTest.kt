package app.snapsync.feature.diagnostics

import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.model.Fact
import app.snapsync.testids.testDeviceId
import kotlin.test.Test
import kotlin.test.assertEquals

/** The bug report's reading of the device id (capability `privacy-security`). */
class ReportedIdentityTest {
    @Test
    fun `a resolved id is known and every reason there is none is failed`() {
        assertEquals(
            Fact.Known(testDeviceId("ID-1").value),
            DeviceIdRead.Ok(testDeviceId("ID-1"), DeviceIdRead.Via.READ).asFact(),
        )
        assertEquals(
            Fact.Failed("the secure store could not be read: locked"),
            DeviceIdRead.Unavailable("locked").asFact(),
        )
        assertEquals(Fact.Failed("no device id stored yet"), DeviceIdRead.Absent.asFact())
    }
}
