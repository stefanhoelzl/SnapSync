package app.snapsync.mock

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File

// sqlite-jdbc's in-memory URL: the driver keeps ONE static connection for it, so the database lives as long as the
// driver does.
internal actual fun newInMemoryDriver(): SqlDriver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

// A file under [directory]: the persisted mock's databases (`mix/`).
internal actual fun newFileDriver(directory: String, name: String): SqlDriver {
    File(directory).mkdirs()
    return JdbcSqliteDriver("jdbc:sqlite:${File(directory, name).path}")
}

internal actual fun databaseFileExists(directory: String, name: String): Boolean = File(directory, name).isFile
