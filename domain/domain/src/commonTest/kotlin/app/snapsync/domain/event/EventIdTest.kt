package app.snapsync.domain.event

import app.snapsync.domain.device.DeviceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EventIdTest {

    @Test
    fun `an event id is a canonical UUID — kept exactly as it arrived`() {
        for (text in listOf(LOWER, UPPER)) {
            val id = EventId.parse(text)
            assertEquals(text, id?.value)
            assertEquals(text, id.toString())
        }
    }

    @Test
    fun `anything else is no event id`() {
        for (text in NOT_UUIDS) assertNull(EventId.parse(text), "'$text'")
    }

    @Test
    fun `a device id follows the same rule — in either case`() {
        for (text in listOf(LOWER, UPPER)) {
            val id = DeviceId.parse(text)
            assertEquals(text, id?.value)
            assertEquals(text, id.toString())
        }
        for (text in NOT_UUIDS) assertNull(DeviceId.parse(text), "'$text'")
    }

    private companion object {
        const val LOWER = "7a3f9c21-0000-4000-8000-000000000001"
        const val UPPER = "5C33E0C1-4E39-4EE0-891F-BAFB943BC168"
        val NOT_UUIDS = listOf(
            "", "E1", "EVENT-1", "7a3f9c21000040008000000000000001", "7a3f9c21-0000-4000-8000-00000000000",
            "7a3f9c21-0000-4000-8000-0000000000012", "g a3f9c21-0000-4000-8000-000000000001",
            " 7a3f9c21-0000-4000-8000-000000000001", "7a3f9c21-0000-4000-8000-000000000001\n",
        )
    }
}
