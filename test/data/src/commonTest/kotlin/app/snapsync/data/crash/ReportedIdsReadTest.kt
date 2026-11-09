package app.snapsync.data.crash

import app.snapsync.application.membership.Device
import app.snapsync.application.membership.FixedIdentity
import app.snapsync.data.identity.DeviceIdRead
import app.snapsync.data.identity.DeviceIdentity
import app.snapsync.data.membership.EVENT
import app.snapsync.data.membership.MembershipRepository
import app.snapsync.data.membership.enrolled
import app.snapsync.data.store.SharedDatabase
import app.snapsync.mock.BuildInfoMock
import app.snapsync.mock.DatabasesMock
import kotlin.test.Test
import kotlin.test.assertEquals

/** The ids a report carries, read now: the joined event, the device as it stands — never minted — and the process. */
class ReportedIdsReadTest {

    private val device = Device()

    private fun read(identity: DeviceIdentity = device.identity, processId: String? = "app.snapsync") =
        ReportedIdsRead(device.membership, identity, BuildInfoMock(processId = processId).port()).now().section()

    @Test
    fun `a joined device names its event, its id and its process`() {
        device.membership.enter(enrolled())
        assertEquals(
            mapOf(
                "event" to EVENT,
                "device" to app.snapsync.application.membership.DEVICE_ID,
                "process" to "app.snapsync",
            ),
            read(),
        )
    }

    @Test
    fun `a device in no event says none, and a process with no id is named by its platform`() {
        assertEquals("none", read()["event"])
        assertEquals("ios", read(processId = null)["process"])
    }

    @Test
    fun `a membership that cannot be read names no event`() {
        val unreadable = MembershipRepository(SharedDatabase(DatabasesMock().port(), legacy = null))
        val section = ReportedIdsRead(unreadable, device.identity, BuildInfoMock().port()).now().section()
        assertEquals("none", section["event"])
    }

    @Test
    fun `an id that cannot be read says why, and none stored says so`() {
        assertEquals(
            "failed (the secure store could not be read: locked)",
            read(FixedIdentity(DeviceIdRead.Unavailable("locked")))["device"],
        )
        assertEquals("failed (no device id stored yet)", read(FixedIdentity(DeviceIdRead.Absent))["device"])
    }
}
