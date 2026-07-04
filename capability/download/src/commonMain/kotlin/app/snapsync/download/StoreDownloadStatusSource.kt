package app.snapsync.download

import app.snapsync.downloadstore.DownloadStore
import app.snapsync.status.DownloadProgress
import app.snapsync.status.DownloadStatusSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The real [DownloadStatusSource] over the [DownloadStore]: `downloaded` = imported foreign assets,
 * `total` = all foreign assets known for the event (pending + imported). [refresh] re-reads both
 * counts; the composition root calls it on foreground entry and after a reconcile/import.
 */
class StoreDownloadStatusSource(private val store: DownloadStore) : DownloadStatusSource {
    private val _progress = MutableStateFlow(DownloadProgress(0, 0))
    override val progress: StateFlow<DownloadProgress> = _progress.asStateFlow()

    override suspend fun refresh() {
        _progress.value = DownloadProgress(
            downloaded = store.importedCount(),
            total = store.assetCount(),
            inFlight = store.inFlightCount(),
        )
    }
}
