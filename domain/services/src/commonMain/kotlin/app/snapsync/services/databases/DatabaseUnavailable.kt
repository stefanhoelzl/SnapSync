package app.snapsync.services.databases

import app.snapsync.data.store.LEDGER_DB_NAME
import app.snapsync.data.store.OwnedDatabase
import app.snapsync.data.store.SharedDatabase
import app.snapsync.data.store.db.LedgerDatabase
import app.snapsync.ports.DbResult

/**
 * A database could not be opened or a statement on it failed: "I could not look" (`docs/architecture.md`, "Absence is
 * never silent") — MIGRATING, what [use] throws to the old zones, whose callers already handle a store that cannot be
 * reached (the cycle's gate reads the ledger under a guard; a locked device's read fails the same way it always did).
 */
class DatabaseUnavailable(val name: String, val detail: String) :
    IllegalStateException("database $name unavailable: $detail")

/**
 * [statements]' answer, a failure thrown as [DatabaseUnavailable] — MIGRATING: the old zones' reads and writes, written
 * against a store that throws, until each moves into the data layer and takes the [DbResult] itself
 * (`PENDING_CLEANUPS.md`). The data layer holds no throw (`docs/patterns.md`, "Invalid program state"), so the shim
 * lives here.
 */
fun <D, T> OwnedDatabase<D>.use(statements: (D) -> T): T = held(name, execute(statements))

/** [OwnedDatabase.use] over the shared database. */
fun <T> SharedDatabase.use(statements: (LedgerDatabase) -> T): T = held(LEDGER_DB_NAME, execute(statements))

private fun <T> held(name: String, result: DbResult<T>): T = when (result) {
    is DbResult.Ok -> result.value
    is DbResult.Failed -> throw DatabaseUnavailable(name, result.detail)
}
