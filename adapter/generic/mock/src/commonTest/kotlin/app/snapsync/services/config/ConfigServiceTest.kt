package app.snapsync.services.config

import app.snapsync.mock.fixedClock
import app.snapsync.mock.inMemoryFiles
import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureCutoff
import app.snapsync.model.CaptureDate
import app.snapsync.model.ConfigRead
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.model.EventEnd
import app.snapsync.model.FileArea
import app.snapsync.model.FileResult
import app.snapsync.model.FileTail
import app.snapsync.model.MembershipRead
import app.snapsync.model.deletesAt
import app.snapsync.model.encodeConfigFile
import app.snapsync.ports.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The config service (capability `join-event`): the membership file in the shared area, where exactly ONE answer —
 * the file is definitively missing — means "this device left the event", and every other failure defers.
 *
 * The `Files` mock is bound to the same `Files` contract as every platform's adapter, so what `NotFound` and
 * `Denied` mean here is what they mean on a phone; this suite holds what the service makes of them.
 */
class ConfigServiceTest {

    private fun service(files: Files) = ConfigService(files, fixedClock(NOW))

    private fun holding(text: String, denied: Set<Pair<FileArea, String>> = emptySet()) =
        inMemoryFiles(shared = mutableMapOf(CONFIG_FILE_NAME to text.encodeToByteArray()), denied = denied)

    // ---- reading: only a missing file is "not joined" -------------------------------------------------------

    @Test
    fun `a missing file is not joined`() {
        val service = service(inMemoryFiles())
        assertEquals(ConfigRead.None, service.read())
        assertNull(service.config.value)
        assertEquals(MembershipRead.NotMember, service.membership)
    }

    @Test
    fun `a record of this build's format is the membership - seeded at construction`() {
        val service = service(holding(encodeConfigFile(SEED)))
        assertEquals(ConfigRead.Joined(SEED), service.read())
        assertEquals(SEED, service.config.value, "seeded synchronously, before any reload")
        assertEquals(MembershipRead.Member(SEED), service.membership)
    }

    @Test
    fun `every answer but a missing file is unreadable and never not joined`() {
        val unreadable = mapOf(
            "an unreachable area" to inMemoryFiles(shared = null),
            "a present file the process may not read" to
                holding(encodeConfigFile(SEED), denied = setOf(FileArea.SHARED to CONFIG_FILE_NAME)),
            "a successor's envelope (a reverted build)" to holding(FOREIGN_FILE),
            "this build's envelope with an undecodable payload" to holding(UNUSABLE_FILE),
            "bytes that are not UTF-8" to
                inMemoryFiles(shared = mutableMapOf(CONFIG_FILE_NAME to byteArrayOf(0xC3.toByte(), 0x28))),
            "a failed read" to Answering(FileResult.Failed("io")),
        )
        unreadable.forEach { (case, files) ->
            val service = service(files)
            assertIs<ConfigRead.Unavailable>(service.read(), "$case must defer, never read as a leave")
            assertNull(service.config.value, "$case: the screen cannot show what this build cannot read")
            assertEquals(MembershipRead.Unreadable, service.membership, case)
        }
    }

    @Test
    fun `an unreadable answer carries the platform's code`() {
        val denied = service(Answering(FileResult.Denied("locked", code = 257))).read()
        assertTrue("257" in assertIs<ConfigRead.Unavailable>(denied).detail)
    }

    // ---- writing: a save or a leave that could not be made is refused, never half-done ----------------------

    @Test
    fun `a save is read back and replaces what was held`() = runTest {
        val empty = service(inMemoryFiles())
        empty.save(WRITTEN)
        assertEquals(ConfigRead.Joined(WRITTEN), empty.read())
        assertEquals(WRITTEN, empty.config.value)
        assertEquals(MembershipRead.Member(WRITTEN), empty.membership)

        val joined = service(holding(encodeConfigFile(SEED)))
        joined.save(WRITTEN)
        assertEquals(ConfigRead.Joined(WRITTEN), joined.read())
        assertEquals(WRITTEN, joined.config.value)
    }

    @Test
    fun `a save of the value this process already holds still writes the file`() = runTest {
        // No equal-value shortcut: another process may have removed the file this process's state still shows.
        val files = holding(encodeConfigFile(SEED))
        val app = service(files)
        service(files).clear()

        app.save(SEED)

        assertEquals(ConfigRead.Joined(SEED), service(files).read())
        assertEquals(SEED, app.config.value)
    }

    @Test
    fun `a save that cannot be written is refused and changes nothing`() = runTest {
        val unwritable = listOf(inMemoryFiles(shared = null), Answering(FileResult.Denied("locked")))
        unwritable.forEach { files ->
            val service = service(files)
            assertFailsWith<IllegalStateException> { service.save(WRITTEN) }
            assertNull(service.config.value)
            assertEquals(MembershipRead.Unreadable, service.membership)
            assertIs<ConfigRead.Unavailable>(service.read())
        }
    }

    @Test
    fun `a leave deletes the file and a missing file is already left`() = runTest {
        val files = holding(encodeConfigFile(SEED))
        val joined = service(files)
        joined.clear()
        assertEquals(ConfigRead.None, joined.read(), "a completed leave reads as not joined")
        assertNull(joined.config.value)
        assertEquals(MembershipRead.NotMember, joined.membership)

        val absent = service(inMemoryFiles())
        absent.clear()
        assertEquals(ConfigRead.None, absent.read())
        assertEquals(MembershipRead.NotMember, absent.membership)
    }

