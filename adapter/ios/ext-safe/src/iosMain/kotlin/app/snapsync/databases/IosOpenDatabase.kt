package app.snapsync.databases

import app.cash.sqldelight.db.SqlDriver
import app.snapsync.ports.DbResult
import app.snapsync.ports.OpenDatabase
import co.touchlab.sqliter.interop.SQLiteException

/** A database [IosDatabases] opened: SQLiter tells a refused statement by a [SQLiteException], answered as a value. */
internal class IosOpenDatabase(private val driver: SqlDriver) : OpenDatabase {
    override fun <T> execute(statements: (SqlDriver) -> T): DbResult<T> = try {
        DbResult.Ok(statements(driver))
    } catch (refused: SQLiteException) {
        DbResult.Failed("${refused::class.simpleName}: ${refused.message}")
    }
}
