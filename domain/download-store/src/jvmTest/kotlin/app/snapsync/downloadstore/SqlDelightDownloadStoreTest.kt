package app.snapsync.downloadstore

import app.snapsync.ports.DownloadStore

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.snapsync.downloadstore.db.DownloadDatabase

/** Runs the shared [DownloadStoreContract] against the real SQLDelight store over an in-memory JDBC driver. */
class SqlDelightDownloadStoreTest : DownloadStoreContract() {
    override fun createStore(): DownloadStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DownloadDatabase.Schema.create(driver)
        return SqlDelightDownloadStore(DownloadDatabase(driver))
    }
}
