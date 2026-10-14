package app.snapsync.services.config

import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.mock.DatabasesMock
import app.snapsync.mock.fixedClock
import app.snapsync.model.ConfigRead
import app.snapsync.model.Direction
import app.snapsync.model.EventConfig
import app.snapsync.model.MemberCounts
import app.snapsync.model.MembershipRead
import app.snapsync.model.TimestampText
import app.snapsync.services.databases.SharedDatabase
import app.snapsync.services.databases.sharedOver
import app.snapsync.testids.testEventId
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
 * The config service (capability `join-event`): the membership row of the shared database, where exactly ONE answer —
 * the database answered, and holds no row — means "this device left the event", and every other failure defers.
 *
 * Each [service] is a process of its own over the same in-memory SQLite, as the app and the extension are over one
 * App-Group file; the database mock's lock is a device before its first unlock since boot.
 */
class ConfigServiceTest {

    private val databases = DatabasesMock()

    /** A process's membership service over the device's shared database. */
    private fun service() = ConfigService(sharedOver(databases.port()), fixedClock(NOW))

    /** What another process left in the membership row. */
    private fun stored(
        config: EventConfig,
    ) = sharedOver(databases.port()).use { it.membershipQueries.saveMembership(config) }

    // ---- reading: only "no row" is "not joined" -------------------------------------------------------------

    @Test
    fun `no row is not joined`() {
        val service = service()
        assertEquals(ConfigRead.None, service.read())
        assertNull(service.config.value)
        assertEquals(MembershipRead.NotMember, service.membership)
    }

    @Test
    fun `the row is the membership - seeded at first use and never at construction`() {
        stored(SEED)
        val opened = databases.opened.size
        val service = service()
        assertEquals(opened, databases.opened.size, "constructing the service opens no database")

        assertEquals(SEED, service.config.value, "seeded synchronously at the first read of it")
        assertEquals(ConfigRead.Joined(SEED), service.read())
        assertEquals(MembershipRead.Member(SEED), service.membership)
    }

    @Test
    fun `every field of a membership survives the row`() = runTest {
        val full = SEED.copy(
            startsAt = EventStart(TimestampText.parse("2026-06-11T00:00:00Z")),
            direction = Direction.UploadOnly,
            saveToAlbum = true,
            closed = true,
            members = MemberCounts(active = 4, settled = 3),
            keyId = "0123456789abcdef",
        )
        service().save(full)
        assertEquals(ConfigRead.Joined(full), service().read())
    }

    @Test
    fun `a locked database is unreadable and never not joined`() {
        stored(SEED)
        databases.lock()
        val service = service()

        assertIs<ConfigRead.Unavailable>(service.read())
        assertNull(service.config.value, "nothing could be seeded")
        assertEquals(MembershipRead.Unreadable, service.membership, "unreadable, not a leave")
    }

    @Test
    fun `a membership still in its legacy file is unreadable and never not joined`() {
        // The extension's open after an update, before the app copied the file: the row is not authoritative yet.
        val read = ConfigService(SharedDatabase(databases.port(), legacy = null), fixedClock(NOW)).read()
        assertIs<ConfigRead.Unavailable>(read)
        assertTrue("legacy" in read.detail, read.detail)
    }

    // ---- writing ----------------------------------------------------------------------------------------------

    @Test
    fun `a save is read back and replaces what was held`() = runTest {
        stored(SEED)
        val service = service()
        service.save(WRITTEN)

        assertEquals(WRITTEN, service.config.value)
        assertEquals(MembershipRead.Member(WRITTEN), service.membership)
        assertEquals(ConfigRead.Joined(WRITTEN), service().read(), "another process reads what was saved")
    }

    @Test
    fun `a save that cannot be written is refused and changes nothing`() = runTest {
        stored(SEED)
        val service = service().apply { config.value }
        databases.lock()

        assertFailsWith<IllegalStateException> { service.save(WRITTEN) }

        assertEquals(SEED, service.config.value)
        databases.lock(false)
        assertEquals(ConfigRead.Joined(SEED), service().read())
    }

    @Test
    fun `a leave deletes the row and a missing row is already left`() = runTest {
        stored(SEED)
        val service = service()
        service.clear()

        assertNull(service.config.value)
        assertEquals(MembershipRead.NotMember, service.membership)
        assertEquals(ConfigRead.None, service().read())
        service.clear() // nothing to delete: still left
        assertEquals(ConfigRead.None, service().read())
    }

    @Test
    fun `a leave that cannot delete the row is refused and changes nothing`() = runTest {
        stored(SEED)
        val service = service().apply { config.value }
        databases.lock()

        assertFailsWith<IllegalStateException> { service.clear() }

        assertEquals(SEED, service.config.value, "still joined: the row survived")
    }

