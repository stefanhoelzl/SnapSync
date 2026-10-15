package app.snapsync.data.store

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.snapsync.ports.Databases
import app.snapsync.ports.DbOpen
import app.snapsync.ports.DbResult
import app.snapsync.ports.OpenDatabase
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * A database this process opens **read-write** for the store that owns it — created if missing, migrated if old — and
 * the generated [D] the store builds [over] its driver, once.
 *
 * **Opened on first use, never at construction** (a locked background launch must not be forced into an open early),
 * one open at a time — `lazy`'s synchronized initialisation keeps two first uses from opening twice — and kept only
 * when it succeeded: a failed open is answered and replaced by a fresh attempt for the next use, so a locked device's
 * failure is retried rather than fixed for the process. [prepared] runs once on the database right after the open (the
 * shared database's legacy copy); it failing fails the open.
 */
@OptIn(ExperimentalAtomicApi::class)
abstract class OwnedDatabase<D>(
    private val databases: Databases,
    private val name: String,
    private val schema: SqlSchema<QueryResult.Value<Unit>>,
) {
    /** The generated database over [driver], its column adapters wired. */
    protected abstract fun over(driver: SqlDriver): D

    /** What the store does once on the database, right after the open: nothing, unless it says otherwise. */
    protected open fun prepared(database: D) = Unit

    private val attempt = AtomicReference(lazy(::openOnce))

    /** Runs [statements] on the database: a failed open or a failed statement is [DbResult.Failed]. */
    fun <T> execute(statements: (D) -> T): DbResult<T> {
        val current = attempt.load()
        return when (val open = current.value) {
            is DbResult.Ok -> open.value.handle.execute { statements(open.value.database) }
            is DbResult.Failed -> open.also { attempt.compareAndSet(current, lazy(::openOnce)) }
        }
    }

    private fun openOnce(): DbResult<Kept<D>> = when (val opened = databases.open(name, schema, readOnly = false)) {
        is DbOpen.Opened -> opened.database.execute { driver -> Kept(opened.database, over(driver).also(::prepared)) }
        is DbOpen.Failed -> DbResult.Failed(opened.detail)
        // Only a read-only open may answer these; an adapter that does breaks the `Databases` contract.
        DbOpen.Missing, DbOpen.OldSchema ->
            DbResult.Failed("a read-write open answered $opened, which only a read-only open may")
    }

    /**
     * [statements]' answer, a failure thrown as [DatabaseUnavailable] — MIGRATING: the old zones' reads and writes,
     * written against a store that throws, until each moves into the data layer and takes the [DbResult] itself.
     */
    fun <T> use(statements: (D) -> T): T = when (val result = execute(statements)) {
        is DbResult.Ok -> result.value
        is DbResult.Failed -> throw DatabaseUnavailable(name, result.detail)
    }

    /** The open database and the generated [database] over its driver, used only inside its [handle]'s execute. */
    private class Kept<D>(val handle: OpenDatabase, val database: D)
}
