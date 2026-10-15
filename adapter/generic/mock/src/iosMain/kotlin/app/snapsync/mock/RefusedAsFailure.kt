package app.snapsync.mock

import app.snapsync.ports.DbResult
import co.touchlab.sqliter.interop.SQLiteException

internal actual fun <T> refusedAsFailure(statements: () -> T): DbResult<T> = try {
    DbResult.Ok(statements())
} catch (refused: StatementRefused) {
    DbResult.Failed("${refused::class.simpleName}: ${refused.message}")
} catch (refused: SQLiteException) {
    DbResult.Failed("${refused::class.simpleName}: ${refused.message}")
}
