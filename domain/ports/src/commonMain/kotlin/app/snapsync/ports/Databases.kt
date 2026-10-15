package app.snapsync.ports

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.snapsync.inbound.CompositionOnly
import app.snapsync.inbound.Inbound

/**
 * **This process's SQLite databases, by name** — one external system, and nothing decided here
 * (`docs/architecture.md`, "Ports are one external system each"). What a database holds, when it is opened
 * and what a failure means are the services' business (`:domain:services`); this port only opens.
 *
 * A database lives where the platform keeps shared, background-readable data: on iOS the App-Group container,
 * so the app and the upload extension open the same file. The adapter owns that placement and the file
 * protection; the name is the only address that crosses this seam.
 *
 * **Opening is I/O and can fail**, so it is never done at composition: a service opens on first use and caches
 * only an [DbOpen.Opened] (a locked device's failure is retried on the next use rather than fixed for the
 * process).
 *
 * [DbOpen] separates four answers, and conflating two of them is how an unreadable database would be mistaken
 * for an empty one (`docs/architecture.md`, "Absence is never silent"):
 *
 * - a **read-write** open creates a missing database and migrates an old one, so it answers only
 *   [DbOpen.Opened] or [DbOpen.Failed];
 * - a **read-only** open ([readOnly]) never creates, never migrates and never writes: it answers
 *   [DbOpen.Missing] for a database nobody has created yet and [DbOpen.OldSchema] for one whose version is
 *   below [schema]'s — the process that owns the migration has not run since the update;
 * - a database that exists but cannot be opened (locked, unreadable, not a database) is [DbOpen.Failed] on
 *   **both** paths, never [DbOpen.Missing].
 *
 * **A statement can fail on a database already open** — the write lock held by the other process past SQLite's busy
 * timeout, a full disk, a file gone bad — and the platform's driver tells it by a throw. The adapter catches that throw
 * where it knows the platform's exception, inside [OpenDatabase.execute], and answers it as [DbResult.Failed]: above
 * this port a failed statement is a value, as every expected failure is (`docs/architecture.md`, "A port answers every
 * expected failure as a value"). The database stays open, and nothing the failed statements asked for happened
 * when they ran in one transaction.
 *
 * **What another process committed arrives through [inbound]** ([DatabaseHandlers.onChangedElsewhere]): the app and
 * the upload extension write the same files, and neither process's own reads would otherwise learn of the other's
 * commit until it re-read on its own. A commit is a transaction that finished — the moment SQLDelight notifies the
 * driver's listeners, after the commit and never for a rollback; this process's own commits are never delivered back
 * to it. A missed delivery costs freshness, never correctness: every entry re-reads what it acts on.
 */
interface Databases : EventPort<DatabaseHandlers> {

    fun open(name: String, schema: SqlSchema<QueryResult.Value<Unit>>, readOnly: Boolean): DbOpen
}

/** What a [Databases] tells the process it belongs to. */
class DatabaseHandlers @CompositionOnly constructor(
    /**
     * Another process committed to the database of the name it was opened by. Never called for this process's own
     * commits; several commits may arrive as one call. Called off the main thread.
     */
    val onChangedElsewhere: Inbound<String, Unit>,
)

/** One [Databases.open] answer. */
sealed interface DbOpen {

    /** The database is open at [Databases.open]'s schema version. The caller keeps [database] for the process. */
    class Opened(val database: OpenDatabase) : DbOpen

    /** Read-only only: no database of that name exists. Nothing was created. */
    data object Missing : DbOpen

    /** Read-only only: the database exists at a version below the schema's. Nothing was migrated. */
    data object OldSchema : DbOpen

    /** The database could not be opened. [detail] is diagnostics only — log it, never branch on it. */
    data class Failed(val detail: String) : DbOpen
}

/**
 * A database [Databases.open] opened: the only way to its statements. Each [execute] hands [statements] the platform's
 * driver; the generated queries a store builds over it run inside, so a statement's failure is caught where the
 * platform's exception is known. Neither the driver nor anything built over it is used outside the block.
 */
interface OpenDatabase {

    /**
     * Runs [statements] on this database. A statement the platform refuses answers [DbResult.Failed]; any other throw
     * from [statements] is the caller's own and passes through.
     */
    fun <T> execute(statements: (SqlDriver) -> T): DbResult<T>
}

/** One [OpenDatabase.execute] answer. */
sealed interface DbResult<out T> {

    /** Every statement ran; [value] is what [OpenDatabase.execute]'s block answered. */
    data class Ok<out T>(val value: T) : DbResult<T>

    /** A statement failed; the database stays open. [detail] is diagnostics only — log it, never branch on it. */
    data class Failed(val detail: String) : DbResult<Nothing>
}
