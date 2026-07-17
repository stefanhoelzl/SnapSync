package app.snapsync.engine

import app.snapsync.ports.LedgerStore

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.snapsync.engine.db.LedgerDatabase

/**
 * Runs the shared [LedgerStoreContract] against the real native-driver-backed
 * [SqlDelightLedgerStore] — the gap CI's `ios-test` job exists for: the native driver, schema
 * creation, and the enum column adapter on Kotlin/Native. In-memory for test isolation; the on-disk
 * path is exercised by the manual app run.
 */
class NativeLedgerStoreTest : LedgerStoreContract() {

    override fun createBackend(): LedgerStore {
        // A UNIQUE name per backend. An in-memory NativeSqliteDriver keyed by a fixed name uses a
        // shared-cache `:memory:` db, so every createBackend() would reuse one database and leak
        // rows across tests (unlike JdbcSqliteDriver.IN_MEMORY, which is private per driver — why
        // the JVM contract passed). The counter is process-global (companion) because kotlin.test
        // builds a fresh test-class instance per @Test.
        val driver = NativeSqliteDriver(
            LedgerDatabase.Schema,
            "test-${nextDb++}.db",
            onConfiguration = { it.copy(inMemory = true) },
        )
        return SqlDelightLedgerStore(LedgerDatabase(driver))
    }

    private companion object {
        var nextDb = 0
    }
}
