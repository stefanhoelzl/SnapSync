package app.snapsync.engine

import app.snapsync.model.AssetId
import app.snapsync.services.ledger.LedgerService
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.LEDGER_CONTRACT_EVENT
import app.snapsync.contracts.LedgerStoreContract
import app.snapsync.contracts.LedgerStoreState
import app.snapsync.contracts.verify
import app.snapsync.model.LedgerAggregates
import app.snapsync.model.LedgerEntry
import app.snapsync.model.LedgerState
import app.snapsync.model.TerminalOutcome

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.snapsync.databases.freshJdbcDatabases
import app.snapsync.databases.opened
import app.snapsync.services.ledger.db.LedgerDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class LedgerServiceTest {

    /** The contract, bound on this host (JVM). Every clause starts from a fresh, empty store. */
    private val binding = object : Binding<LedgerStoreState, LedgerService> {
        override val host = Host.JVM
        override val kind = BindingKind.Live
        override val reaches = setOf(LedgerStoreState.EMPTY)
        override fun create(state: LedgerStoreState, clauseId: String) = Entered.Ready(createBackend())
    }

    @Test
    fun `satisfies the LedgerService contract`() = verify(LedgerStoreContract, binding)

    /** The service over the real JVM adapter, in a directory of its own: the contract runs through the service. */
    private fun createBackend(): LedgerService = LedgerService(freshJdbcDatabases()) { LEDGER_CONTRACT_EVENT }

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
        val backend = LedgerService(opened(driver)) { JOINED }

        val thrown = runCatching {
            backend.recordAllUnlessSettled(
                listOf(
                    LedgerEntry("X-primary.heic", AssetId("X"), LedgerState.DISCOVERED),
                    LedgerEntry("X-live.mov", AssetId("X"), LedgerState.DISCOVERED),
                ),
            )
        }

        assertTrue(thrown.isFailure, "the injected failure surfaces")
        assertNull(backend.get("X-primary.heic"), "the batch rolled back whole")
    }

    @Test
    fun `migration v12 to v13 parks every row and the joined event adopts them at first use`() = runTest {
        val driver = v12Ledger()

        LedgerDatabase.Schema.migrate(driver, 12L, LedgerDatabase.Schema.version).await()
        assertEquals(listOf(""), driver.eventIds(), "parked, not lost")

        val backend = LedgerService(opened(driver)) { JOINED }
        assertEquals(LedgerState.COMPLETED, backend.get("C-photo.jpg")?.state)
        // A job queued before the upgrade still settles: the destination survives the rebuild.
        assertEquals("R-photo.jpg", backend.entryForDestination("/v2/files/R")?.key)
        assertTrue(backend.markTerminal("R-photo.jpg", TerminalOutcome.COMPLETED))
        assertEquals(listOf(JOINED), driver.eventIds())
    }

    @Test
    fun `parked rows stay parked and invisible while nothing is joined and the next join purges them`() = runTest {
        val driver = v12Ledger()
        LedgerDatabase.Schema.migrate(driver, 12L, LedgerDatabase.Schema.version).await()

        val backend = LedgerService(opened(driver)) { null }
        assertNull(backend.get("C-photo.jpg"))
        assertEquals(LedgerAggregates(0, 0), backend.aggregates())
        assertTrue(backend.rowsNeedingJob().isEmpty())
        assertEquals(listOf(""), driver.eventIds())

        backend.purgeExcept(JOINED)
        assertTrue(driver.eventIds().isEmpty())
    }

    @Test
    fun `a parked key the joined event already holds keeps the joined event's row`() = runTest {
        val driver = v12Ledger()
        LedgerDatabase.Schema.migrate(driver, 12L, LedgerDatabase.Schema.version).await()
        driver.execute(
            null,
            "INSERT INTO ledgerRow (eventId, key, assetId, state) VALUES ('$JOINED', 'C-photo.jpg', 'C', 'DISCOVERED')",
            0,
        )

        val backend = LedgerService(opened(driver)) { JOINED }

        assertEquals(LedgerState.DISCOVERED, backend.get("C-photo.jpg")?.state)
        assertEquals(LedgerState.REQUESTED, backend.get("R-photo.jpg")?.state)
        assertEquals(listOf("", JOINED), driver.eventIds(), "the duplicate stays parked until a purge")
    }

    @Test
    fun `a row of another event never satisfies the joined event`() = runTest {
        val backend = createBackend()
        backend.resetTo("E-earlier", listOf(LedgerEntry("X-primary.heic", AssetId("X"), LedgerState.COMPLETED)))

        assertNull(backend.get("X-primary.heic"), "a COMPLETED row of another event suppresses nothing")
        assertTrue(backend.recordUnlessSettled(LedgerEntry("X-primary.heic", AssetId("X"), LedgerState.DISCOVERED)))
        assertEquals(listOf("X-primary.heic"), backend.rowsNeedingJob().map { it.key })
    }
}

