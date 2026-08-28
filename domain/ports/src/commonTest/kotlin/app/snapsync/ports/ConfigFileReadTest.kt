package app.snapsync.ports

import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.deletesAt
import app.snapsync.model.eventEnd
import app.snapsync.model.eventStart
import app.snapsync.model.EventConfig
import app.snapsync.model.encodeConfigFile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The file-backed three-state read (capability `event-link`; migration step 11a made the App-Group
 * file the storage of record): the pure `configReadViaFile` algorithm the iOS adapter runs — the
 * file and **nothing else**. The read-only legacy-Keychain fallback that used to sit behind a
 * missing file was the whole installed base's update path under the migration's ship-at-once
 * model, and the Stage-2 change deleted it once that population was gone (both the fallback's own
 * step 11a and the finale are ancestors of `v0.1`, the first App Store release). So a missing file
 * is now **definitively not joined**, which is what makes a reinstall a leave.
 *
 * The branch that matters is unchanged from the Keychain era: *unreadable is not absent*, grounded
 * on file errors instead of `OSStatus`. It matters more now, not less — with the fallback gone,
 * nothing downstream catches a wrong `Missing`.
 */

/** Every membership carries a concrete capture-date ceiling (capability `join-event`). */
private val FIXTURE_CEILING = captureCeiling("2099-01-01T00:00:00Z")

class ConfigFileReadTest {

    private val config = EventConfig(
        eventId = "e1",
        name = "Party",
        minPhotoDate = captureCutoff("2026-07-01T00:00:00Z"),
        maxPhotoDate = FIXTURE_CEILING,
    )

    @Test
    fun `a valid file answers Joined`() {
        val read = configReadViaFile(ConfigFileRead.Content(encodeConfigFile(config)))

        assertEquals(ConfigRead.Joined(config), read)
    }

    @Test
    fun `an unusable current-version file is Unavailable never None`() {
        // Same-version-but-undecodable is an UNEXPLAINED state (this adapter's own atomic writes
        // should make it unreachable), so it defers — the retired Keychain legacy-item rule
        // (undecodable reads as no config) deliberately never transferred to the file.
        val read = configReadViaFile(ConfigFileRead.Content("""{"v":1,"payload":{"eventId":"e1"}}"""))

        assertIs<ConfigRead.Unavailable>(read)
        assertEquals(CONFIG_FILE_UNUSABLE_STATUS, read.status)
    }

    @Test
    fun `a foreign file is Unavailable with the sentinel status — never None`() {
        val read = configReadViaFile(ConfigFileRead.Content("""{"v":99}"""))

        assertIs<ConfigRead.Unavailable>(read)
        assertEquals(CONFIG_FILE_FOREIGN_STATUS, read.status)
    }

    // THE bug this seam exists to prevent, in its file clothing: a locked device's protected-file
    // read fails permission-class. Reported as None, the cycle reads it as a LEAVE and clears the
    // join marker — every locked wake, for ever.
    @Test
    fun `a failed read is Unavailable with the platform status`() {
        val read = configReadViaFile(ConfigFileRead.Failed(status = 257, detail = "NSFileReadNoPermissionError"))

        assertIs<ConfigRead.Unavailable>(read)
        assertEquals(257, read.status)
    }

    /**
     * The Stage-2 end state: a missing file is the leave, decided from one fact. Nothing else is
     * consulted — there is no longer anything else *to* consult, which is why
     * `isConfigFileAbsence`'s classification is now solely load-bearing (a wrong `Missing` is an
     * uncaught logout, not a caught one).
     */
    @Test
    fun `a missing file is None — definitively not joined`() {
        assertEquals(ConfigRead.None, configReadViaFile(ConfigFileRead.Missing))
    }
}
