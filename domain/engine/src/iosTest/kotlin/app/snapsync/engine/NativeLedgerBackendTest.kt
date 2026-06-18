package app.snapsync.engine

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.snapsync.engine.db.LedgerDatabase

/**
 * Runs the shared [LedgerBackendContract] against the real native-driver-backed
 * [SqlDelightLedgerBackend] — the gap CI's `ios-test` job exists for: the native driver, schema
 * creation, and the enum / `kotlin.time.Instant` column adapters on Kotlin/Native. In-memory for
 * test isolation; the on-disk path is exercised by the manual app run.
 */
class NativeLedgerBackendTest : LedgerBackendContract() {

    override fun createBackend(): LedgerBackend {
        val driver = NativeSqliteDriver(
            LedgerDatabase.Schema,
            "test.db",
            onConfiguration = { it.copy(inMemory = true) },
        )
        return SqlDelightLedgerBackend(LedgerDatabase(driver))
    }
}
