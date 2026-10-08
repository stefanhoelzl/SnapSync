package app.snapsync.services.push

import app.snapsync.mock.inMemoryFiles
import app.snapsync.model.FileArea
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The last push registration the backend accepted (capability `receiving-photos`): a file in the shared area that
 * saves a re-publish when nothing changed. It is a cache in one direction only — whatever it cannot read or keep
 * answers "nothing registered", so the next entry publishes again, the redundant and harmless direction.
 */
class PushRegistrationRecordTest {

    private val shared = mutableMapOf<String, ByteArray>()

    @Test
    fun `nothing registered yet reads as nothing`() {
        assertNull(PushRegistrationRecord(inMemoryFiles(shared = shared)).loadLastRegistered())
    }

    @Test
    fun `a saved record is read back byte for byte by every instance and replaced by the next`() {
        PushRegistrationRecord(inMemoryFiles(shared = shared)).saveLastRegistered(FIRST)
        assertEquals(
            FIRST,
            PushRegistrationRecord(inMemoryFiles(shared = shared)).loadLastRegistered(),
            "multi-line, verbatim",
        )

        PushRegistrationRecord(inMemoryFiles(shared = shared)).saveLastRegistered(SECOND)
        assertEquals(SECOND, PushRegistrationRecord(inMemoryFiles(shared = shared)).loadLastRegistered())
    }

    @Test
    fun `a record that cannot be read is nothing registered`() {
        PushRegistrationRecord(inMemoryFiles(shared = shared)).saveLastRegistered(FIRST)
        val denied = inMemoryFiles(
            shared = shared,
            denied = setOf(FileArea.SHARED to "push-registration/last-registered.txt"),
        )

        assertNull(PushRegistrationRecord(denied).loadLastRegistered())
    }

    @Test
    fun `with no shared area a save degrades without raising and nothing is claimed`() {
        val record = PushRegistrationRecord(inMemoryFiles(shared = null))

        record.saveLastRegistered(FIRST)

        assertNull(record.loadLastRegistered(), "a record that cannot hold anything must never claim to")
    }
}

private const val FIRST = "sandbox\ndevice-1\ntoken-1"
private const val SECOND = "production\ndevice-1\ntoken-2"
