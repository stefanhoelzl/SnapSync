package app.snapsync.services.databases

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.snapsync.ports.Databases
import app.snapsync.ports.DbOpen

/**
 * A database could not be opened: "I could not look" (`docs/architecture.md`, "Absence is never silent").
 *
 * Thrown by a store's first use, where today's callers already handle a store that cannot be reached (the
 * cycle's gate reads the ledger under a guard; a locked device's read fails the same way it always did). It is
 * never cached: the store's next use opens again.
 */
class DatabaseUnavailable(val name: String, val detail: String) :
    IllegalStateException("database $name unavailable: $detail")

/**
 * Opens [name] **read-write** for the store that owns it: created if missing, migrated if old. The only answers a
 * read-write open may give are [DbOpen.Opened] and [DbOpen.Failed]; anything else is an adapter breaking the
 * `Databases` contract, and is reported as unavailable rather than guessed around.
 */
internal fun Databases.openOwned(name: String, schema: SqlSchema<QueryResult.Value<Unit>>): SqlDriver =
    when (val opened = open(name, schema, readOnly = false)) {
        is DbOpen.Opened -> opened.driver
        is DbOpen.Failed -> throw DatabaseUnavailable(name, opened.detail)
        DbOpen.Missing, DbOpen.OldSchema ->
            throw DatabaseUnavailable(name, "a read-write open answered $opened, which only a read-only open may")
    }
