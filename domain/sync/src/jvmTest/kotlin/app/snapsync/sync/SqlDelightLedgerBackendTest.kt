package app.snapsync.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.snapsync.sync.db.LedgerDatabase

class SqlDelightLedgerBackendTest : LedgerBackendContract() {

    override fun createBackend(): LedgerBackend {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LedgerDatabase.Schema.create(driver)
        return SqlDelightLedgerBackend(LedgerDatabase(driver))
    }
}
