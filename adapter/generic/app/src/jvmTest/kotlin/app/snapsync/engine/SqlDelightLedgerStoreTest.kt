package app.snapsync.engine

import app.snapsync.ports.LedgerStore
import app.snapsync.world.LedgerStoreContract
import app.snapsync.model.LedgerAggregates
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
        backend.recordUnlessSettled(LedgerEntry("k", "A", LedgerState.REQUESTED, 0, eventId = "E1"))
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
        backend.recordUnlessSettled(LedgerEntry("B-photo.jpg", "B", LedgerState.REQUESTED, 0, eventId = "E1"))
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
        backend.recordUnlessSettled(LedgerEntry("B-photo.jpg", "B", LedgerState.REQUESTED, 0, eventId = "E1"))
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
        others.forEach { backend.recordUnlessSettled(LedgerEntry(it, it, LedgerState.REQUESTED, 0, eventId = "E1")) }
        backend.recordUnlessSettled(LedgerEntry("gone", "gone", LedgerState.COMPLETED, 0, eventId = "E1"))

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
        assertEquals(listOf("A"), backend.manifestRows().map { it.assetId })
    }

    @Test
    fun `migration v8 to v9 settles UPLOADED rows and touches nothing else`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // 8.sqm changes no schema, so the v8 schema IS the current one: create it, then plant rows exactly
        // as a build that still wrote `UPLOADED` left them. The raw INSERT is the point — the current enum
        // cannot express that state, so no Kotlin write could put it there.
        LedgerDatabase.Schema.create(driver)
        driver.execute(
            null,
            "INSERT INTO ledgerRow (key, assetId, state, attempt, eventId, creationDate, role, contentType, " +
                "originalFilename, absent, destinationPath) VALUES " +
                "('U-primary.jpg', 'U', 'UPLOADED', 2, 'E1', '2026-07-10T00:00:00Z', 'primary', 'image/jpeg', " +
                "'IMG_U.JPG', 0, '/v2/files/U'), " +
                "('D-primary.jpg', 'D', 'DISCOVERED', 0, 'E1', '2026-07-10T00:00:00Z', 'primary', 'image/jpeg', 'IMG_D.JPG', 0, NULL), " +
                "('R-primary.jpg', 'R', 'REQUESTED', 0, 'E1', '2026-07-10T00:00:00Z', 'primary', 'image/jpeg', 'IMG_R.JPG', 0, NULL), " +
                "('C-primary.jpg', 'C', 'COMPLETED', 0, 'E1', '2026-07-10T00:00:00Z', 'primary', 'image/jpeg', 'IMG_C.JPG', 0, NULL), " +
                "('F-primary.jpg', 'F', 'FAILED', 1, 'E1', '2026-07-10T00:00:00Z', 'primary', 'image/jpeg', 'IMG_F.JPG', 0, NULL)",
            0,
        )

        // The data-only rewrite. The migration-verify task compares schemas and cannot see a wrong one of
        // these, which is why this test exists — and still cannot, now that the committed schema snapshot
        // has made that comparison real: it gained drift detection and no power at all over a row rewrite.
        LedgerDatabase.Schema.migrate(driver, 8L, LedgerDatabase.Schema.version).await()

        val backend = SqlDelightLedgerStore(LedgerDatabase(driver))
        val settled = backend.get("U-primary.jpg")
        assertEquals(LedgerState.COMPLETED, settled?.state, "an UPLOADED row decodes, and decodes settled")
        assertEquals(2, settled?.attempt)
        assertEquals("E1", settled?.eventId)
        assertEquals("2026-07-10T00:00:00Z", settled?.creationDate)
        assertEquals("/v2/files/U", settled?.destinationPath)
        assertEquals(LedgerState.DISCOVERED, backend.get("D-primary.jpg")?.state)
        assertEquals(LedgerState.REQUESTED, backend.get("R-primary.jpg")?.state)
        assertEquals(LedgerState.COMPLETED, backend.get("C-primary.jpg")?.state)
        assertEquals(LedgerState.FAILED, backend.get("F-primary.jpg")?.state)
        // The half a decode alias could never have fixed: the aggregate compares the STORED text against the
        // bound done set, so an unrewritten row would have counted pending forever.
        assertEquals(LedgerAggregates(pending = 3, completed = 2), backend.aggregates())
    }

    // ---- 9.sqm: the destinationPath index -------------------------------------------------------
    //
    // TWO tests, because 9.sqm meets TWO shapes of v9 database and only one of them is reachable from the
    // committed schema snapshot. `7.sqm` added `destinationPath` with ALTER TABLE ... ADD COLUMN, which
    // creates no index, while `Ledger.sq` has always carried one — so a device that MIGRATED through 7.sqm
    // lacks the index and a device CREATED FRESH at v7/v8/v9 has it. The verify task's snapshot is a fresh
    // database, so it checks the second shape only; the first — the one that does the work, and the whole
    // reason this migration exists — is asserted here.

    @Test
    fun `migration v9 to v10 adds the destinationPath index an upgraded device lacks`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // Stand up v9 as an UPGRADE through 7.sqm left it: every column of the current schema, the assetId
        // index, and — the defect — no index on destinationPath.
        driver.execute(null, V9_LEDGER_ROW, 0)
        driver.execute(null, "CREATE INDEX ledgerRow_assetId ON ledgerRow(assetId)", 0)
        driver.execute(
            null,
            "INSERT INTO ledgerRow VALUES " +
                "('D-photo.jpg', 'D', 'DISCOVERED', 0, 'E1', '2026-07-10T00:00:00Z', 'PRIMARY', " +
                "'image/jpeg', 'IMG_D.JPG', 0, NULL), " +
                "('R-photo.jpg', 'R', 'REQUESTED', 1, 'E1', '2026-07-10T00:00:00Z', 'PRIMARY', " +
                "'image/jpeg', 'IMG_R.JPG', 0, '/v2/files/R'), " +
                "('C-photo.jpg', 'C', 'COMPLETED', 0, 'E1', '2026-07-10T00:00:00Z', 'PRIMARY', " +
                "'image/jpeg', 'IMG_C.JPG', 0, '/v2/files/C')",
            0,
        )
        assertEquals(emptyList(), driver.indexNames().filter { it == DESTINATION_INDEX })

        LedgerDatabase.Schema.migrate(driver, 9L, LedgerDatabase.Schema.version).await()

        // The repair itself: the lookup the acknowledgement path runs on an OS deadline is now indexed on a
        // device that upgraded, exactly as on one created fresh.
        assertEquals(listOf(DESTINATION_INDEX), driver.indexNames().filter { it == DESTINATION_INDEX })

        // CREATE INDEX builds a b-tree over the rows and rewrites none of them: nothing settles, nothing
        // becomes upload work. Two pending + one completed going in, the same coming out.
        val backend = SqlDelightLedgerStore(LedgerDatabase(driver))
        assertEquals(LedgerState.DISCOVERED, backend.get("D-photo.jpg")?.state)
        assertEquals(LedgerState.REQUESTED, backend.get("R-photo.jpg")?.state)
        val completed = backend.get("C-photo.jpg")
        assertEquals(LedgerState.COMPLETED, completed?.state)
        assertEquals("/v2/files/C", completed?.destinationPath, "the indexed column itself is untouched")
        assertEquals("E1", completed?.eventId)
        assertEquals(LedgerAggregates(pending = 2, completed = 1), backend.aggregates())
    }

    @Test
    fun `migration v9 to v10 does not fail on a device created fresh with the index`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // The other shape: created from Ledger.sq's CREATE statements, which have always carried the index.
        // A bare `CREATE INDEX` would fail here with "index ledgerRow_destinationPath already exists" — a
        // crash on update for every recent install, which is why 9.sqm says IF NOT EXISTS.
        driver.execute(null, V9_LEDGER_ROW, 0)
        driver.execute(null, "CREATE INDEX ledgerRow_assetId ON ledgerRow(assetId)", 0)
        driver.execute(null, "CREATE INDEX $DESTINATION_INDEX ON ledgerRow(destinationPath)", 0)
        driver.execute(
            null,
            "INSERT INTO ledgerRow VALUES " +
                "('C-photo.jpg', 'C', 'COMPLETED', 0, 'E1', '2026-07-10T00:00:00Z', 'PRIMARY', " +
                "'image/jpeg', 'IMG_C.JPG', 0, '/v2/files/C')",
            0,
        )

        LedgerDatabase.Schema.migrate(driver, 9L, LedgerDatabase.Schema.version).await()

        // Present exactly once — the migration is idempotent, not additive.
        assertEquals(listOf(DESTINATION_INDEX), driver.indexNames().filter { it == DESTINATION_INDEX })
        val backend = SqlDelightLedgerStore(LedgerDatabase(driver))
        assertEquals(LedgerState.COMPLETED, backend.get("C-photo.jpg")?.state)
        assertEquals(LedgerAggregates(pending = 0, completed = 1), backend.aggregates())
    }
}

private const val DESTINATION_INDEX = "ledgerRow_destinationPath"

/** The v9 table: every column of the current schema, since 9.sqm adds none. */
private val V9_LEDGER_ROW =
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
        originalFilename TEXT NOT NULL DEFAULT '',
        absent INTEGER NOT NULL DEFAULT 0,
        destinationPath TEXT
    )
    """.trimIndent()

/** The index names SQLite actually holds — the thing under test, which no generated query exposes. */
private fun JdbcSqliteDriver.indexNames(): List<String> =
    executeQuery(
        null,
        "SELECT name FROM sqlite_master WHERE type = 'index' AND name NOT LIKE 'sqlite_%' ORDER BY name",
        { cursor ->
            val names = mutableListOf<String>()
            while (cursor.next().value) names += cursor.getString(0)!!
            app.cash.sqldelight.db.QueryResult.Value(names.toList())
        },
        0,
    ).value
