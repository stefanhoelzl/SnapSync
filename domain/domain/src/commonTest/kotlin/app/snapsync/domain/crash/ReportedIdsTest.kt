package app.snapsync.domain.crash

import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.EventId
import app.snapsync.domain.report.Fact
import kotlin.test.Test
import kotlin.test.assertEquals

/** The snapsync section: the joined event, the device and the process, in full. */
class ReportedIdsTest {

    private val event = EventId.parse("550e8400-e29b-41d4-a716-446655440000")!!
    private val device = DeviceId.parse("1E6AC700-0000-4000-8000-000000000001")!!

    @Test
    fun `a joined device reports its event, its id and its process`() {
        assertEquals(
            mapOf("event" to event.value, "device" to device.value, "process" to "app.snapsync"),
            ReportedIds(event, Fact.Known(device), "app.snapsync").section(),
        )
    }

    @Test
    fun `a device in no event says so, and an id it could not read says why`() {
        assertEquals(
            mapOf("event" to "none", "device" to "failed (no device id stored yet)", "process" to "ios"),
            ReportedIds(null, Fact.Failed("no device id stored yet"), "ios").section(),
        )
    }

    @Test
    fun `a device id the platform cannot have is left out`() {
        assertEquals(mapOf("event" to "none", "process" to "ios"), ReportedIds(null, Fact.Unsupported, "ios").section())
    }
}
