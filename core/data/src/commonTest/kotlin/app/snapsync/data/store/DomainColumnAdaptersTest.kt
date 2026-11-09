package app.snapsync.data.store

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.event.EventId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An id column reads back what it stored, byte for byte, and a stored value that spells no id is read as what it held
 * — never a throw, and written back unchanged.
 */
class DomainColumnAdaptersTest {

    @Test
    fun `an id column stores the id as it is and reads it back`() {
        val event = Stored.Valid(EventId.parse(UPPER)!!)
        val device = Stored.Valid(DeviceId.parse(UPPER)!!)
        val asset = Stored.Valid(AssetId.parse("A")!!)
        assertEquals(UPPER, EventIdColumnAdapter.encode(event))
        assertEquals(event, EventIdColumnAdapter.decode(UPPER))
        assertEquals(UPPER, DeviceIdColumnAdapter.encode(device))
        assertEquals(device, DeviceIdColumnAdapter.decode(UPPER))
        assertEquals("A", AssetIdColumnAdapter.encode(asset))
        assertEquals(asset, AssetIdColumnAdapter.decode("A"))
    }

    @Test
    fun `the empty text a migration left is no id, and is written back as it was`() {
        assertEquals(Stored.Empty, EventIdColumnAdapter.decode(""))
        assertEquals(Stored.Empty, DeviceIdColumnAdapter.decode(""))
        assertEquals(Stored.Empty, AssetIdColumnAdapter.decode(""))
        assertEquals("", EventIdColumnAdapter.encode(Stored.Empty))
    }

    private companion object {
        const val UPPER = "5C33E0C1-4E39-4EE0-891F-BAFB943BC168"
    }
}
