package app.snapsync.services.downloads

import app.snapsync.data.store.Stored
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.event.EventId
import app.snapsync.model.SuppressionReadiness
import app.snapsync.ports.Databases
import app.snapsync.ports.DbOpen
import app.snapsync.ports.DbResult
import app.snapsync.ports.OpenDatabase
import app.snapsync.services.databases.DatabaseUnavailable
import app.snapsync.services.databases.held
import app.snapsync.services.downloads.db.DownloadDatabase
import app.snapsync.services.downloads.db.DownloadStoreQueries
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The upload extension's echo-suppression read: the download store's
 * suppression projection, opened **read-only**.
 *
 * The extension never creates, migrates or writes the download store — the app is its one writer — because
 * the extension may run before an updated app has, and a migration the app did not run is one nobody sequenced
 * with the app's own writes. What it finds decides the cycle (see [SuppressionReadiness]):
 *
 * - **no store** — nothing was ever downloaded on this device, so nothing is suppressed; the cycle runs;
 * - **an older schema** — the app has not run since the update: the cycle pauses and asks to be invoked
 *   again. Running without suppression would upload this device's downloaded photos back into the event;
 * - **unopenable** — "I could not look": no upload this run.
 *
 * Opened on first use and kept only once open; every other answer is asked again next time, so a store the app
 * creates or migrates meanwhile is seen at once.
 */
class SuppressionService(private val databases: Databases) : SuppressionSource, RecognitionSource {

    private val lock = Mutex()
    private var opened: OpenDatabase? = null

    override suspend fun readiness(): SuppressionReadiness = when (val found = open()) {
        is Found.Open, Found.Missing -> SuppressionReadiness.Ready
        Found.OldSchema -> SuppressionReadiness.OldSchema
        is Found.Failed -> SuppressionReadiness.Unavailable(found.detail)
    }

    override suspend fun suppressedLocalIds(): Set<AssetId> = when (val found = open()) {
        is Found.Open -> found.read {
            it.suppressedLocalIds().executeAsList().filterNotNull().mapTo(
                mutableSetOf(),
            ) { held(it) }
        }
        Found.Missing -> emptySet()
        // Never "nothing suppressed": either would upload downloaded photos back into the event.
        Found.OldSchema -> throw DatabaseUnavailable(DOWNLOADS_DB_NAME, "older schema than this build's")
        is Found.Failed -> throw DatabaseUnavailable(DOWNLOADS_DB_NAME, found.detail)
    }

    override suspend fun isRecognised(eventId: EventId): Boolean = when (val found = open()) {
        is Found.Open -> found.read { it.isRecognised(Stored.Valid(eventId)).executeAsOne() }
        // No store: the app has run no recognition on this device, so nothing is recognised.
        Found.Missing -> false
        Found.OldSchema -> throw DatabaseUnavailable(DOWNLOADS_DB_NAME, "older schema than this build's")
        is Found.Failed -> throw DatabaseUnavailable(DOWNLOADS_DB_NAME, found.detail)
    }

    private suspend fun open(): Found = lock.withLock {
        opened?.let { return@withLock Found.Open(it) }
        when (val open = databases.open(DOWNLOADS_DB_NAME, DownloadDatabase.Schema, readOnly = true)) {
            is DbOpen.Opened -> Found.Open(open.database.also { opened = it })
            DbOpen.Missing -> Found.Missing
            DbOpen.OldSchema -> Found.OldSchema
            is DbOpen.Failed -> Found.Failed(open.detail)
        }
    }

    private sealed interface Found {
        class Open(private val database: OpenDatabase) : Found {
            /** [statements]' answer over the store's queries; a failed statement is thrown as [DatabaseUnavailable]. */
            fun <T> read(statements: (DownloadStoreQueries) -> T): T =
                when (val result = database.execute { statements(DownloadDatabase(it).downloadStoreQueries) }) {
                    is DbResult.Ok -> result.value
                    is DbResult.Failed -> throw DatabaseUnavailable(DOWNLOADS_DB_NAME, result.detail)
                }
        }
        data object Missing : Found
        data object OldSchema : Found
        class Failed(val detail: String) : Found
    }
}
