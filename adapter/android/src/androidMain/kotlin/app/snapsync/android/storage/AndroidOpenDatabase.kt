package app.snapsync.android.storage

import android.database.SQLException
import app.cash.sqldelight.db.SqlDriver
import app.snapsync.ports.DbResult
import app.snapsync.ports.OpenDatabase

/**
 * A database [AndroidDatabases] opened: the platform's SQLite tells a refused statement by an [SQLException] — a
 * corruption found after the open included, which the adapter refuses as one — answered as a value.
 */
internal class AndroidOpenDatabase(private val driver: SqlDriver) : OpenDatabase {
    override fun <T> execute(statements: (SqlDriver) -> T): DbResult<T> = try {
        DbResult.Ok(statements(driver))
    } catch (refused: SQLException) {
        DbResult.Failed("${refused::class.simpleName}: ${refused.message}")
    }
}
