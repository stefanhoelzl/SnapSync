package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The facts the model derives: how a refusal the service sent is read, how many members an ended event waits for, and
 * whether a membership is encrypted.
 */
class ModelFactsTest {

    @Test
    fun `a refusal is read by its wire name and an absent or unknown one is an unverifiable phone`() {
        assertEquals(DeviceRefusal.DEVICE_MODIFIED, DeviceRefusal.fromWire("device-modified"))
        assertEquals(DeviceRefusal.APP_NOT_GENUINE, DeviceRefusal.fromWire(" app-not-genuine "))
        assertEquals(DeviceRefusal.DEVICE_UNVERIFIABLE, DeviceRefusal.fromWire("rooted"))
        assertEquals(DeviceRefusal.DEVICE_UNVERIFIABLE, DeviceRefusal.fromWire(null))
    }

    @Test
    fun `an ended event waits for its active members that have not settled never fewer than none`() {
        assertEquals(2, MemberCounts(active = 3, settled = 1).waitingFor)
        assertEquals(0, MemberCounts(active = 1, settled = 2).waitingFor)
    }

    @Test
    fun `a membership is encrypted by its key`() {
        val membership = EventConfig(
            eventId = "e",
            name = "Picnic",
            minPhotoDate = captureCutoff("2026-07-06T00:00:00Z"),
            endsAt = eventEnd("2026-07-07T00:00:00Z"),
            maxPhotoDate = captureCeiling("2026-07-07T00:00:00Z"),
            deletesAt = deletesAt("2026-08-05T00:00:00Z"),
        )

        assertFalse(membership.encrypted)
        assertTrue(membership.copy(keyId = "k1").encrypted)
    }
}

/** A build's store page (capability `app-update-required`): the address and the store, one value. */
class StoreLinkTest {

    @Test
    fun `a store page names its store`() {
        val play = StoreLink("https://play.google.com/store/apps/details?id=app.snapsync", StoreKind.GOOGLE_PLAY)
        assertEquals(StoreKind.GOOGLE_PLAY, play.kind)
        assertEquals(listOf(StoreKind.APP_STORE, StoreKind.GOOGLE_PLAY), StoreKind.entries)
    }
}
