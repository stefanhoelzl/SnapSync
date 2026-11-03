package app.snapsync.data.push

import app.snapsync.data.store.StoreWrite
import app.snapsync.domain.push.LastRegistration
import app.snapsync.domain.push.PushRegistrationKey
import app.snapsync.mock.inMemoryFiles
import app.snapsync.mock.inMemoryPreferences
import app.snapsync.ports.PrefRead
import app.snapsync.ports.Preferences
import app.snapsync.ports.WriteOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The last push registration the backend accepted: a device-scoped value in the shared preferences that saves a
 * re-publish when nothing changed. It is a cache in one direction only — whatever it cannot read answers "none" or
 * "unavailable", and both publish again at the next entry, the redundant and harmless direction.
 */
class PushRegistrationRecordTest {

    private val values = mutableMapOf<String, String>()
    private val shared = mutableMapOf<String, ByteArray>()

    private fun record(preferences: Preferences = inMemoryPreferences(values)) =
        PushRegistrationRecord(preferences, inMemoryFiles(shared = shared))

    private val refusing = object : Preferences by inMemoryPreferences(values) {
        override fun set(key: String, value: String): WriteOutcome = WriteOutcome.Failed("locked")
    }

    @Test
    fun `nothing registered yet reads as none`() {
        assertEquals(LastRegistration.None, record().lastAccepted())
    }

    @Test
    fun `a saved record is read back byte for byte by every instance and replaced by the next`() {
        assertEquals(StoreWrite.Written, record().save(KEY1))
        assertEquals(LastRegistration.Recorded(KEY1), record().lastAccepted())
        record().save(KEY2)
        assertEquals(LastRegistration.Recorded(KEY2), record().lastAccepted())
    }

    @Test
    fun `a record that cannot be read is unavailable, never none`() {
        record().save(KEY1)
        val locked = object : Preferences by inMemoryPreferences(values) {
            override fun get(key: String): PrefRead = PrefRead.Unavailable("locked")
        }
        assertEquals(LastRegistration.Unavailable, record(locked).lastAccepted())
    }

    @Test
    fun `a refused save answers so and records nothing`() {
        assertEquals(StoreWrite.Failed("locked"), record(refusing).save(KEY1))
        assertEquals(LastRegistration.None, record().lastAccepted())
    }

    @Test
    fun `an updated device's record moves from its legacy file into the preferences once`() {
        shared[LEGACY] = "apns\nsandbox\nD\nOLD".encodeToByteArray()

        assertEquals(LastRegistration.Recorded(PushRegistrationKey("apns\nsandbox\nD\nOLD")), record().lastAccepted())

        assertTrue(shared.isEmpty(), "the legacy file is gone")
        assertEquals(LastRegistration.Recorded(PushRegistrationKey("apns\nsandbox\nD\nOLD")), record().lastAccepted())
    }

    @Test
    fun `a legacy record that cannot be moved is still believed and stays for the next read`() {
        shared[LEGACY] = "apns\nsandbox\nD\nOLD".encodeToByteArray()

        assertEquals(
            LastRegistration.Recorded(PushRegistrationKey("apns\nsandbox\nD\nOLD")),
            record(refusing).lastAccepted(),
        )
        assertTrue(shared.isNotEmpty(), "kept until it is moved")
    }

    private companion object {
        const val LEGACY = "push-registration/last-registered.txt"
        val KEY1 = PushRegistrationKey("apns\nsandbox\nD\nT1")
        val KEY2 = PushRegistrationKey("apns\nsandbox\nD\nT2")
    }
}
