package app.snapsync.data.store

import app.cash.sqldelight.db.SqlDriver
import app.snapsync.data.store.db.LedgerDatabase
import app.snapsync.mock.DatabasesMock
import app.snapsync.ports.DbResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

/**
 * A store's own database (`docs/architecture.md`, "Storage"): a statement that fails on it is a value, the database stays
 * open, and a store that prepares nothing is opened as it is.
 */
class OwnedDatabaseTest {

    private val device = DatabasesMock()

    /** A store over `ledger.db` that prepares nothing after its open. */
    private val store = object : OwnedDatabase<LedgerDatabase>(device.port(), LEDGER_DB_NAME, LedgerDatabase.Schema) {
        override fun over(driver: SqlDriver) = sharedDatabaseOver(driver)
    }

    @Test
    fun `a store that prepares nothing answers its statements`() {
        val imported = store.execute { it.legacyImportQueries.selectImported().executeAsList() }
        assertEquals(DbResult.Ok(emptyList()), imported)
    }

    @Test
    fun `a failed statement is a value, and the database stays open`() {
        store.execute { }
        device.lock()
        assertIs<DbResult.Failed>(store.execute { it.legacyImportQueries.selectImported().executeAsList() })
        assertFailsWith<DatabaseUnavailable>("the old zones' read throws it") {
            store.use { it.legacyImportQueries.selectImported().executeAsList() }
        }

        device.lock(on = false)
        assertEquals(DbResult.Ok(Unit), store.execute { })
        assertEquals(listOf(LEDGER_DB_NAME), device.opened, "a failed statement is not a failed open")
    }
}
