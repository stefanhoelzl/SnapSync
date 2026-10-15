package app.snapsync.databases

import app.cash.sqldelight.db.SqlDriver
import app.snapsync.ports.DbResult
import app.snapsync.ports.OpenDatabase
import java.sql.SQLException

/** A database [JdbcDatabases] opened: sqlite-jdbc tells a refused statement by an [SQLException], answered as a value. */
internal class JdbcOpenDatabase(private val driver: SqlDriver) : OpenDatabase {
    override fun <T> execute(statements: (SqlDriver) -> T): DbResult<T> = try {
        DbResult.Ok(statements(driver))
    } catch (refused: SQLException) {
        DbResult.Failed("${refused::class.simpleName}: ${refused.message}")
    }
}
