package app.snapsync.data.store

import app.snapsync.data.store.db.LedgerDatabase
import app.snapsync.ports.DbResult
import kotlin.test.assertIs

// A test's statements on a store it set up to succeed: the answer, or the test fails naming the store's failure.

/** [statements]' answer on [this] shared database. */
internal fun <T> SharedDatabase.use(
    statements: (LedgerDatabase) -> T,
): T = assertIs<DbResult.Ok<T>>(execute(statements)).value

/** [statements]' answer on [this] owned database. */
internal fun <D, T> OwnedDatabase<D>.use(statements: (D) -> T): T = assertIs<DbResult.Ok<T>>(execute(statements)).value
