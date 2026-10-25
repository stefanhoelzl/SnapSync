package app.snapsync.data.store

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.db.SqlDriver
import app.snapsync.data.store.db.EventAlbum
import app.snapsync.data.store.db.EventCheck
import app.snapsync.data.store.db.LedgerDatabase
import app.snapsync.data.store.db.LedgerRow
import app.snapsync.data.store.db.Membership
import app.snapsync.data.store.db.PendingLeave
import app.snapsync.data.store.db.UploadedManifest
import app.snapsync.ports.Databases
import app.snapsync.ports.DbResult

/** The shared database's file — runtime identity (`docs/architecture.md`, section 9): a device holds it. */
const val LEDGER_DB_NAME: String = "ledger.db"

/** One record's legacy home, as `legacyImport` names it once its rows are in the shared database. */
enum class LegacySource(val key: String) {
    MEMBERSHIP("membership"),
    MANIFEST("manifest"),
    PENDING_LEAVES("pendingLeaves"),
    ALBUM("album"),
    CHECKS("checks"),
}

/**
 * The app's one-time copy of the shared records' legacy homes into the open database — a shim that lives in the old
 * zone (`LegacyRecords`, `PENDING_CLEANUPS.md`): the store runs it once per process, right after the app's open.
 */
interface LegacyCopy {
    fun copyInto(db: LedgerDatabase)
}

/** Whether the shared database's records can be read now. */
sealed interface SharedRecordsReadiness {
    /** Every record lives in the database. */
    data object Ready : SharedRecordsReadiness

    /** Some record is still in its legacy file or key: the app copies it on its next run. */
    data object LegacyPending : SharedRecordsReadiness

    /** The database cannot be opened or read now (a locked device). [detail] is diagnostics only. */
    data class Unavailable(val detail: String) : SharedRecordsReadiness
}

/**
 * **`ledger.db` for this process** (`docs/architecture.md`, "Storage") — the one database
 * both processes open read-write, holding the upload rows AND the small records they share: the membership, the
 * uploaded manifest, the pending leaves, the event albums and the event checks. One owner per process, so every service
 * over it shares one open, and one consistency domain — SQLite's transactions — covers them all.
 *
 * Opened on first use and kept only once open ([OwnedDatabase]). [legacy] is the app's one-time copy of the records'
 * older homes (files and Preferences keys), run once per process right after the open; the extension passes none and
 * only asks [readiness] — it never reads a legacy file.
 */
class SharedDatabase(databases: Databases, legacy: LegacyCopy?) {
    private val owned = object : OwnedDatabase<LedgerDatabase>(databases, LEDGER_DB_NAME, LedgerDatabase.Schema) {
        override fun over(driver: SqlDriver) = sharedDatabaseOver(driver)

        override fun prepared(database: LedgerDatabase) {
            legacy?.copyInto(database)
        }
    }

    /** Runs [statements] on the database: a failed open or a failed statement is [DbResult.Failed]. */
    fun <T> execute(statements: (LedgerDatabase) -> T): DbResult<T> = owned.execute(statements)

    /** Whether [source] lives in this database now, rather than in its legacy file or key. */
    fun imported(source: LegacySource): DbResult<Boolean> =
        execute { source.key in it.legacyImportQueries.selectImported().executeAsList() }

    /**
     * Whether the records can be read here: [SharedRecordsReadiness.LegacyPending]
     * until the app has copied every legacy source — the extension then pauses rather than read a table that is not
     * authoritative yet — and [SharedRecordsReadiness.Unavailable] when the database cannot be read.
     */
    fun readiness(): SharedRecordsReadiness =
        when (val imported = execute { it.legacyImportQueries.selectImported().executeAsList().toSet() }) {
            is DbResult.Ok -> readinessOf(imported.value)
            is DbResult.Failed -> SharedRecordsReadiness.Unavailable(imported.detail)
        }

    private fun readinessOf(imported: Set<String>): SharedRecordsReadiness =
        when (LegacySource.entries.all { it.key in imported }) {
            true -> SharedRecordsReadiness.Ready
            false -> SharedRecordsReadiness.LegacyPending
        }
}

/** The generated database over [driver] with its column adapters wired — the one place that knows each encoding. */
fun sharedDatabaseOver(driver: SqlDriver): LedgerDatabase = LedgerDatabase(
    driver,
    eventAlbumAdapter = EventAlbum.Adapter(eventIdAdapter = EventIdColumnAdapter),
    eventCheckAdapter = EventCheck.Adapter(eventIdAdapter = EventIdColumnAdapter),
    ledgerRowAdapter = LedgerRow.Adapter(assetIdAdapter = AssetIdColumnAdapter, stateAdapter = EnumColumnAdapter()),
    membershipAdapter = Membership.Adapter(
        eventIdAdapter = EventIdColumnAdapter,
        minPhotoDateAdapter = CaptureCutoffColumnAdapter,
        startsAtAdapter = EventStartColumnAdapter,
        endsAtAdapter = EventEndColumnAdapter,
        maxPhotoDateAdapter = CaptureCeilingColumnAdapter,
        deletesAtAdapter = DeletesAtColumnAdapter,
        directionAdapter = EnumColumnAdapter(),
        openUntilAdapter = OpenUntilColumnAdapter,
    ),
    pendingLeaveAdapter = PendingLeave.Adapter(eventIdAdapter = EventIdColumnAdapter),
    uploadedManifestAdapter = UploadedManifest.Adapter(eventIdAdapter = EventIdColumnAdapter),
)