    @Test
    fun `a leave that cannot delete the file is refused and changes nothing`() = runTest {
        // An unreachable area is NOT "nothing to delete": a file it never touched would resurrect the membership.
        val refusing = listOf(inMemoryFiles(shared = null), Answering(FileResult.Failed("io")))
        refusing.forEach { files ->
            val service = service(files)
            assertFailsWith<IllegalStateException> { service.clear() }
            assertEquals(MembershipRead.Unreadable, service.membership)
            assertIs<ConfigRead.Unavailable>(service.read())
        }
    }

    // ---- reloading: what another process wrote, and never a transient failure ------------------------------

    @Test
    fun `a reload takes what another process wrote or removed`() = runTest {
        val files = inMemoryFiles()
        val app = service(files)
        val extension = service(files)

        extension.save(SEED)
        app.reload()
        assertEquals(SEED, app.config.value)
        assertEquals(MembershipRead.Member(SEED), app.membership)

        extension.clear()
        app.reload()
        assertNull(app.config.value)
        assertEquals(MembershipRead.NotMember, app.membership)
    }

    @Test
    fun `a reload that cannot read keeps the last good membership`() {
        val shared = mutableMapOf(CONFIG_FILE_NAME to encodeConfigFile(SEED).encodeToByteArray())
        val denied = mutableSetOf<Pair<FileArea, String>>()
        val service = service(inMemoryFiles(shared = shared, denied = denied))

        denied += FileArea.SHARED to CONFIG_FILE_NAME
        service.reload()

        assertEquals(SEED, service.config.value, "a transient failure must not flip the screen to the setup gate")
        assertEquals(MembershipRead.Member(SEED), service.membership)
    }

    // ---- the membership's clock ---------------------------------------------------------------------------

    @Test
    fun `a membership is past its deletion only once its own deadline has passed`() {
        val service = service(inMemoryFiles())
        assertTrue(service.isPastDeletion(deletesAt("2026-06-01T00:00:00Z")), "the deadline is behind the clock")
        assertFalse(service.isPastDeletion(deletesAt("2026-07-01T00:00:00Z")), "the deadline is ahead of the clock")
    }

    @Test
    fun `an event has ended only once its end is behind the clock`() {
        val service = service(inMemoryFiles())
        assertTrue(service.hasEnded(SEED.copy(endsAt = EventEnd(CaptureDate("2026-06-01T00:00:00Z")))))
        assertFalse(service.hasEnded(SEED.copy(endsAt = EventEnd(CaptureDate("2026-07-01T00:00:00Z")))))
    }

    /** A `Files` whose every answer is [answer] — a platform failure class the mock has no lever for. */
    // ---- the reads the composition takes ---------------------------------------------------------------------

    private fun joined(config: EventConfig) = service(holding(encodeConfigFile(config)))

    @Test
    fun `a device that is not joined has no active event arms no download and has not ended`() {
        val service = service(inMemoryFiles())
        assertNull(service.activeEventId())
        assertNull(service.downloadsEnabled(), "not joined is neither yes nor no")
        assertFalse(service.freshReadHasEnded())
    }

    @Test
    fun `a joined membership answers its event and whether it receives`() {
        assertEquals(SEED.eventId, joined(SEED).activeEventId())
        assertEquals(true, joined(SEED.copy(direction = Direction.Both)).downloadsEnabled())
        assertEquals(false, joined(SEED.copy(direction = Direction.UploadOnly)).downloadsEnabled())
    }

    @Test
    fun `a fresh read says whether the joined range has ended`() {
        assertFalse(joined(SEED).freshReadHasEnded())
        assertTrue(joined(SEED.copy(endsAt = EventEnd(CaptureDate("2026-06-01T00:00:00Z")))).freshReadHasEnded())
    }

    @Test
    fun `an unreadable config has not ended`() {
        assertFalse(service(Answering(FileResult.Denied("locked"))).freshReadHasEnded())
    }

    private class Answering(private val answer: FileResult<Nothing>) : Files {
        override fun read(area: FileArea, path: String): FileResult<ByteArray> = answer
        override fun readTail(area: FileArea, path: String, maxBytes: Int): FileResult<FileTail> = answer
        override fun readRange(
            area: FileArea,
            path: String,
            offset: Long,
            maxBytes: Int,
        ): FileResult<ByteArray> = answer
        override fun append(area: FileArea, path: String, bytes: ByteArray): FileResult<Unit> = answer
        override fun write(area: FileArea, path: String, bytes: ByteArray): FileResult<Unit> = answer
        override fun delete(area: FileArea, path: String): FileResult<Unit> = answer
        override fun exists(area: FileArea, path: String): FileResult<Boolean> = answer
        override fun locate(area: FileArea, path: String): FileResult<String> = answer
        override fun move(area: FileArea, from: String, to: String): FileResult<Unit> = answer
        override fun adopt(osPath: String, area: FileArea, to: String): FileResult<Unit> = answer
        override fun list(area: FileArea, directory: String): FileResult<List<String>> = answer
    }
}

/** "Now" for the config service's clock. */
private val NOW: Instant = Instant.parse("2026-06-15T12:00:00Z")

private fun config(eventId: String) = EventConfig(
    eventId = eventId,
    name = "Event $eventId",
    minPhotoDate = CaptureCutoff(CaptureDate("2026-06-10T00:00:00Z")),
    maxPhotoDate = CaptureCeiling(CaptureDate("2026-06-20T00:00:00Z")),
    endsAt = EventEnd(CaptureDate("2026-06-20T00:00:00Z")),
    deletesAt = deletesAt("2026-07-20T00:00:00Z"),
)

private val SEED = config("seeded")
private val WRITTEN = config("written")

/** A successor's envelope — the revert-build case. */
private const val FOREIGN_FILE = """{"v":2,"payload":{"eventId":"from-a-later-build"}}"""

/** This build's envelope, whose payload lacks every required field. */
private const val UNUSABLE_FILE = """{"v":1,"payload":{"eventId":"no-cutoff-no-name"}}"""
