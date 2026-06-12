package app.snapsync.engine

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.snapsync.engine.db.LedgerDatabase

class SqlDelightLedgerBackendTest : LedgerBackendContract() {

    override fun createBackend(): LedgerBackend {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        LedgerDatabase.Schema.create(driver)
        return SqlDelightLedgerBackend(LedgerDatabase(driver))
    }
}
