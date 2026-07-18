package app.snapsync.ports

import app.snapsync.model.EventConfig
import app.snapsync.model.encodeConfigFile

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The file-backed three-state read (capability `event-link`, migration step 11a): the pure
 * `configReadViaFile` algorithm the iOS adapter runs — file first, Keychain fallback **only** on a
 * definitively missing file, with a found fallback migrated forward into the file. The branch that
 * matters is unchanged from the Keychain era: *unreadable is not absent*, now grounded on file
 * errors instead of `OSStatus`.
 */
class ConfigFileReadTest {

    private val config = EventConfig(
        eventId = "e1",
        name = "Party",
        minPhotoDate = "2026-07-01T00:00:00Z",
    )

    private fun noFallback(): ConfigRead = throw AssertionError("fallback must not be consulted")

    private fun noMigrate(config: EventConfig): Unit = throw AssertionError("migrate must not run")

    private fun noRepair(read: ConfigRead): Unit = throw AssertionError("repair must not run")

    @Test
    fun `a valid file answers Joined without consulting the Keychain`() {
        val read = configReadViaFile(
            ConfigFileRead.Content(encodeConfigFile(config)),
            fallback = ::noFallback,
            migrate = ::noMigrate,
            repair = ::noRepair,
        )

        assertEquals(ConfigRead.Joined(config), read)
    }

    @Test
    fun `an unusable current-version file is Unavailable, never None — no fallback`() {
        // Same-version-but-undecodable is an UNEXPLAINED state (this adapter's own atomic writes
        // should make it unreachable), so it defers — the Keychain legacy-item rule (undecodable
        // reads as no config) deliberately does not transfer to the file. The Keychain is NOT
        // consulted: the file is the storage of record, and it answered.
        val read = configReadViaFile(
            ConfigFileRead.Content("""{"v":1,"payload":{"eventId":"e1"}}"""),
            fallback = ::noFallback,
            migrate = ::noMigrate,
            repair = ::noRepair,
        )

        assertIs<ConfigRead.Unavailable>(read)
        assertEquals(CONFIG_FILE_UNUSABLE_STATUS, read.status)
    }

    @Test
    fun `a foreign file is Unavailable with the sentinel status, never None`() {
        val read = configReadViaFile(
            ConfigFileRead.Content("""{"v":99}"""),
            fallback = ::noFallback,
            migrate = ::noMigrate,
            repair = ::noRepair,
        )

        assertIs<ConfigRead.Unavailable>(read)
        assertEquals(CONFIG_FILE_FOREIGN_STATUS, read.status)
    }

    // THE bug this seam exists to prevent, in its new clothing: a locked device's protected-file
    // read fails permission-class. Reported as None, the cycle reads it as a LEAVE and clears the
    // join marker — every locked wake, for ever.
    @Test
    fun `a failed read is Unavailable with the platform status and consults nothing`() {
        val read = configReadViaFile(
            ConfigFileRead.Failed(status = 257, detail = "NSFileReadNoPermissionError"),
            fallback = ::noFallback,
            migrate = ::noMigrate,
            repair = ::noRepair,
        )

        assertIs<ConfigRead.Unavailable>(read)
        assertEquals(257, read.status)
    }

    @Test
    fun `a missing file with a Joined Keychain migrates and returns the Keychain's config`() {
        // The update-in-place path: every already-joined device has a Keychain item and no file.
        // The first read — in WHICHEVER process, the OS may run the extension first — must answer
        // Joined (not a false leave) and write the file forward. The post-migrate recheck sees the
        // same value, so no repair runs.
        var migrated: EventConfig? = null

        val read = configReadViaFile(
            ConfigFileRead.Missing,
            fallback = { ConfigRead.Joined(config) },
            migrate = { migrated = it },
            repair = ::noRepair,
        )

        assertEquals(ConfigRead.Joined(config), read)
        assertEquals(config, migrated)
    }

    @Test
    fun `a missing file with an absent Keychain is None — definitively not joined`() {
        var migrated = false

        val read = configReadViaFile(
            ConfigFileRead.Missing,
            fallback = { ConfigRead.None },
            migrate = { migrated = true },
            repair = ::noRepair,
        )

        assertEquals(ConfigRead.None, read)
        assertEquals(false, migrated)
    }

    @Test
    fun `a missing file with an unreadable Keychain is Unavailable — absence unproven`() {
        // A genuinely missing file proves nothing while the Keychain copy is unreadable: the device
        // may be a pre-migration joined install on a locked device. Only file-missing AND
        // Keychain-absent may read as a leave.
        val read = configReadViaFile(
            ConfigFileRead.Missing,
            fallback = { ConfigRead.Unavailable(-25308) },
            migrate = { throw AssertionError("migrate must not run") },
            repair = ::noRepair,
        )

        assertIs<ConfigRead.Unavailable>(read)
        assertEquals(-25308, read.status)
    }

    @Test
    fun `a failed migration write does not fail the read`() {
        // Migration is best-effort: the answer is the Keychain's Joined either way; a failed write
        // simply retries on the next read. (The adapter catches its own write errors; here the
        // algorithm's contract is that migrate's result is not consulted.)
        val read = configReadViaFile(
            ConfigFileRead.Missing,
            fallback = { ConfigRead.Joined(config) },
            migrate = { /* swallowed write failure */ },
            repair = ::noRepair,
        )

        assertEquals(ConfigRead.Joined(config), read)
    }

    // ---- compare-and-repair: the post-migrate recheck catches a concurrent writer ----

    @Test
    fun `a concurrent clear during the migrate is repaired and the fresh state wins`() {
        // The other process cleared between the Keychain read and the migrate write: the file now
        // holds a stale clobber of a leave. The recheck sees it, repair runs with the fresh state
        // (the adapter deletes the file), and the FRESH state — not the stale Joined — is returned.
        val answers = ArrayDeque(listOf(ConfigRead.Joined(config), ConfigRead.None))
        var migrated: EventConfig? = null
        var repaired: ConfigRead? = null

        val read = configReadViaFile(
            ConfigFileRead.Missing,
            fallback = { answers.removeFirst() },
            migrate = { migrated = it },
            repair = { repaired = it },
        )

        assertEquals(ConfigRead.None, read)
        assertEquals(config, migrated)
        assertEquals(ConfigRead.None, repaired)
    }

    @Test
    fun `a concurrent save during the migrate is repaired to the newer config`() {
        val newer = config.copy(eventId = "e2")
        val answers = ArrayDeque(listOf(ConfigRead.Joined(config), ConfigRead.Joined(newer)))
        var repaired: ConfigRead? = null

        val read = configReadViaFile(
            ConfigFileRead.Missing,
            fallback = { answers.removeFirst() },
            migrate = { },
            repair = { repaired = it },
        )

        assertEquals(ConfigRead.Joined(newer), read)
        assertEquals(ConfigRead.Joined(newer), repaired)
    }
}
