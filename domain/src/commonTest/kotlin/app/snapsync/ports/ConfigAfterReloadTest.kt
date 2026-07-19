package app.snapsync.ports

import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The trigger-time reload's merge rule (migration step 12): a conclusive read replaces the config
 * StateFlow value; an **unreadable** read retains the last good one. At the old cadence (reload only
 * from the unlock hook) an unreadable reload was unreachable; at trigger cadence a transient read
 * failure on a foreground entry would otherwise clear a good membership and flip the screen to the
 * setup gate — the same regression class the status counts' keep-last-good posture prevents.
 */
class ConfigAfterReloadTest {

    private val config = EventConfig(
        eventId = "e1",
        name = "Party",
        minPhotoDate = "2026-07-01T00:00:00Z",
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
}
