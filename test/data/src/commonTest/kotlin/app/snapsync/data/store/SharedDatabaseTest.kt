package app.snapsync.data.store

import app.snapsync.data.store.db.LedgerDatabase
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.OpenUntil
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.sharing.Direction
import app.snapsync.format.TimestampText
import app.snapsync.mock.DatabasesMock
import app.snapsync.ports.DbOpen
import app.snapsync.ports.DbResult
import app.snapsync.testids.testEventId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * `ledger.db` for a process (`docs/architecture.md`, "Storage"): opened on first use and never at construction, a failed
 * open retried by the next use, the app's legacy copy run once right after its open, and the records' readiness — every
 * record in the database, one still in its legacy home, or a database that cannot be read.
 */
class SharedDatabaseTest {

    private val device = DatabasesMock()

    @Test
    fun `building it opens nothing - the first use opens it`() {
        val shared = SharedDatabase(device.port(), legacy = null)
        assertEquals(emptyList(), device.opened)

        shared.use { it.legacyImportQueries.selectImported().executeAsList() }
        assertEquals(listOf(LEDGER_DB_NAME), device.opened)
    }

    @Test
    fun `a failed open is retried by the next use, and only a success is kept`() {
        val shared = SharedDatabase(device.port(), legacy = null)
        device.lock()
        assertIs<DbResult.Failed>(shared.execute { })

        device.lock(on = false)
        shared.use { }
        shared.use { }
        assertEquals(listOf(LEDGER_DB_NAME, LEDGER_DB_NAME), device.opened, "one failed open, one kept")
    }

    @Test
    fun `an adapter breaking the read-write contract is unavailable, never guessed around`() {
        listOf(DbOpen.Missing, DbOpen.OldSchema, DbOpen.Failed("refused")).forEach { refusal ->
            val shared = SharedDatabase(DatabasesMock(mapOf(LEDGER_DB_NAME to refusal)).port(), legacy = null)
            assertIs<DbResult.Failed>(shared.execute { }, "$refusal")
        }
    }

    @Test
    fun `the legacy copy runs once, right after the open`() {
        val copies = mutableListOf<LedgerDatabase>()
        val shared = SharedDatabase(device.port(), RecordingCopy(copies))

        shared.use { }
        shared.use { }
        assertEquals(1, copies.size)
    }

    @Test
    fun `the records are ready once every legacy source is in the database`() {
        val shared = SharedDatabase(device.port(), legacy = null)
        assertEquals(SharedRecordsReadiness.LegacyPending, shared.readiness())
        assertEquals(DbResult.Ok(false), shared.imported(LegacySource.MEMBERSHIP))

        shared.use { db -> LegacySource.entries.forEach { db.legacyImportQueries.markImported(it.key) } }
        assertEquals(SharedRecordsReadiness.Ready, shared.readiness())
        assertEquals(DbResult.Ok(true), shared.imported(LegacySource.MEMBERSHIP))
    }

    @Test
    fun `a database that cannot be opened is unavailable, never pending or ready`() {
        val shared = SharedDatabase(device.port(), legacy = null)
        device.lock()
        assertIs<SharedRecordsReadiness.Unavailable>(shared.readiness())
    }

    @Test
    fun `every typed column reads back what it stored`() {
        val shared = SharedDatabase(device.port(), legacy = null)
        val event = testEventId("11111111-1111-4111-8111-111111111111")
        shared.use { db ->
            db.membershipQueries.saveMembership(
                eventId = Stored.Valid(event),
                name = "Picnic",
                minPhotoDate = CaptureCutoff(TimestampText.parse(FROM)),
                startsAt = EventStart(TimestampText.parse(FROM)),
                endsAt = EventEnd(TimestampText.parse(UNTIL)),
                maxPhotoDate = CaptureCeiling(TimestampText.parse(UNTIL)),
                deletesAt = DeletesAt(TimestampText.parse(UNTIL)),
                direction = Direction.UploadOnly,
                saveToAlbum = true,
                membersActive = 2,
                membersSettled = 1,
                membersWaiting = 1,
                keyId = "k1",
                openUntil = OpenUntil(TimestampText.parse(UNTIL)),
                deleted = false,
            )
        }
        val row = shared.use { it.membershipQueries.selectMembership().executeAsOne() }
        assertEquals(Stored.Valid(event), row.eventId)
        assertEquals(CaptureCutoff(TimestampText.parse(FROM)), row.minPhotoDate)
        assertEquals(EventStart(TimestampText.parse(FROM)), row.startsAt)
        assertEquals(EventEnd(TimestampText.parse(UNTIL)), row.endsAt)
        assertEquals(CaptureCeiling(TimestampText.parse(UNTIL)), row.maxPhotoDate)
        assertEquals(DeletesAt(TimestampText.parse(UNTIL)), row.deletesAt)
        assertEquals(OpenUntil(TimestampText.parse(UNTIL)), row.openUntil)
        assertEquals(Direction.UploadOnly, row.direction)
    }

    private class RecordingCopy(private val copies: MutableList<LedgerDatabase>) : LegacyCopy {
        override fun copyInto(db: LedgerDatabase) {
            copies += db
        }
    }

    private companion object {
        const val FROM = "2026-07-06T00:00:00Z"
        const val UNTIL = "2026-07-07T00:00:00Z"
    }
}
