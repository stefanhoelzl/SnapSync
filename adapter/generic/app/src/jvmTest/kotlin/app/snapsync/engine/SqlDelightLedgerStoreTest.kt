package app.snapsync.engine

import app.snapsync.ports.LedgerStore
import app.snapsync.world.LedgerStoreContract
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
        backend.put(LedgerEntry("k", "A", LedgerState.REQUESTED, 0, eventId = "E1"))
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
        backend.put(LedgerEntry("B-photo.jpg", "B", LedgerState.REQUESTED, 0, eventId = "E1"))
        assertEquals("B", backend.get("B-photo.jpg")?.assetId)
    }

    @Test
    fun `migration v4 to v5 adds eventId as the sentinel and preserves COMPLETED rows`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // Stand up the v4 schema (key, assetId, state, attempt — no eventId) holding a COMPLETED row.
        driver.execute(
            null,
            """
            CREATE TABLE ledgerRow (
                key TEXT NOT NULL PRIMARY KEY,
                assetId TEXT NOT NULL,
                state TEXT NOT NULL,
                attempt INTEGER NOT NULL
            )
            """.trimIndent(),
            0,
        )
        driver.execute(null, "CREATE INDEX ledgerRow_assetId ON ledgerRow(assetId)", 0)
        driver.execute(null, "INSERT INTO ledgerRow VALUES ('A-photo.jpg', 'A', 'COMPLETED', 0)", 0)

        // Run only the v4 -> v5 migration (4.sqm: ALTER TABLE … ADD COLUMN eventId, row-preserving).
        LedgerDatabase.Schema.migrate(driver, 4L, LedgerDatabase.Schema.version).await()

        // The COMPLETED row survives (the 2.sqm house invariant: a surviving COMPLETED row is what
        // stops re-upload) and carries the pre-provenance sentinel — the true eventId lives in
        // config, unreachable from SQL, so the migration cannot fill it.
        val backend = SqlDelightLedgerStore(LedgerDatabase(driver))
        val survived = backend.get("A-photo.jpg")
        assertEquals(LedgerState.COMPLETED, survived?.state)
        assertEquals("A", survived?.assetId)
        assertEquals(0, survived?.attempt)
        assertEquals("", survived?.eventId)

        // The single writer's first post-migration cycle sweeps the sentinel to the live event.
        backend.backfillEventId("E1")
        assertEquals("E1", backend.get("A-photo.jpg")?.eventId)
        assertEquals(LedgerState.COMPLETED, backend.get("A-photo.jpg")?.state)

        // And a fresh put on the migrated schema round-trips the new column.
        backend.put(LedgerEntry("B-photo.jpg", "B", LedgerState.REQUESTED, 0, eventId = "E1"))
        assertEquals("E1", backend.get("B-photo.jpg")?.eventId)
    }

    @Test
    fun `a v4-shaped column-explicit insert still works on the v5 schema`() = runTest {
        // The staged-revert guarantee: a reverted build ships the OLD generated queries — a
        // column-explicit 4-column INSERT OR REPLACE — against the already-migrated 5-column table.
        // `DEFAULT ''` is what lets that insert succeed (the row lands as a sentinel row, swept by
        // the next post-re-update cycle's backfill). This is the INSERT-level half of the downgrade
        // stance; the driver-level half (SQLiter refuses to OPEN a newer-versioned DB) is design.md's.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LedgerDatabase.Schema.create(driver)

        driver.execute(
            null,
            "INSERT OR REPLACE INTO ledgerRow (key, assetId, state, attempt) VALUES ('r', 'R', 'COMPLETED', 0)",
            0,
        )

        val backend = SqlDelightLedgerStore(LedgerDatabase(driver))
        val row = backend.get("r")
        assertEquals(LedgerState.COMPLETED, row?.state)
        assertEquals("", row?.eventId) // the DEFAULT filled the omitted column
    }

    @Test
    fun `markAbsent flags one asset's rows across a large table`() = runTest {
        // What this replaces: `retainAssets` took a keep-set, so it had to avoid binding a
        // multi-thousand-element `NOT IN` (sqlite's limit is 32766) by diffing in Kotlin. `markAbsent`
        // takes ONE assetId and rides the assetId index, so no such hazard exists — this only holds that
        // the indexed UPDATE still finds its row in a table large enough to matter.
        val backend = createBackend()
        val others = (0 until 40_000).map { "k$it" }
        others.forEach { backend.put(LedgerEntry(it, it, LedgerState.REQUESTED, 0, eventId = "E1")) }
        backend.put(LedgerEntry("gone", "gone", LedgerState.COMPLETED, 0, eventId = "E1"))

        backend.markAbsent("gone")

        val row = backend.get("gone")
        assertEquals(true, row?.absent)
        assertEquals(LedgerState.COMPLETED, row?.state) // the row survives, so re-upload stays suppressed
        assertEquals(false, backend.get("k0")?.absent)
    }

    @Test
    fun `migration v6 to v7 adds absent unset and preserves COMPLETED rows`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // Stand up the v6 schema (manifest detail present, no `absent` column) holding a COMPLETED row.
        driver.execute(
            null,
            """
            CREATE TABLE ledgerRow (
                key TEXT NOT NULL PRIMARY KEY,
                assetId TEXT NOT NULL,
                state TEXT NOT NULL,
                attempt INTEGER NOT NULL,
                eventId TEXT NOT NULL DEFAULT '',
                creationDate TEXT NOT NULL DEFAULT '',
                role TEXT NOT NULL DEFAULT '',
                contentType TEXT NOT NULL DEFAULT '',
                originalFilename TEXT NOT NULL DEFAULT ''
            )
            """.trimIndent(),
            0,
        )
        driver.execute(null, "CREATE INDEX ledgerRow_assetId ON ledgerRow(assetId)", 0)
        driver.execute(
            null,
            "INSERT INTO ledgerRow VALUES " +
                "('A-photo.jpg', 'A', 'COMPLETED', 0, 'E1', '2026-07-10T00:00:00Z', 'PRIMARY', " +
                "'image/jpeg', 'IMG_A.JPG')",
            0,
        )

        // 6.sqm is ALTER TABLE ... ADD COLUMN — catalog-only, so no row is touched. That matters more than
        // usual here: a surviving COMPLETED row is exactly what stops the next cycle re-uploading an
        // already-stored resource, and losing them would re-upload every member's whole in-window library.
        LedgerDatabase.Schema.migrate(driver, 6L, LedgerDatabase.Schema.version).await()

        val backend = SqlDelightLedgerStore(LedgerDatabase(driver))
        val survived = backend.get("A-photo.jpg")
        assertEquals(LedgerState.COMPLETED, survived?.state)
        assertEquals("A", survived?.assetId)
        assertEquals("E1", survived?.eventId)
        assertEquals("2026-07-10T00:00:00Z", survived?.creationDate)
        // Unset is the correct resting value: a row recorded before this column existed was, by
        // construction, not marked absent.
        assertEquals(false, survived?.absent)
        // And it still projects into the manifest, which filters on that column.
        assertEquals(listOf("A"), backend.completedManifestRows().map { it.assetId })
    }
}
