package app.snapsync.services.ledger

import app.cash.sqldelight.db.SqlDriver
import app.snapsync.contracts.at
import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.mock.fixedClock
import app.snapsync.mock.inMemoryFiles
import app.snapsync.mock.inMemoryPreferences
import app.snapsync.model.ConfigRead
import app.snapsync.model.EventConfig
import app.snapsync.model.FileArea
import app.snapsync.model.LedgerState
import app.snapsync.model.MemberCounts
import app.snapsync.model.TerminalOutcome
import app.snapsync.model.TimestampText
import app.snapsync.model.encodeConfigFile
import app.snapsync.ports.Databases
import app.snapsync.ports.Files
import app.snapsync.services.config.ConfigService
import app.snapsync.services.databases.CONFIG_FILE_NAME
import app.snapsync.services.databases.LegacyRecords
import app.snapsync.services.databases.SharedDatabase
import app.snapsync.services.databases.databaseAt
import app.snapsync.services.databases.strings
import app.snapsync.services.ledger.db.LedgerDatabase
import app.snapsync.testids.testEventId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The ledger's migrations as a device upgrades through them (capability `photo-sharing`). 12.sqm parked every row
 * under the empty event, because the joined event lived in a file a migration cannot read; 13.sqm moved the membership
 * into this database, and the app's copy of that file — the first open after the update — adopts the parked rows into
 * the event it names, or drops them when there is none. v12 is the oldest ledger a device holds. The database is
 * entered at its version THROUGH THE PORT, so the app's own open runs the migrations and the copy — the path an
 * upgrading phone takes. That the migrations also run on each platform's own SQLite is `DatabasesContract`'s
 * `LEDGER_SCHEMA_MIGRATES_FROM_ITS_FIRST_VERSION`.
 */
class LedgerMigrationTest {

    @Test
    fun `an upgrade from v12 adopts every parked row into the membership the app copies`() = runTest {
        val (databases, driver) = databaseAt(LEDGER_DB_NAME, LedgerDatabase.Schema.at(12, V12_ROWS))
        val ledger = LedgerService(appOpen(databases, membershipFile(JOINED))) { testEventId(JOINED) }

        assertEquals(LedgerState.COMPLETED, ledger.get("C-photo.jpg")?.state, "adopted, not lost")
        assertEquals(listOf(JOINED), driver.eventIds())
        // A job queued before the upgrade still settles: the destination survives both rebuilds.
        assertEquals("R-photo.jpg", ledger.entryForDestination("/v2/files/R")?.key)
        assertTrue(ledger.markTerminal("R-photo.jpg", TerminalOutcome.COMPLETED))
    }

    @Test
    fun `with no membership to copy the parked rows are no event's and go`() = runTest {
        val (databases, driver) = databaseAt(LEDGER_DB_NAME, LedgerDatabase.Schema.at(12, V12_ROWS))

        LedgerService(appOpen(databases, inMemoryFiles())) { null }.manifestVersion()

        assertTrue(driver.eventIds().isEmpty())
    }

    @Test
    fun `a later join never adopts what was parked`() = runTest {
        // The extension opened first and migrated, as it may: nothing is copied, so nothing is adopted or dropped.
        val (databases, driver) = databaseAt(LEDGER_DB_NAME, LedgerDatabase.Schema.at(12, V12_ROWS))
        LedgerService(SharedDatabase(databases, legacy = null)) { testEventId(LATER) }.manifestVersion()
        assertEquals(listOf(""), driver.eventIds(), "parked, untouched by a process that copies nothing")

        // The app's copy finds no membership file — the device had left — so the rows go, never to the later event.
        LedgerService(appOpen(databases, inMemoryFiles())) { testEventId(LATER) }.manifestVersion()
        assertTrue(driver.eventIds().isEmpty())
    }

