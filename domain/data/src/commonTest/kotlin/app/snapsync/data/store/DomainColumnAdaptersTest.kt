package app.snapsync.data.store

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.EventId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A typed column reads back what it stored, byte for byte, and a stored value that is not one of its type is refused
 * as the row is read — never handed on half-typed.
 */
class DomainColumnAdaptersTest {

    @Test
    fun `an id column stores the id as it is and reads it back`() {
        val event = EventId.parse(UPPER)!!
        val device = DeviceId.parse(UPPER)!!
        assertEquals(UPPER, EventIdColumnAdapter.encode(event))
        assertEquals(event, EventIdColumnAdapter.decode(UPPER))
        assertEquals(UPPER, DeviceIdColumnAdapter.encode(device))
        assertEquals(device, DeviceIdColumnAdapter.decode(UPPER))
        assertEquals("A", AssetIdColumnAdapter.encode(AssetIdColumnAdapter.decode("A")))
        assertEquals(AssetId("A"), AssetIdColumnAdapter.decode("A"))
    }

    @Test
    fun `a stored value that is no id is refused`() {
        assertFailsWith<IllegalArgumentException> { EventIdColumnAdapter.decode("E1") }
        assertFailsWith<IllegalArgumentException> { DeviceIdColumnAdapter.decode("D1") }
    }

    private companion object {
        const val UPPER = "5C33E0C1-4E39-4EE0-891F-BAFB943BC168"
    }
}
