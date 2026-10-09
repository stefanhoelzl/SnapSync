package app.snapsync.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The facts the screen reads off the reduced state rather than deciding for itself: how a refusal the service sent
 * becomes a message, which messages offer a report, what the network notice says, and the joined layer's derived
 * flags.
 */
class ScreenFactsTest {

    @Test
    fun `a refusal is read by its wire name and an absent or unknown one is an unverifiable phone`() {
        assertEquals(DeviceRefusal.DEVICE_MODIFIED, DeviceRefusal.fromWire("device-modified"))
        assertEquals(DeviceRefusal.APP_NOT_GENUINE, DeviceRefusal.fromWire(" app-not-genuine "))
        assertEquals(DeviceRefusal.DEVICE_UNVERIFIABLE, DeviceRefusal.fromWire("rooted"))
        assertEquals(DeviceRefusal.DEVICE_UNVERIFIABLE, DeviceRefusal.fromWire(null))
    }

    @Test
    fun `each refusal has its own message and only the unverifiable phone offers a report`() {
        assertEquals(
            listOf(ScreenMessage.DEVICE_MODIFIED, ScreenMessage.DEVICE_UNVERIFIABLE, ScreenMessage.APP_NOT_GENUINE),
            DeviceRefusal.entries.map(ScreenMessage::of),
        )
        assertEquals(listOf(ScreenMessage.DEVICE_UNVERIFIABLE), ScreenMessage.entries.filter { it.offersReport })
    }

    @Test
    fun `the network notice says nothing while online restricted or not`() {
        assertNull(NetworkNotice.of(NetworkAccess.Online(restricted = false)))
        assertNull(NetworkNotice.of(NetworkAccess.Online(restricted = true)))
        assertEquals(NetworkNotice.OFFLINE, NetworkNotice.of(NetworkAccess.Offline))
        assertEquals(NetworkNotice.BLOCKED, NetworkNotice.of(NetworkAccess.Blocked))
    }

    @Test
    fun `an ended event waits for its active members that have not settled never fewer than none`() {
        assertEquals(2, MemberCounts(active = 3, settled = 1).waitingFor)
        assertEquals(0, MemberCounts(active = 1, settled = 2).waitingFor)
    }

    @Test
    fun `the joined layer has ended only once its timing says so and a membership is encrypted by its key`() {
        val membership = EventConfig(
            eventId = "e",
            name = "Picnic",
            minPhotoDate = captureCutoff("2026-07-06T00:00:00Z"),
            endsAt = eventEnd("2026-07-07T00:00:00Z"),
            maxPhotoDate = captureCeiling("2026-07-07T00:00:00Z"),
            deletesAt = deletesAt("2026-08-05T00:00:00Z"),
        )
        val joined = Layer.Joined(
            JoinedEvent(
                eventId = "e",
                name = "Picnic",
                startsAt = LocalDateTime(2026, 7, 6, 0, 0),
                endsAt = LocalDateTime(2026, 7, 7, 0, 0),
                sharesFrom = LocalDateTime(2026, 7, 6, 0, 0),
                sharesUntil = LocalDateTime(2026, 7, 7, 0, 0),
                shares = true,
                receives = true,
                savesToAlbum = false,
            ),
            "https://snapsync.app/e",
            SyncHealth.InSync,
            today = LocalDate(2026, 7, 6),
        )

        assertFalse(joined.ended)
        assertTrue(joined.copy(timing = EventTiming.Ended).ended)
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
