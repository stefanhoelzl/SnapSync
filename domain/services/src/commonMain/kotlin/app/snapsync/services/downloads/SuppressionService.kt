package app.snapsync.services.downloads

import app.snapsync.model.AssetId
import app.snapsync.model.SuppressionReadiness
import app.snapsync.ports.Databases
import app.snapsync.ports.DbOpen
import app.snapsync.services.databases.DatabaseUnavailable
import app.snapsync.services.downloads.db.DownloadDatabase
import app.snapsync.services.downloads.db.DownloadStoreQueries
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The upload extension's echo-suppression read (capability `receiving-photos`): the download store's
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
class SuppressionService(private val databases: Databases) : SuppressionSource {

    private val lock = Mutex()
    private var queries: DownloadStoreQueries? = null

    override suspend fun readiness(): SuppressionReadiness = when (val found = open()) {
        is Found.Open, Found.Missing -> SuppressionReadiness.Ready
        Found.OldSchema -> SuppressionReadiness.OldSchema
        is Found.Failed -> SuppressionReadiness.Unavailable(found.detail)
    }

    override suspend fun suppressedLocalIds(): Set<AssetId> = when (val found = open()) {
        is Found.Open -> found.queries.suppressedLocalIds().executeAsList().mapNotNull { it }.toSet()
        Found.Missing -> emptySet()
        // Never "nothing suppressed": either would upload downloaded photos back into the event.
        Found.OldSchema -> throw DatabaseUnavailable(DOWNLOADS_DB_NAME, "older schema than this build's")
        is Found.Failed -> throw DatabaseUnavailable(DOWNLOADS_DB_NAME, found.detail)
    }

    private suspend fun open(): Found = lock.withLock {
        queries?.let { return@withLock Found.Open(it) }
        when (val opened = databases.open(DOWNLOADS_DB_NAME, DownloadDatabase.Schema, readOnly = true)) {
            is DbOpen.Opened -> Found.Open(DownloadDatabase(opened.driver).downloadStoreQueries.also { queries = it })
            DbOpen.Missing -> Found.Missing
            DbOpen.OldSchema -> Found.OldSchema
            is DbOpen.Failed -> Found.Failed(opened.detail)
        }
    }

    private sealed interface Found {
        class Open(val queries: DownloadStoreQueries) : Found
        data object Missing : Found
        data object OldSchema : Found
        class Failed(val detail: String) : Found
    }
}
