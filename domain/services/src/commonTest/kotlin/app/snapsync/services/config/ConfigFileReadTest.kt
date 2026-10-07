package app.snapsync.services.config

import app.snapsync.model.ConfigRead
import app.snapsync.model.EventConfig
import app.snapsync.model.FileResult
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.deletesAt
import app.snapsync.model.encodeConfigFile
import app.snapsync.model.eventEnd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The file-backed three-state read (capability `join-event`; migration step 11a made the App-Group
 * file the storage of record): the pure `configReadViaFile` algorithm over one `Files` answer — the
 * file and **nothing else**. The read-only legacy-Keychain fallback that used to sit behind a
 * missing file was the whole installed base's update path under the migration's ship-at-once
 * model, and the Stage-2 change deleted it once that population was gone (both the fallback's own
 * step 11a and the finale are ancestors of `v0.1`, the first App Store release). So a missing file
 * is now **definitively not joined**, which is what makes a reinstall a leave.
 *
 * The branch that matters is unchanged from the Keychain era: *unreadable is not absent*, grounded
 * on file errors instead of `OSStatus`. It matters more now, not less — with the fallback gone,
 * nothing downstream catches a wrong not-found.
 */

/** Every membership carries a concrete capture-date ceiling (capability `join-event`). */
private val FIXTURE_CEILING = captureCeiling("2099-01-01T00:00:00Z")

class ConfigFileReadTest {

    private val config = EventConfig(
        eventId = "e1",
        name = "Party",
        minPhotoDate = captureCutoff("2026-07-01T00:00:00Z"),
        maxPhotoDate = FIXTURE_CEILING,
        endsAt = eventEnd("2099-12-31T00:00:00Z"),
        deletesAt = deletesAt("2099-12-31T00:00:00Z"),
    )

    private fun content(text: String) = FileResult.Ok(text.encodeToByteArray())

    @Test
    fun `a valid file answers Joined`() {
        val read = configReadViaFile(content(encodeConfigFile(config)))

        assertEquals(ConfigRead.Joined(config), read)
    }

    @Test
    fun `an unusable current-version file is Unavailable never None`() {
        // Same-version-but-undecodable is an UNEXPLAINED state (this app's own atomic writes
        // should make it unreachable), so it defers — the retired Keychain legacy-item rule
        // (undecodable reads as no config) deliberately never transferred to the file.
        val read = configReadViaFile(content("""{"v":1,"payload":{"eventId":"e1"}}"""))

        assertIs<ConfigRead.Unavailable>(read)
        assertTrue("unusable" in read.detail, read.detail)
    }

    @Test
    fun `a foreign file is Unavailable with its own detail — never None`() {
        val foreign = configReadViaFile(content("""{"v":99}"""))
        val unusable = configReadViaFile(content("""{"v":1,"payload":{"eventId":"e1"}}"""))

        assertIs<ConfigRead.Unavailable>(foreign)
        assertTrue("foreign" in foreign.detail, foreign.detail)
        assertNotEquals<ConfigRead>(unusable, foreign, "a device log tells a foreign file from an unusable one")
    }

    @Test
    fun `a file that is not UTF-8 is Unavailable`() {
        assertIs<ConfigRead.Unavailable>(configReadViaFile(FileResult.Ok(byteArrayOf(0xC3.toByte(), 0x28))))
    }

    // THE bug this seam exists to prevent, in its file clothing: a locked device's protected-file
    // read fails permission-class. Reported as None, the cycle reads it as a LEAVE and clears the
    // join marker — every locked wake, for ever.
    @Test
    fun `a denied read is Unavailable carrying the platform's code`() {
        val read = configReadViaFile(FileResult.Denied(detail = "NSFileReadNoPermissionError", code = 257))

        assertIs<ConfigRead.Unavailable>(read)
        assertTrue("257" in read.detail && "NSFileReadNoPermissionError" in read.detail, read.detail)
    }

    @Test
    fun `a failed read and an unavailable area are Unavailable never None`() {
        assertIs<ConfigRead.Unavailable>(configReadViaFile(FileResult.Failed(detail = "io", code = 5)))
        assertIs<ConfigRead.Unavailable>(configReadViaFile(FileResult.AreaUnavailable))
    }

    /**
     * The Stage-2 end state: a missing file is the leave, decided from one fact. Nothing else is
     * consulted — there is no longer anything else *to* consult, which is why the `Files` adapter's
     * not-found classification is now solely load-bearing (a wrong not-found is an uncaught logout,
     * not a caught one).
     */
    @Test
    fun `a missing file is None — definitively not joined`() {
        assertEquals(ConfigRead.None, configReadViaFile(FileResult.NotFound))
    }
}
