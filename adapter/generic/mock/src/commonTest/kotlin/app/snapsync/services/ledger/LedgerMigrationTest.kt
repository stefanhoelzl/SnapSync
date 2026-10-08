package app.snapsync.services.ledger

import app.cash.sqldelight.db.SqlDriver
import app.snapsync.contracts.LEDGER_V12
import app.snapsync.model.LedgerAggregates
import app.snapsync.model.LedgerState
import app.snapsync.model.TerminalOutcome
import app.snapsync.ports.Databases
import app.snapsync.services.databases.databaseAt
import app.snapsync.services.databases.strings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ledger's one migration (capability `photo-sharing`): 12.sqm, which parks every row under the empty event,
 * because the joined event lives in a file the migration cannot read; the service adopts them at its first use.
 * v12 is the oldest ledger a device holds. The database is entered at that version THROUGH THE PORT, so the
 * service's own open runs the migration — the path an upgrading phone takes. The verify task compares schemas and
 * cannot see these rows move; that the migration also runs on each platform's own SQLite is `DatabasesContract`'s
 * `LEDGER_SCHEMA_MIGRATES_FROM_ITS_FIRST_VERSION`.
 */
class LedgerMigrationTest {

    @Test
    fun `migration v12 to v13 parks every row and the joined event adopts them at first use`() = runTest {
        val (databases, driver) = v12Ledger()
        val backend = LedgerService(databases) { JOINED }
        backend.manifestVersion() // opens, and so migrates, without touching the event
        assertEquals(listOf(""), driver.eventIds(), "parked, not lost")

        assertEquals(LedgerState.COMPLETED, backend.get("C-photo.jpg")?.state)
        // A job queued before the upgrade still settles: the destination survives the rebuild.
        assertEquals("R-photo.jpg", backend.entryForDestination("/v2/files/R")?.key)
        assertTrue(backend.markTerminal("R-photo.jpg", TerminalOutcome.COMPLETED))
        assertEquals(listOf(JOINED), driver.eventIds())
    }

    @Test
    fun `parked rows stay parked and invisible while nothing is joined and the next join purges them`() = runTest {
        val (databases, driver) = v12Ledger()

        val backend = LedgerService(databases) { null }
        assertNull(backend.get("C-photo.jpg"))
        assertEquals(LedgerAggregates(0, 0), backend.aggregates())
        assertTrue(backend.rowsNeedingJob().isEmpty())
        assertEquals(listOf(""), driver.eventIds())

        backend.purgeExcept(JOINED)
        assertTrue(driver.eventIds().isEmpty())
    }

    @Test
    fun `a parked key the joined event already holds keeps the joined event's row`() = runTest {
        val (databases, driver) = v12Ledger()
        LedgerService(databases) { null }.manifestVersion() // migrated, nothing adopted
        driver.execute(
            null,
            "INSERT INTO ledgerRow (eventId, key, assetId, state) VALUES ('$JOINED', 'C-photo.jpg', 'C', 'DISCOVERED')",
            0,
        )

        val backend = LedgerService(databases) { JOINED }

        assertEquals(LedgerState.DISCOVERED, backend.get("C-photo.jpg")?.state)
        assertEquals(LedgerState.REQUESTED, backend.get("R-photo.jpg")?.state)
        assertEquals(listOf("", JOINED), driver.eventIds(), "the duplicate stays parked until a purge")
    }
}

/** The event every service in this file is joined to, unless a test says otherwise. */
private const val JOINED = "E-joined"

/** A v12 ledger — the oldest a device holds — with one settled row and one in flight. */
private fun v12Ledger(): Pair<Databases, SqlDriver> = databaseAt(LEDGER_DB_NAME, 12, *V12_LEDGER)

/** The distinct events the ledger holds rows for, sorted. */
private fun SqlDriver.eventIds(): List<String> = strings("SELECT DISTINCT eventId FROM ledgerRow ORDER BY eventId")

/** v12 with one settled row and one in flight. */
private val V12_LEDGER: Array<String> = (
    LEDGER_V12 + (
        "INSERT INTO ledgerRow VALUES " +
            "('C-photo.jpg', 'C', 'COMPLETED', '2026-07-10T00:00:00Z', 'primary', 'image/jpeg', 'IMG_C.JPG', '/v2/files/C'), " +
            "('R-photo.jpg', 'R', 'REQUESTED', '2026-07-10T00:00:00Z', 'primary', 'image/jpeg', 'IMG_R.JPG', '/v2/files/R')"
        )
    ).toTypedArray()