    // ---- re-reading -------------------------------------------------------------------------------------------

    @Test
    fun `a reload takes what another process wrote or removed`() = runTest {
        val app = service()
        assertNull(app.config.value)

        service().save(SEED)
        app.reload()
        assertEquals(SEED, app.config.value)

        service().clear()
        app.reload()
        assertNull(app.config.value)
        assertEquals(MembershipRead.NotMember, app.membership)
    }

    @Test
    fun `a reload that cannot read keeps the last good membership`() {
        stored(SEED)
        val service = service().apply { config.value }
        databases.lock()

        service.reload()

        assertEquals(SEED, service.config.value)
        assertEquals(MembershipRead.Member(SEED), service.membership)
    }

    @Test
    fun `a reader before the re-read takes the held membership - else the row's - else none`() = runTest {
        val app = service()
        assertNull(app.joinedOrRead(), "no row")

        databases.lock()
        assertNull(app.joinedOrRead(), "an unreadable database holds no membership this reader may use")

        databases.lock(false)
        service().save(SEED) // another process joins
        assertEquals(SEED, app.joinedOrRead(), "the row's, before this process re-read it")

        val held = SEED.copy(eventId = testEventId("E-held"))
        app.save(held)
        databases.lock()
        assertEquals(held, app.joinedOrRead(), "the held one, without asking the database")
    }

    // ---- the membership's clock ---------------------------------------------------------------------------

    @Test
    fun `a membership is past its deletion only once its own deadline has passed`() {
        val service = service()
        assertTrue(
            service.isPastDeletion(DeletesAt(TimestampText.parse("2026-06-01T00:00:00Z"))),
            "the deadline is behind the clock",
        )
        assertFalse(
            service.isPastDeletion(DeletesAt(TimestampText.parse("2026-07-01T00:00:00Z"))),
            "the deadline is ahead of the clock",
        )
    }

    @Test
    fun `an event has ended only once its end is behind the clock`() {
        val service = service()
        assertTrue(service.hasEnded(SEED.copy(endsAt = EventEnd(TimestampText.parse("2026-06-01T00:00:00Z")))))
        assertFalse(service.hasEnded(SEED.copy(endsAt = EventEnd(TimestampText.parse("2026-07-01T00:00:00Z")))))
    }

    // ---- the reads the composition takes ---------------------------------------------------------------------

    private fun joined(config: EventConfig): ConfigService {
        stored(config)
        return service()
    }

    @Test
    fun `a device that is not joined has no active event arms no download and has not ended`() {
        val service = service()
        assertNull(service.activeEventId())
        assertNull(service.downloadsEnabled(), "not joined is neither yes nor no")
        assertFalse(service.freshReadShareHasEnded())
    }

    @Test
    fun `a joined membership answers its event and whether it receives`() {
        assertEquals(SEED.eventId, joined(SEED).activeEventId())
        assertEquals(true, joined(SEED.copy(direction = Direction.Both)).downloadsEnabled())
        assertEquals(false, joined(SEED.copy(direction = Direction.UploadOnly)).downloadsEnabled())
    }

    @Test
    fun `a fresh read says whether the membership's own range has ended`() {
        // Capability `event-lifetime`: a share is settled at the member's own end, not the event's.
        val june1 = TimestampText.parse("2026-06-01T00:00:00Z")
        assertFalse(joined(SEED).freshReadShareHasEnded())
        assertTrue(joined(SEED.copy(maxPhotoDate = CaptureCeiling(june1))).freshReadShareHasEnded(), "ended early")
        assertFalse(
            joined(SEED.copy(endsAt = EventEnd(june1))).freshReadShareHasEnded(),
            "the event ended, but the member's range reaches past it",
        )
    }

    @Test
    fun `an unreadable config has not ended`() {
        stored(SEED.copy(maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-06-01T00:00:00Z"))))
        databases.lock()
        assertFalse(service().freshReadShareHasEnded())
    }
}

/** "Now" for the config service's clock. */
private val NOW: Instant = Instant.parse("2026-06-15T12:00:00Z")

private fun config(eventId: String) = EventConfig(
    eventId = testEventId(eventId),
    name = "Event $eventId",
    minPhotoDate = CaptureCutoff(TimestampText.parse("2026-06-10T00:00:00Z")),
    maxPhotoDate = CaptureCeiling(TimestampText.parse("2026-06-20T00:00:00Z")),
    endsAt = EventEnd(TimestampText.parse("2026-06-20T00:00:00Z")),
    deletesAt = DeletesAt(TimestampText.parse("2026-07-20T00:00:00Z")),
)

private val SEED = config("seeded")
private val WRITTEN = config("written")
