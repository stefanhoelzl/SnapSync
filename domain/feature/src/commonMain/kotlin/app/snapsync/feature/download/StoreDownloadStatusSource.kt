package app.snapsync.feature.download

import app.snapsync.feature.download.readmodel.DownloadProgress
import app.snapsync.feature.download.readmodel.DownloadStatusSource
import app.snapsync.model.runCatchingCancellable
import app.snapsync.services.downloads.DownloadService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The real [DownloadStatusSource] over the [DownloadService]: `downloaded` = imported foreign assets,
 * `total` = all foreign assets known for the event (pending + imported). [refresh] re-reads both
 * counts; the composition root calls it on foreground entry and after a reconcile/import.
 *
 * Seeded [DownloadProgress.UNREAD]: before the first [refresh] this source has read nothing, and
 * saying so is what stops the download arrow from hiding — and the whole screen from settling — over
 * counts nobody took.
 */
class StoreDownloadStatusSource(
    private val store: DownloadService,
    // The current membership's event, or `null` with none. The store keeps every past event's imported rows
    // (they are the suppression handles), so the counts must be scoped to the event on screen: unscoped,
    // "received" was every foreign photo the device ever imported.
    private val currentEvent: () -> String?,
) : DownloadStatusSource {
    private val _progress = MutableStateFlow(DownloadProgress.UNREAD)
    override val progress: StateFlow<DownloadProgress> = _progress.asStateFlow()

    /**
     * ONE read, so the published projection cannot be a torn composite of counts taken at three different
     * instants.
     *
     * **Keep-last-good on failure**, matching `ReadingLedgerCountsSource` — the other member of the one bounded
     * group the cheap local status reads form — rather than throwing. Two callers make that load-bearing: the
     * foreground refresh runs as one child of the `Foreground` flow's `coroutineScope`, so an escaping failure
     * would cancel its SIBLINGS (the download reconcile, the staged-byte reclaim, the membership refresh) — which
     * is forbidden outright; and the poll ticks this
     * every cadence, where a store error would otherwise be raised over and over.
     *
     * A failed read leaves the last good value standing, which is the honest answer: it is still the most
     * recent thing this source actually read. It never regresses to a placeholder, and never to a counted
     * zero — the distinction `DownloadProgress.UNREAD` exists to protect.
     */
    override suspend fun refresh() {
        val eventId = currentEvent() ?: return
        val counts = runCatchingCancellable { store.counts(eventId) }.getOrNull() ?: return
        _progress.value = DownloadProgress(
            downloaded = counts.imported,
            total = counts.stillArriving,
            inFlight = counts.inFlight,
        )
    }
}
