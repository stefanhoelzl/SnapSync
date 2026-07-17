package app.snapsync.engine

import app.snapsync.model.LedgerStore
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.snapsync.engine.db.LedgerDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest

class SqlDelightLedgerStoreTest : LedgerStoreContract() {

    override fun createBackend(): LedgerStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LedgerDatabase.Schema.create(driver)
        return SqlDelightLedgerStore(LedgerDatabase(driver))
    }

    @Test
    fun `migration from v1 adds assetId and drops pre-migration rows`() = runTest {
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

        // Run the full migration chain to the current schema (1.sqm: destructive drop+recreate with
        // assetId; 2.sqm: row-preserving drop of version; 3.sqm: row-preserving drop of updatedAt).
        LedgerDatabase.Schema.migrate(driver, 1L, LedgerDatabase.Schema.version).await()

        val backend = SqlDelightLedgerStore(LedgerDatabase(driver))
        assertNull(backend.get("old-key")) // 1.sqm is destructive — pre-migration rows are not preserved
        // The assetId column exists and version/updatedAt are gone: a put/get round-trips.
        backend.put(LedgerEntry("k", "A", LedgerState.REQUESTED, 0))
        assertEquals("A", backend.get("k")?.assetId)
    }

    @Test
    fun `migration from v2 drops version and updatedAt but preserves rows`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // Stand up the v2 schema (assetId present, version + updatedAt still present) holding a row.
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

        // Run 2.sqm (drop version) and 3.sqm (drop updatedAt) — both row-preserving.
        LedgerDatabase.Schema.migrate(driver, 2L, LedgerDatabase.Schema.version).await()

        // The COMPLETED row survives (so it is not re-uploaded), now without version or updatedAt.
        val survived = SqlDelightLedgerStore(LedgerDatabase(driver)).get("A-photo.jpg")
        assertEquals(LedgerState.COMPLETED, survived?.state)
        assertEquals("A", survived?.assetId)
        assertEquals(0, survived?.attempt)
    }

    @Test
    fun `migration v3 to v4 drops the updatedAt column but preserves rows`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // Stand up the v3 schema (assetId present, version already gone, updatedAt still present).
        driver.execute(
            null,
            """
            CREATE TABLE ledgerRow (
                key TEXT NOT NULL PRIMARY KEY,
                assetId TEXT NOT NULL,
                state TEXT NOT NULL,
                attempt INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL
            )
            """.trimIndent(),
            0,
        )
        driver.execute(null, "CREATE INDEX ledgerRow_assetId ON ledgerRow(assetId)", 0)
        driver.execute(null, "INSERT INTO ledgerRow VALUES ('A-photo.jpg', 'A', 'COMPLETED', 0, 1000)", 0)

        // Run only the v3 -> v4 migration (3.sqm: ALTER TABLE … DROP COLUMN updatedAt, row-preserving).
        LedgerDatabase.Schema.migrate(driver, 3L, LedgerDatabase.Schema.version).await()

        // The COMPLETED row survives, now without an updatedAt column; key/assetId/state/attempt intact.
        val backend = SqlDelightLedgerStore(LedgerDatabase(driver))
        val survived = backend.get("A-photo.jpg")
        assertEquals(LedgerState.COMPLETED, survived?.state)
        assertEquals("A", survived?.assetId)
        assertEquals(0, survived?.attempt)
        // The generated schema no longer binds updatedAt — a fresh put/get round-trips.
        backend.put(LedgerEntry("B-photo.jpg", "B", LedgerState.REQUESTED, 0))
        assertEquals("B", backend.get("B-photo.jpg")?.assetId)
    }

    @Test
    fun `retainAssets over a keep-set past the bind-variable limit deletes the complement`() = runTest {
        val backend = createBackend()
        // Far past sqlite's single-statement bind-variable limit (32766): a naive
        // `WHERE assetId NOT IN (…)` would throw. retainAssets must never bind the keep-set into
        // one statement — it diffs in Kotlin and deletes the (small) complement per assetId.
        val keep = (0 until 40_000).mapTo(mutableSetOf()) { "k$it" }
        keep.forEach { backend.put(LedgerEntry(it, it, LedgerState.REQUESTED, 0)) }
        backend.put(LedgerEntry("straggler", "straggler", LedgerState.REQUESTED, 0))

        backend.retainAssets(keep)

        assertNull(backend.get("straggler"))
        assertEquals(keep.size, backend.aggregates().pending)
    }
}
