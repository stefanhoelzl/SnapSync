package app.snapsync.engine

import app.snapsync.ports.LedgerStore
import app.snapsync.world.LedgerStoreContract
import app.snapsync.model.LedgerAggregates
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.model.TerminalOutcome

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.snapsync.engine.db.LedgerDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class SqlDelightLedgerStoreTest : LedgerStoreContract() {

    override fun createBackend(): LedgerStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LedgerDatabase.Schema.create(driver)
        return SqlDelightLedgerStore(LedgerDatabase(driver))
    }

    @Test
    fun `a batch record that fails part-way records nothing from that batch`() = runTest {
        // The walk skips an asset whose rows all exist, so a batch that landed one role of a Live Photo and not
        // the other would leave the other unrecorded for good. Force a failure on the batch's second statement
        // with a trigger — the real driver's rollback, not a fake's — and assert the first never landed.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LedgerDatabase.Schema.create(driver)
        driver.execute(
            null,
            "CREATE TRIGGER boom BEFORE INSERT ON ledgerRow WHEN NEW.key = 'X-live.mov' " +
                "BEGIN SELECT RAISE(ABORT, 'boom'); END",
            0,
        )
        val backend = SqlDelightLedgerStore(LedgerDatabase(driver))

        val thrown = runCatching {
            backend.recordAllUnlessSettled(
                listOf(
                    LedgerEntry("X-primary.heic", "X", LedgerState.DISCOVERED),
                    LedgerEntry("X-live.mov", "X", LedgerState.DISCOVERED),
                ),
            )
        }

        assertTrue(thrown.isFailure, "the injected failure surfaces")
        assertNull(backend.get("X-primary.heic"), "the batch rolled back whole")
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
        backend.recordUnlessSettled(LedgerEntry("k", "A", LedgerState.REQUESTED))
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

        // The COMPLETED row survives, now without an updatedAt column; key/assetId/state intact.
        val backend = SqlDelightLedgerStore(LedgerDatabase(driver))
        val survived = backend.get("A-photo.jpg")
        assertEquals(LedgerState.COMPLETED, survived?.state)
        assertEquals("A", survived?.assetId)
        // The generated schema no longer binds updatedAt — a fresh put/get round-trips.
        backend.recordUnlessSettled(LedgerEntry("B-photo.jpg", "B", LedgerState.REQUESTED))
        assertEquals("B", backend.get("B-photo.jpg")?.assetId)
    }

    @Test
    fun `migration from v4 preserves COMPLETED rows`() = runTest {
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

        // The COMPLETED row survives the whole chain (the 2.sqm house invariant: a surviving COMPLETED row is
        // what stops re-upload) — through 4.sqm adding `eventId` and 10.sqm dropping it again.
        val backend = SqlDelightLedgerStore(LedgerDatabase(driver))
        val survived = backend.get("A-photo.jpg")
        assertEquals(LedgerState.COMPLETED, survived?.state)
        assertEquals("A", survived?.assetId)

        // And a fresh put on the migrated schema round-trips.
        backend.recordUnlessSettled(LedgerEntry("B-photo.jpg", "B", LedgerState.REQUESTED))
        assertEquals(LedgerState.REQUESTED, backend.get("B-photo.jpg")?.state)
    }

    @Test
    fun `migration from v6 preserves COMPLETED rows and their manifest detail`() = runTest {
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
        assertEquals("2026-07-10T00:00:00Z", survived?.creationDate)
        // And it still projects into the manifest — 6.sqm's `absent` column came and went (10.sqm) without
        // ever hiding a row nobody marked.
        assertEquals(listOf("A"), backend.manifestRows().map { it.assetId })
    }

    @Test
    fun `migration v8 to v9 settles UPLOADED rows and touches nothing else`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // 8.sqm changes no schema, so the v8 schema is the v10 one (9.sqm adds only an index): stand it up,
        // then plant rows exactly as a build that still wrote `UPLOADED` left them. The raw INSERT is the
        // point — the current enum cannot express that state, so no Kotlin write could put it there.
        driver.execute(null, V9_LEDGER_ROW, 0)
        driver.execute(null, "CREATE INDEX ledgerRow_assetId ON ledgerRow(assetId)", 0)
        driver.execute(null, "CREATE INDEX $DESTINATION_INDEX ON ledgerRow(destinationPath)", 0)
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
        assertEquals("2026-07-10T00:00:00Z", settled?.creationDate)
        assertEquals("/v2/files/U", settled?.destinationPath)
        assertEquals(LedgerState.DISCOVERED, backend.get("D-primary.jpg")?.state)
        assertEquals(LedgerState.REQUESTED, backend.get("R-primary.jpg")?.state)
        assertEquals(LedgerState.COMPLETED, backend.get("C-primary.jpg")?.state)
        assertEquals(LedgerState.DISCOVERED, backend.get("F-primary.jpg")?.state, "and 10.sqm rewrites FAILED")
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

    // ---- 10.sqm: three states, and no attempt, provenance or absence columns ----------------------
    //
    // The schema half (three drops) is checked by the verify task against the committed snapshot. The row
    // rewrite is not — it changes no schema — so this test is its coverage, exactly as for 8.sqm.

    @Test
    fun `migration v10 to v11 rewrites FAILED rows to DISCOVERED and drops the three columns`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // v10 as both routes leave it (9.sqm made them agree): every retired column, both indexes.
        driver.execute(null, V9_LEDGER_ROW, 0)
        driver.execute(null, "CREATE INDEX ledgerRow_assetId ON ledgerRow(assetId)", 0)
        driver.execute(null, "CREATE INDEX $DESTINATION_INDEX ON ledgerRow(destinationPath)", 0)
        // Raw INSERTs: the current enum cannot write FAILED, and the current schema has no absent/eventId.
        driver.execute(
            null,
            "INSERT INTO ledgerRow VALUES " +
                "('F-photo.jpg', 'F', 'FAILED', 3, 'E1', '2026-07-10T00:00:00Z', 'primary', " +
                "'image/jpeg', 'IMG_F.JPG', 0, '/v2/files/F'), " +
                "('D-photo.jpg', 'D', 'DISCOVERED', 0, 'E1', '2026-07-10T00:00:00Z', 'primary', " +
                "'image/jpeg', 'IMG_D.JPG', 0, NULL), " +
                "('R-photo.jpg', 'R', 'REQUESTED', 1, 'E1', '2026-07-10T00:00:00Z', 'primary', " +
                "'image/jpeg', 'IMG_R.JPG', 0, '/v2/files/R'), " +
                "('C-photo.jpg', 'C', 'COMPLETED', 0, 'E1', '2026-07-10T00:00:00Z', 'primary', " +
                "'image/jpeg', 'IMG_C.JPG', 0, '/v2/files/C'), " +
                // An earlier build's absence mark, on a row whose provenance was never swept.
                "('A-photo.jpg', 'A', 'COMPLETED', 0, '', '2026-07-10T00:00:00Z', 'primary', " +
                "'image/jpeg', 'IMG_A.JPG', 1, '/v2/files/A')",
            0,
        )

        LedgerDatabase.Schema.migrate(driver, 10L, LedgerDatabase.Schema.version).await()

        // The schema half: no retired column survives (the verify task checks this too, from the snapshot).
        assertEquals(
            listOf(
                "key", "assetId", "state", "creationDate", "role", "contentType", "originalFilename",
                "destinationPath",
            ),
            driver.columnNames(),
        )

        val backend = SqlDelightLedgerStore(LedgerDatabase(driver))
        // The rewrite: FAILED decodes — as DISCOVERED — with every surviving column untouched.
        val failed = backend.get("F-photo.jpg")!!
        assertEquals(LedgerState.DISCOVERED, failed.state)
        assertEquals("2026-07-10T00:00:00Z", failed.creationDate)
        assertEquals("IMG_F.JPG", failed.originalFilename)
        assertEquals("/v2/files/F", failed.destinationPath)
        // Nothing else moves: a COMPLETED row is still what stops a re-upload.
        assertEquals(LedgerState.DISCOVERED, backend.get("D-photo.jpg")?.state)
        assertEquals(LedgerState.REQUESTED, backend.get("R-photo.jpg")?.state)
        assertEquals(LedgerState.COMPLETED, backend.get("C-photo.jpg")?.state)
        assertEquals(LedgerState.COMPLETED, backend.get("A-photo.jpg")?.state)
        // The half a decode alias could never have fixed: the work read compares STORED text against the
        // bound needs-job set, so an unrewritten FAILED row would never have been offered a job.
        assertEquals(listOf("D-photo.jpg", "F-photo.jpg"), backend.rowsNeedingJob().map { it.key })
        // The formerly marked row is reachable again, exactly as the retired per-cycle sweep would have left it.
        assertTrue("A-photo.jpg" in backend.manifestRows().map { it.key })
        assertEquals(LedgerAggregates(pending = 3, completed = 2), backend.aggregates())
    }

    // The manifest version's table and triggers are schema, so the verify task checks that an upgraded device
    // carries them. What it cannot check is that the upgrade touched no row and that the counter WORKS on a
    // database that got its triggers from 11.sqm rather than from the CREATE — this is that coverage.

    @Test
    fun `migration v11 to v12 adds the manifest version and touches no row`() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, V11_LEDGER_ROW, 0)
        driver.execute(null, "CREATE INDEX ledgerRow_assetId ON ledgerRow(assetId)", 0)
        driver.execute(null, "CREATE INDEX $DESTINATION_INDEX ON ledgerRow(destinationPath)", 0)
        driver.execute(
            null,
            "INSERT INTO ledgerRow VALUES " +
                "('C-photo.jpg', 'C', 'COMPLETED', '2026-07-10T00:00:00Z', 'primary', 'image/jpeg', " +
                "'IMG_C.JPG', '/v2/files/C'), " +
                "('R-photo.jpg', 'R', 'REQUESTED', '2026-07-10T00:00:00Z', 'primary', 'image/jpeg', " +
                "'IMG_R.JPG', '/v2/files/R')",
            0,
        )

        LedgerDatabase.Schema.migrate(driver, 11L, LedgerDatabase.Schema.version).await()

        val backend = SqlDelightLedgerStore(LedgerDatabase(driver))
        assertEquals(LedgerState.COMPLETED, backend.get("C-photo.jpg")?.state)
        assertEquals("/v2/files/C", backend.get("C-photo.jpg")?.destinationPath)
        assertEquals(LedgerState.REQUESTED, backend.get("R-photo.jpg")?.state)
        assertEquals(LedgerAggregates(pending = 1, completed = 1), backend.aggregates())
        // No seed and no row touched, so the counter reads 0 — and the migrated triggers are live.
        assertEquals(0L, backend.manifestVersion())
        assertTrue(backend.markTerminal("R-photo.jpg", TerminalOutcome.COMPLETED))
        assertEquals(0L, backend.manifestVersion(), "a state change alone does not advance it")
        backend.deleteKeys(listOf("C-photo.jpg"))
        assertTrue(backend.manifestVersion() > 0L, "a delete advances it through the migrated trigger")
    }
}

/** The v11 table — what 10.sqm leaves, and the shape 11.sqm meets. */
private val V11_LEDGER_ROW =
    """
    CREATE TABLE ledgerRow (
        key TEXT NOT NULL PRIMARY KEY,
        assetId TEXT NOT NULL,
        state TEXT NOT NULL,
        creationDate TEXT NOT NULL DEFAULT '',
        role TEXT NOT NULL DEFAULT '',
        contentType TEXT NOT NULL DEFAULT '',
        originalFilename TEXT NOT NULL DEFAULT '',
        destinationPath TEXT
    )
    """.trimIndent()

private const val DESTINATION_INDEX = "ledgerRow_destinationPath"

/** The v9 (and v10) table: 9.sqm adds no column, so this is also the shape 10.sqm meets. */
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

/** The ledger table's columns in declaration order, as SQLite holds them. */
private fun JdbcSqliteDriver.columnNames(): List<String> =
    executeQuery(
        null,
        "SELECT name FROM pragma_table_info('ledgerRow') ORDER BY cid",
        { cursor ->
            val names = mutableListOf<String>()
            while (cursor.next().value) names += cursor.getString(0)!!
            app.cash.sqldelight.db.QueryResult.Value(names.toList())
        },
        0,
    ).value
