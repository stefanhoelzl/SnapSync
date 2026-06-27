package app.snapsync.engine

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.snapsync.engine.db.LedgerDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant
import kotlinx.coroutines.test.runTest

class SqlDelightLedgerBackendTest : LedgerBackendContract() {

    override fun createBackend(): LedgerBackend {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LedgerDatabase.Schema.create(driver)
        return SqlDelightLedgerBackend(LedgerDatabase(driver))
    }

    @Test
    fun `migration to v2 adds assetId and drops pre-migration rows`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // Stand up the pre-assetId (v1) schema with a row.
        driver.execute(
            null,
            """
            CREATE TABLE ledgerRow (
                key TEXT NOT NULL PRIMARY KEY,
                state TEXT NOT NULL,
                attempt INTEGER NOT NULL,
                version TEXT NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent(),
            0,
        )
        driver.execute(null, "INSERT INTO ledgerRow VALUES ('old-key', 'COMPLETED', 0, 'v1', 1000)", 0)

        // Run the full migration to the current schema (1.sqm: destructive drop+recreate with
        // assetId; 2.sqm: row-preserving drop of the version column).
        LedgerDatabase.Schema.migrate(driver, 1L, LedgerDatabase.Schema.version).await()

        val backend = SqlDelightLedgerBackend(LedgerDatabase(driver))
        assertNull(backend.get("old-key")) // 1.sqm is destructive — pre-migration rows are not preserved
        // The assetId column exists and the version column is gone: a put/get round-trips.
        backend.put(LedgerEntry("k", "A", LedgerState.REQUESTED, 0, Instant.fromEpochMilliseconds(1000)))
        assertEquals("A", backend.get("k")?.assetId)
    }

    @Test
    fun `migration v2 to v3 drops the version column but preserves rows`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // Stand up the v2 schema (assetId present, version still present) holding a COMPLETED row.
        driver.execute(
            null,
            """
            CREATE TABLE ledgerRow (
                key TEXT NOT NULL PRIMARY KEY,
                assetId TEXT NOT NULL,
                state TEXT NOT NULL,
                attempt INTEGER NOT NULL,
                version TEXT NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent(),
            0,
        )
        driver.execute(null, "CREATE INDEX ledgerRow_assetId ON ledgerRow(assetId)", 0)
        driver.execute(null, "INSERT INTO ledgerRow VALUES ('A-photo.jpg', 'A', 'COMPLETED', 0, 'v1', 1000)", 0)

        // Run only the v2 -> v3 migration (2.sqm: ALTER TABLE … DROP COLUMN version, row-preserving).
        LedgerDatabase.Schema.migrate(driver, 2L, LedgerDatabase.Schema.version).await()

        // The COMPLETED row survives (so it is not re-uploaded), now without a version column.
        val survived = SqlDelightLedgerBackend(LedgerDatabase(driver)).get("A-photo.jpg")
        assertEquals(LedgerState.COMPLETED, survived?.state)
        assertEquals("A", survived?.assetId)
        assertEquals(0, survived?.attempt)
        assertEquals(Instant.fromEpochMilliseconds(1000), survived?.updatedAt)
    }

    @Test
    fun `retainAssets over a keep-set past the bind-variable limit deletes the complement`() = runTest {
        val backend = createBackend()
        // Far past sqlite's single-statement bind-variable limit (32766): a naive
        // `WHERE assetId NOT IN (…)` would throw. retainAssets must never bind the keep-set into
        // one statement — it diffs in Kotlin and deletes the (small) complement per assetId.
        val t0 = Instant.fromEpochMilliseconds(1_000_000)
        val keep = (0 until 40_000).mapTo(mutableSetOf()) { "k$it" }
        keep.forEach { backend.put(LedgerEntry(it, it, LedgerState.REQUESTED, 0, t0)) }
        backend.put(LedgerEntry("straggler", "straggler", LedgerState.REQUESTED, 0, t0))

        backend.retainAssets(keep)

        assertNull(backend.get("straggler"))
        assertEquals(keep.size, backend.aggregates().pending)
    }
}
