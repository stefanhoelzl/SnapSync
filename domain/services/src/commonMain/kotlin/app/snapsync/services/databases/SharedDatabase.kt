package app.snapsync.services.databases

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.snapsync.model.SharedRecordsReadiness
import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.Databases
import app.snapsync.services.ledger.LEDGER_DB_NAME
import app.snapsync.services.ledger.db.LedgerDatabase
import app.snapsync.services.ledger.db.LedgerRow
import app.snapsync.services.ledger.db.Membership

/**
 * **`ledger.db` for this process** (`docs/architecture.md`, "Storage") — the one database
 * both processes open read-write, holding the upload rows AND the small records they share: the membership, the
 * uploaded manifest, the pending leaves, the event albums and the event checks. One owner per process, so every service
 * over it shares one open, and one consistency domain — SQLite's transactions — covers them all.
 *
 * **Opened on first use, never at construction**: building the composition opens no database (a locked background
 * launch must not be forced into an open early). A failed open throws [DatabaseUnavailable] from that use and is
 * retried on the next — only a success is kept.
 *
 * [legacy] is the app's one-time copy of the records' older homes (files and Preferences keys), run once per process
 * right after the open; the extension passes none and only asks [readiness] — it never reads a legacy file.
 */
class SharedDatabase(
    private val databases: Databases,
    private val legacy: LegacyRecords?,
) {
    private val database: LedgerDatabase by lazy {
        sharedDatabaseOver(
            databases.openOwned(LEDGER_DB_NAME, LedgerDatabase.Schema),
        ).also { db -> legacy?.copyInto(db) }
    }

    /** Runs [block] on the open database. Throws [DatabaseUnavailable] when it cannot be opened (a locked device). */
    fun <T> use(block: (LedgerDatabase) -> T): T = block(database)

    /** Whether [source] lives in this database now, rather than in its legacy file or key. */
    fun imported(source: LegacySource): Boolean =
        use { source.key in it.legacyImportQueries.selectImported().executeAsList() }

    /**
     * Whether the records can be read here: [SharedRecordsReadiness.LegacyPending]
     * until the app has copied every legacy source — the extension then pauses rather than read a table that is not
     * authoritative yet — and [SharedRecordsReadiness.Unavailable] when the database cannot be opened.
     */
    fun readiness(): SharedRecordsReadiness = runCatchingCancellable {
        use { it.legacyImportQueries.selectImported().executeAsList().toSet() }
    }.fold(
        onSuccess = { imported ->
            if (LegacySource.entries.all { it.key in imported }) {
                SharedRecordsReadiness.Ready
            } else {
                SharedRecordsReadiness.LegacyPending
            }
        },
        onFailure = { SharedRecordsReadiness.Unavailable("$it") },
    )
}

/** The generated database over [driver] with its column adapters wired — the one place that knows each encoding. */
internal fun sharedDatabaseOver(driver: SqlDriver): LedgerDatabase = LedgerDatabase(
    driver,
    ledgerRowAdapter = LedgerRow.Adapter(assetIdAdapter = AssetIdColumnAdapter, stateAdapter = EnumColumnAdapter()),
    membershipAdapter = Membership.Adapter(directionAdapter = EnumColumnAdapter()),
)
