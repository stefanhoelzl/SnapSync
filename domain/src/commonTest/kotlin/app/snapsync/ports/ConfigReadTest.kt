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
import kotlin.test.assertIs

/**
 * *Unreadable is not absent* (capability `event-link`). The extension takes "no config" to mean
 * **this device left the event** and clears its `joinedEventId` marker — so conflating the two turned
 * every locked-device wake into a false leave.
 */

/** Every membership carries a concrete capture-date ceiling (capability `join-event`). */
private val FIXTURE_CEILING = captureCeiling("2099-01-01T00:00:00Z")

class ConfigReadTest {

    private val config = EventConfig(
        eventId = "e1",
        name = "Party",
        minPhotoDate = captureCutoff("2026-07-01T00:00:00Z"), maxPhotoDate = FIXTURE_CEILING,
        direction = Direction.Both,
        saveToAlbum = true,
    )

    private fun decode(stored: String): EventConfig? = if (stored == "good") config else null

    @Test
    fun `a stored decodable item is Joined`() {
        val read = configReadFrom(KeychainRead.Found("good", "AfterFirstUnlock"), ::decode)

        assertEquals(ConfigRead.Joined(config), read)
    }

    @Test
    fun `an absent item is None`() {
        assertEquals(ConfigRead.None, configReadFrom(KeychainRead.Absent, ::decode))
    }

    // THE bug: a locked device answers Unavailable. Reported as None, the extension reads it as a
    // LEAVE and clears its join marker — every cycle, for ever.
    @Test
    fun `an unreadable item is Unavailable and never None`() {
        val read = configReadFrom(KeychainRead.Unavailable(-25308), ::decode)

        assertIs<ConfigRead.Unavailable>(read)
        assertEquals(-25308, read.status)
    }

    @Test
    fun `an undecodable legacy item is None not Unavailable`() {
        // A pre-cutoff item is genuinely unusable — the user must re-join. Retrying later cannot help,
        // so this is None (the safe outcome), not Unavailable ("try again when unlocked").
        assertEquals(ConfigRead.None, configReadFrom(KeychainRead.Found("legacy", "AfterFirstUnlock"), ::decode))
    }
}
