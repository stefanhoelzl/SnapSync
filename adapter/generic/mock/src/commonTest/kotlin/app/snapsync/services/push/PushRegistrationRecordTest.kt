package app.snapsync.services.push

import app.snapsync.mock.inMemoryFiles
import app.snapsync.mock.inMemoryPreferences
import app.snapsync.model.PrefRead
import app.snapsync.ports.Preferences
import app.snapsync.ports.WriteOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The last push registration the backend accepted (capability `receiving-photos`): a device-scoped value in the shared
 * preferences that saves a re-publish when nothing changed. It is a cache in one direction only — whatever it cannot
 * read or keep answers "nothing registered", so the next entry publishes again, the redundant and harmless direction.
 */
class PushRegistrationRecordTest {

    private val values = mutableMapOf<String, String>()
    private val shared = mutableMapOf<String, ByteArray>()

    private fun record(preferences: Preferences = inMemoryPreferences(values)) =
        PushRegistrationRecord(preferences, inMemoryFiles(shared = shared))

    @Test
    fun `nothing registered yet reads as nothing`() {
        assertNull(record().loadLastRegistered())
    }

    @Test
    fun `a saved record is read back byte for byte by every instance and replaced by the next`() {
        record().saveLastRegistered("apns|T1|sandbox|D")
        assertEquals("apns|T1|sandbox|D", record().loadLastRegistered())
        record().saveLastRegistered("apns|T2|sandbox|D")
        assertEquals("apns|T2|sandbox|D", record().loadLastRegistered())
    }

    @Test
    fun `a record that cannot be read is nothing registered`() {
        record().saveLastRegistered("apns|T1|sandbox|D")
        val locked = object : Preferences by inMemoryPreferences(values) {
            override fun get(key: String): PrefRead = PrefRead.Unavailable("locked")
        }
        assertNull(record(locked).loadLastRegistered())
    }

    @Test
    fun `a refused save degrades without raising and nothing is claimed`() {
        val refusing = object : Preferences by inMemoryPreferences(values) {
            override fun set(key: String, value: String): WriteOutcome = WriteOutcome.Failed("locked")
        }
        record(refusing).saveLastRegistered("apns|T1|sandbox|D")
        assertNull(record().loadLastRegistered())
    }

    @Test
    fun `an updated device's record moves from its legacy file into the preferences once`() {
        shared["push-registration/last-registered.txt"] = "apns|OLD|sandbox|D".encodeToByteArray()

        assertEquals("apns|OLD|sandbox|D", record().loadLastRegistered())

        assertTrue(shared.isEmpty(), "the legacy file is gone")
        assertEquals("apns|OLD|sandbox|D", record().loadLastRegistered(), "read from the preferences now")
    }

    @Test
    fun `a legacy record that cannot be moved is still believed and stays for the next read`() {
        shared["push-registration/last-registered.txt"] = "apns|OLD|sandbox|D".encodeToByteArray()
        val refusing = object : Preferences by inMemoryPreferences(values) {
            override fun set(key: String, value: String): WriteOutcome = WriteOutcome.Failed("locked")
        }

        assertEquals("apns|OLD|sandbox|D", record(refusing).loadLastRegistered())
        assertTrue(shared.isNotEmpty(), "kept until it is moved")
    }
}
