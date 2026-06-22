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

        // Run the destructive v1 -> v2 migration (1.sqm: drop + recreate with assetId).
        LedgerDatabase.Schema.migrate(driver, 1L, LedgerDatabase.Schema.version).await()

        val backend = SqlDelightLedgerBackend(LedgerDatabase(driver))
        assertNull(backend.get("old-key")) // destructive — pre-migration rows are not preserved
        // The assetId column now exists: a put/get round-trips it.
        backend.put(LedgerEntry("k", "A", LedgerState.REQUESTED, 0, "v1", Instant.fromEpochMilliseconds(1000)))
        assertEquals("A", backend.get("k")?.assetId)
    }

    @Test
    fun `retainAssets over a keep-set past the bind-variable limit deletes the complement`() = runTest {
        val backend = createBackend()
        // Far past sqlite's single-statement bind-variable limit (32766): a naive
        // `WHERE assetId NOT IN (…)` would throw. retainAssets must never bind the keep-set into
        // one statement — it diffs in Kotlin and deletes the (small) complement per assetId.
        val t0 = Instant.fromEpochMilliseconds(1_000_000)
        val keep = (0 until 40_000).mapTo(mutableSetOf()) { "k$it" }
        keep.forEach { backend.put(LedgerEntry(it, it, LedgerState.REQUESTED, 0, "v1", t0)) }
        backend.put(LedgerEntry("straggler", "straggler", LedgerState.REQUESTED, 0, "v1", t0))

        backend.retainAssets(keep)

        assertNull(backend.get("straggler"))
        assertEquals(keep.size, backend.aggregates().pending)
    }
}
