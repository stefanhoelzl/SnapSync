package app.snapsync.model

import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.deletesAt
import app.snapsync.model.eventEnd
import app.snapsync.model.eventStart
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The versioned config-file envelope (capability `event-link`, migration step 11a). The stakes: the
 * file read decides "this device left the event", so every text this build cannot **positively**
 * interpret must land on the unreadable side ([ConfigFileDecode.Foreign]) — a future build's file,
 * or corruption, must never read as a leave on a revert build.
 */

/** Every membership carries a concrete capture-date ceiling (capability `join-event`). */
private val FIXTURE_CEILING = captureCeiling("2099-01-01T00:00:00Z")

class ConfigFileTest {

    private val config = EventConfig(
        eventId = "e1",
        name = "Party",
        minPhotoDate = captureCutoff("2026-07-01T00:00:00Z"), maxPhotoDate = FIXTURE_CEILING,
        startsAt = eventStart("2026-06-30T00:00:00Z"),
        direction = Direction.UploadOnly,
        saveToAlbum = true,
    )

    @Test
    fun `encode-decode round-trips every field`() {
        val decoded = decodeConfigFile(encodeConfigFile(config))

        assertEquals(ConfigFileDecode.Valid(config), decoded)
    }

    @Test
    fun `defaulted fields round-trip through an omitting encode`() {
        // encodeDefaults is off (matching the Keychain item's serialization posture), so a config at
        // defaults must still decode to the same values via the payload's own defaults.
        val minimal = EventConfig(eventId = "e2", minPhotoDate = captureCutoff("2026-07-01T00:00:00Z"), maxPhotoDate = FIXTURE_CEILING)

        assertEquals(ConfigFileDecode.Valid(minimal), decodeConfigFile(encodeConfigFile(minimal)))
    }

    @Test
    fun `a future envelope version is Foreign — never Unusable`() {
        val decoded = decodeConfigFile("""{"v":2,"payload":{"something":"newer"}}""")

        assertIs<ConfigFileDecode.Foreign>(decoded)
    }

    @Test
    fun `a version this build never wrote is Foreign`() {
        assertIs<ConfigFileDecode.Foreign>(decodeConfigFile("""{"v":0,"payload":{}}"""))
    }

    @Test
    fun `text that is not an envelope is Foreign — never a crash`() {
        assertIs<ConfigFileDecode.Foreign>(decodeConfigFile("not json at all"))
        assertIs<ConfigFileDecode.Foreign>(decodeConfigFile(""))
        // A bare EventConfig (no envelope) is also Foreign: it lacks `v`, which has no default.
        assertIs<ConfigFileDecode.Foreign>(decodeConfigFile(encodeConfigFile(config).substringAfter("\"payload\":").dropLast(1)))
    }

    @Test
    fun `a current-version payload without a cutoff is Unusable`() {
        // No minPhotoDate: no default is substituted (an empty cutoff would admit the whole
        // library). The port mapping treats Unusable as UNREADABLE — this adapter's own atomic
        // writes should make the state unreachable, so it is unexplained and must defer, never
        // drive a leave. (The Keychain legacy-item None rule deliberately does not transfer.)
        val decoded = decodeConfigFile("""{"v":1,"payload":{"eventId":"e1","name":"Party"}}""")

        assertEquals(ConfigFileDecode.Unusable, decoded)
    }

    @Test
    fun `a current-version envelope without a payload is Unusable`() {
        assertEquals(ConfigFileDecode.Unusable, decodeConfigFile("""{"v":1}"""))
    }

    @Test
    fun `unknown keys are ignored on envelope and payload — additive change needs no version bump`() {
        val decoded = decodeConfigFile(
            """{"v":1,"extra":"ignored","payload":{"eventId":"e1","minPhotoDate":"2026-07-01T00:00:00Z","maxPhotoDate":"2099-01-01T00:00:00Z","novel":"ignored"}}""",
        )

        assertEquals(
            ConfigFileDecode.Valid(
                EventConfig(
                    eventId = "e1",
                    minPhotoDate = captureCutoff("2026-07-01T00:00:00Z"),
                    maxPhotoDate = FIXTURE_CEILING,
                ),
            ),
            decoded,
        )
    }

    // ---- the ⑥ classifier: absent is the not-found error class ONLY ----

    @Test
    fun `not-found errors are the only absence`() {
        assertTrue(isConfigFileAbsence("NSCocoaErrorDomain", 260)) // NSFileReadNoSuchFileError
        assertTrue(isConfigFileAbsence("NSCocoaErrorDomain", 4)) // NSFileNoSuchFileError (delete path)
        assertTrue(isConfigFileAbsence("NSPOSIXErrorDomain", 2)) // ENOENT
    }

    @Test
    fun `a pre-first-unlock protected read is NOT absence`() {
        // Apple's data-protection contract: a protected file read before first unlock fails
        // permission-class (NSFileReadNoPermissionError 257 / EPERM 1) — mapping it to absence
        // would turn every locked background wake into a false leave.
        assertFalse(isConfigFileAbsence("NSCocoaErrorDomain", 257))
        assertFalse(isConfigFileAbsence("NSPOSIXErrorDomain", 1))
    }

    @Test
    fun `any unknown error stays on the unreadable side`() {
        assertFalse(isConfigFileAbsence("NSCocoaErrorDomain", 256)) // NSFileReadUnknownError
        assertFalse(isConfigFileAbsence("SomeOtherDomain", 260)) // the code alone is not enough
        assertFalse(isConfigFileAbsence(null, 2))
    }
}
