package app.snapsync.mock

import android.database.SQLException
import app.snapsync.ports.DbResult

internal actual fun <T> refusedAsFailure(statements: () -> T): DbResult<T> = try {
    DbResult.Ok(statements())
} catch (refused: StatementRefused) {
    DbResult.Failed("${refused::class.simpleName}: ${refused.message}")
} catch (refused: SQLException) {
    DbResult.Failed("${refused::class.simpleName}: ${refused.message}")
}
