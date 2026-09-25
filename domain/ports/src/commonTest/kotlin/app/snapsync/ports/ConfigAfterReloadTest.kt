package app.snapsync.ports

import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.deletesAt
import app.snapsync.model.eventEnd
import app.snapsync.model.eventStart
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import app.snapsync.model.ConfigRead
import app.snapsync.model.MembershipRead

/**
 * The trigger-time reload's merge rule (migration step 12): a conclusive read replaces the config
 * StateFlow value; an **unreadable** read retains the last good one. At the old cadence (reload only
 * from the unlock hook) an unreadable reload was unreachable; at trigger cadence a transient read
 * failure on a foreground entry would otherwise clear a good membership and flip the screen to the
 * setup gate — the same regression class the status counts' keep-last-good posture prevents.
 */

/** Every membership carries a concrete capture-date ceiling (capability `join-event`). */
private val FIXTURE_CEILING = captureCeiling("2099-01-01T00:00:00Z")

class ConfigAfterReloadTest {

    private val config = EventConfig(
        eventId = "e1",
        name = "Party",
        minPhotoDate = captureCutoff("2026-07-01T00:00:00Z"), maxPhotoDate = FIXTURE_CEILING,
        direction = Direction.Both,
        saveToAlbum = true,
    )

    @Test
    fun `a joined read replaces the value`() {
        assertEquals(config, configAfterReload(ConfigRead.Joined(config), current = null))
    }

    @Test
    fun `a none read clears the value`() {
        // Definitively not joined — e.g. the other process's leave landed; the UI must fall to setup.
        assertNull(configAfterReload(ConfigRead.None, current = config))
    }

    @Test
    fun `an unreadable read retains the last good value`() {
        assertEquals(config, configAfterReload(ConfigRead.Unavailable(status = -1), current = config))
    }

    @Test
    fun `an unreadable read on an empty flow stays empty`() {
        assertNull(configAfterReload(ConfigRead.Unavailable(status = 257), current = null))
    }

    // ---- the three-valued membership (decision record `harden-seam-bug-classes`, D11) ----

    @Test
    fun `a conclusive read replaces the membership`() {
        assertEquals(MembershipRead.Member(config), membershipAfterReload(ConfigRead.Joined(config), MembershipRead.Unreadable))
        assertEquals(MembershipRead.NotMember, membershipAfterReload(ConfigRead.None, MembershipRead.Member(config)))
    }

    @Test
    fun `an unreadable read keeps the last conclusive membership and is unreadable only without one`() {
        val unreadable = ConfigRead.Unavailable(status = -1)
        assertEquals(MembershipRead.Member(config), membershipAfterReload(unreadable, MembershipRead.Member(config)))
        assertEquals(MembershipRead.NotMember, membershipAfterReload(unreadable, MembershipRead.NotMember))
        assertEquals(MembershipRead.Unreadable, membershipAfterReload(unreadable, MembershipRead.Unreadable))
    }
}
