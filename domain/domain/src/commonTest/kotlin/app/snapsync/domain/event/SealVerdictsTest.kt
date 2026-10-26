package app.snapsync.domain.event

import app.snapsync.domain.device.DeviceId
import app.snapsync.domain.membership.Membership
import app.snapsync.domain.membership.Share
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.CaptureRange
import app.snapsync.domain.sharing.CaptureRangeVerdict
import app.snapsync.domain.sharing.Direction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * How one upload goes up and whether one download opens (capability `invite-link`, "Only the whole invite opens an
 * event's photos"): never plaintext into an encrypted event, never sealed for another event than the one the bytes go
 * to, and only the joined event's key opens what was fetched for it.
 */
@OptIn(MakesEventKey::class, MintsIdentifiedKey::class)
class SealVerdictsTest {

    private val id = EventKeyId("0011223344556677")
    private val key = IdentifiedKey(EventKey.of(ByteArray(EventKey.LENGTH) { 1 })!!, id)
    private val device = DeviceId.parse("7c9e6679-7425-40de-944b-e07fc1f90ae7")!!
    private val other = EventId.parse("1c9e6679-7425-40de-944b-e07fc1f90ae7")!!

    private val share = Share(
        assertIs<CaptureRangeVerdict.Ok>(
            CaptureRange.of(
                CaptureCutoff(anEvent().range.start.at),
                CaptureCeiling(anEvent().range.end.at),
                anEvent().range.start,
                anEvent().range.end,
            ),
        ).range,
        Direction.Both,
        saveToAlbum = false,
    )
    private val encrypted = Membership.Active(anEvent().copy(encryption = Encryption.Encrypted(id)), share)
    private val plain = Membership.Active(anEvent(), share)
    private val event = anEvent().id

    @Test
    fun `an encrypted event's upload is sealed under its key, as this device`() {
        val sealed = assertIs<SealVerdict.Sealed>(SealVerdict.of(encrypted, event, KeyPresence.Held(key), device))
        assertSame(key, sealed.key)
        assertEquals(device, sealed.device)
        assertEquals(
            SealVerdict.Plain,
            SealVerdict.of(plain, event, KeyPresence.NotNeeded, null),
            "a plain one's as it is",
        )
    }

    @Test
    fun `nothing goes up without the key, the device, or the membership of the event the bytes go to`() {
        fun because(verdict: SealVerdict) = assertIs<SealVerdict.Withheld>(verdict).because
        assertEquals(
            SealVerdict.Withheld.Because.KEY_UNREADABLE,
            because(SealVerdict.of(encrypted, event, KeyPresence.Unknown, device)),
        )
        assertEquals(
            SealVerdict.Withheld.Because.KEY_LOST,
            because(SealVerdict.of(encrypted, event, KeyPresence.Lost, device)),
        )
        assertEquals(
            SealVerdict.Withheld.Because.KEY_LOST,
            because(SealVerdict.of(encrypted, event, KeyPresence.NotNeeded, device)),
        )
        assertEquals(
            SealVerdict.Withheld.Because.DEVICE_UNKNOWN,
            because(SealVerdict.of(encrypted, event, KeyPresence.Held(key), null)),
        )
        assertEquals(
            SealVerdict.Withheld.Because.NOT_JOINED,
            because(SealVerdict.of(null, event, KeyPresence.NotNeeded, device)),
            "a leave mid-cycle",
        )
        assertEquals(
            SealVerdict.Withheld.Because.NOT_JOINED,
            because(SealVerdict.of(encrypted, other, KeyPresence.Held(key), device)),
            "a cycle that outlived its membership: a switch to another event",
        )
        assertEquals(
            SealVerdict.Withheld.Because.NOT_JOINED,
            because(SealVerdict.of(plain, other, KeyPresence.NotNeeded, device)),
            "never plaintext to an event left behind, whatever the new one is",
        )
    }

    @Test
    fun `a download opens only with the joined event's key, for that event`() {
        val opened = assertIs<OpenVerdict.OpenWith>(OpenVerdict.of(encrypted, event, KeyPresence.Held(key)))
        assertSame(key, opened.key)
        assertEquals(event, opened.event)
        assertIs<OpenVerdict.OpenWith>(
            OpenVerdict.of(encrypted, null, KeyPresence.Held(key)),
            "fetched before transfers named their event",
        )
        assertEquals(OpenVerdict.NotJoined, OpenVerdict.of(null, event, KeyPresence.NotNeeded))
        assertEquals(OpenVerdict.OtherEvent, OpenVerdict.of(encrypted, other, KeyPresence.Held(key)))
        assertEquals(OpenVerdict.KeyUnreadable, OpenVerdict.of(encrypted, event, KeyPresence.Unknown))
        assertEquals(OpenVerdict.NoKey, OpenVerdict.of(encrypted, event, KeyPresence.Lost))
        assertEquals(OpenVerdict.NoKey, OpenVerdict.of(plain, event, KeyPresence.NotNeeded))
    }

    @Test
    fun `a file that never opens is reported once, on its fourth failure in a row`() {
        val counted = generateSequence(OpenFailures.NONE) { it.next() }.take(7).toList()
        assertEquals((0..6).toList(), counted.map { it.count })
        assertEquals(
            listOf(OpenFailures.Report.ONCE),
            counted.map { it.report }.filter { it == OpenFailures.Report.ONCE },
        )
        assertEquals(OpenFailures.Report.ONCE, counted[OpenFailures.REPORT_AFTER].report)
    }
}
