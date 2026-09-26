package app.snapsync.fake

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

// sqlite-jdbc's in-memory URL: the driver keeps ONE static connection for it, so the database lives as long as the
// driver does.
internal actual fun newInMemoryDriver(): SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