/** The event every service in this file is joined to, unless a test says otherwise. */
private const val JOINED = "E-joined"

/** A v12 ledger — what 11.sqm leaves — holding one settled row and one in flight. */
private fun v12Ledger(): JdbcSqliteDriver {
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
    // What 11.sqm (squashed away since every device runs 0.4's v12 or later) added: the manifest version's table
    // and triggers, so the migration starts from exactly the v12 shape.
    V12_MANIFEST_VERSION.forEach { driver.execute(null, it, 0) }
    return driver
}

/** The distinct events the ledger holds rows for, sorted. */
private fun JdbcSqliteDriver.eventIds(): List<String> =
    executeQuery(null, "SELECT DISTINCT eventId FROM ledgerRow ORDER BY eventId", { cursor ->
        val out = mutableListOf<String>()
        while (cursor.next().value) out += cursor.getString(0)!!
        app.cash.sqldelight.db.QueryResult.Value(out)
    }, 0).value

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

/** The manifest version's table and triggers, as 11.sqm created them. */
private val V12_MANIFEST_VERSION = listOf(
    """
    CREATE TABLE manifestVersion (
        id INTEGER NOT NULL PRIMARY KEY CHECK (id = 0),
        value INTEGER NOT NULL
    );
    """.trimIndent(),
    """
    CREATE TRIGGER ledgerRow_version_insert AFTER INSERT ON ledgerRow
    BEGIN
        INSERT INTO manifestVersion (id, value)
        SELECT 0, 0 WHERE NOT EXISTS (SELECT 1 FROM manifestVersion WHERE id = 0);
        UPDATE manifestVersion SET value = value + 1 WHERE id = 0;
    END;
    """.trimIndent(),
    """
    CREATE TRIGGER ledgerRow_version_delete AFTER DELETE ON ledgerRow
    BEGIN
        INSERT INTO manifestVersion (id, value)
        SELECT 0, 0 WHERE NOT EXISTS (SELECT 1 FROM manifestVersion WHERE id = 0);
        UPDATE manifestVersion SET value = value + 1 WHERE id = 0;
    END;
    """.trimIndent(),
    """
    CREATE TRIGGER ledgerRow_version_update AFTER UPDATE ON ledgerRow
    BEGIN
        INSERT INTO manifestVersion (id, value)
        SELECT 0, 0 WHERE NOT EXISTS (SELECT 1 FROM manifestVersion WHERE id = 0);
        UPDATE manifestVersion SET value = value + 1
        WHERE id = 0 AND (
            old.key IS NOT new.key
            OR old.assetId IS NOT new.assetId
            OR old.creationDate IS NOT new.creationDate
            OR old.role IS NOT new.role
            OR old.contentType IS NOT new.contentType
            OR old.originalFilename IS NOT new.originalFilename
        );
    END;
    """.trimIndent(),
)