    @Test
    fun `a parked key the copied event already holds keeps that event's row`() = runTest {
        val (databases, driver) = databaseAt(
            LEDGER_DB_NAME,
            LedgerDatabase.Schema.at(
                13,
                "INSERT INTO ledgerRow (eventId, key, assetId, state) VALUES ('', 'C-photo.jpg', 'C', 'COMPLETED')",
                "INSERT INTO ledgerRow (eventId, key, assetId, state) VALUES ('$JOINED', 'C-photo.jpg', 'C', 'DISCOVERED')",
            ),
        )

        val ledger = LedgerService(appOpen(databases, membershipFile(JOINED))) { testEventId(JOINED) }

        assertEquals(LedgerState.DISCOVERED, ledger.get("C-photo.jpg")?.state)
        assertEquals(listOf("", JOINED), driver.eventIds(), "the duplicate stays parked until a purge")
    }

    @Test
    fun `an upgrade from v14 keeps the membership - its event not known deleted and open until its end`() = runTest {
        val (databases, _) = databaseAt(
            LEDGER_DB_NAME,
            LedgerDatabase.Schema.at(
                14,
                "INSERT INTO membership (id, eventId, name, minPhotoDate, startsAt, endsAt, maxPhotoDate, deletesAt, " +
                    "direction, saveToAlbum, closed, membersActive, membersSettled, keyId) VALUES (0, '$JOINED', " +
                    "'Picnic', '2026-07-06T14:00:00Z', '2026-07-06T14:00:00Z', '2026-07-07T00:00:00Z', " +
                    "'2026-07-07T00:00:00Z', '2026-08-05T00:00:00Z', 'Both', 0, 0, 3, 1, NULL)",
                // Every legacy home was copied at an earlier open.
                "INSERT INTO legacyImport (source) VALUES ('membership'), ('manifest'), ('pendingLeaves'), " +
                    "('album'), ('checks')",
            ),
        )

        val read = ConfigService(appOpen(databases, inMemoryFiles()), fixedClock(Instant.parse("2026-07-10T00:00:00Z")))
            .read()

        val config = assertIs<ConfigRead.Joined>(read).config
        assertEquals(testEventId(JOINED), config.eventId)
        assertEquals(false, config.deleted)
        assertNull(config.openUntil)
        assertEquals(MemberCounts(active = 3, settled = 1, waiting = null), config.members)
        assertEquals(
            2,
            config.members?.waitingFor,
            "counts recorded before the waiting one fall back to the difference",
        )
    }
}

/** The event every service in this file is joined to, unless a test says otherwise. */
private const val JOINED = "e101ed00-0000-4000-8000-00000000000a"

/** The shared database as the app opens it, over [files] holding the records' legacy homes. */
private fun appOpen(databases: Databases, files: Files) =
    SharedDatabase(databases, LegacyRecords(files, inMemoryPreferences()))

/** A shared area holding the membership's legacy file, joined to [eventId]. */
private fun membershipFile(eventId: String): Files = inMemoryFiles().apply {
    val config = EventConfig(
        testEventId(eventId),
        name = "Anna's Birthday",
        minPhotoDate = CaptureCutoff(TimestampText.parse("2026-07-06T14:32:11Z")),
        maxPhotoDate = CaptureCeiling(TimestampText.parse("2099-01-01T00:00:00Z")),
        endsAt = EventEnd(TimestampText.parse("2099-12-31T00:00:00Z")),
        deletesAt = DeletesAt(TimestampText.parse("2099-12-31T00:00:00Z")),
    )
    write(FileArea.SHARED, CONFIG_FILE_NAME, encodeConfigFile(config).encodeToByteArray())
}

/** The distinct events the ledger holds rows for, sorted. */
private fun SqlDriver.eventIds(): List<String> = strings("SELECT DISTINCT eventId FROM ledgerRow ORDER BY eventId")

/** One settled row and one in flight, as v12 holds them. */
private const val V12_ROWS: String =
    "INSERT INTO ledgerRow VALUES " +
        "('C-photo.jpg', 'C', 'COMPLETED', '2026-07-10T00:00:00Z', 'primary', 'image/jpeg', 'IMG_C.JPG', '/v2/files/C'), " +
        "('R-photo.jpg', 'R', 'REQUESTED', '2026-07-10T00:00:00Z', 'primary', 'image/jpeg', 'IMG_R.JPG', '/v2/files/R')"

private const val LATER = "1a7e0000-0000-4000-8000-00000000000b"
