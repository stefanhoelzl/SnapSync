package app.snapsync.feature.download

import app.snapsync.ports.DownloadStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The real [DownloadStatusSource] over the [DownloadStore]: `downloaded` = imported foreign assets,
 * `total` = all foreign assets known for the event (pending + imported). [refresh] re-reads both
 * counts; the composition root calls it on foreground entry and after a reconcile/import.
 *
 * Seeded [DownloadProgress.UNREAD]: before the first [refresh] this source has read nothing, and
 * saying so is what stops the download arrow from hiding — and the whole screen from settling — over
 * counts nobody took.
 */
class StoreDownloadStatusSource(private val store: DownloadStore) : DownloadStatusSource {
    private val _progress = MutableStateFlow(DownloadProgress.UNREAD)
    override val progress: StateFlow<DownloadProgress> = _progress.asStateFlow()

    /**
     * ONE read, so the published projection cannot be a torn composite of counts taken at three different
     * instants (capability `download-store`).
     *
     * **Keep-last-good on failure**, matching `ReadingLedgerCountsSource` — the group's other member — rather
     * than throwing (capability `sync-status`, "The cheap local status reads are one bounded group"). Two
     * callers make that load-bearing: the foreground refresh runs as one child of the `Foreground` flow's
     * `coroutineScope`, so an escaping failure would cancel its SIBLINGS (the download reconcile, the
     * staged-byte reclaim, the membership refresh) — which the spec forbids outright; and the poll ticks this
     * every cadence, where a store error would otherwise be raised over and over.
     *
     * A failed read leaves the last good value standing, which is the honest answer: it is still the most
     * recent thing this source actually read. It never regresses to a placeholder, and never to a counted
     * zero — the distinction `DownloadProgress.UNREAD` exists to protect.
     */
    override suspend fun refresh() {
        val counts = runCatching { store.counts() }.getOrNull() ?: return
        _progress.value = DownloadProgress(
            downloaded = counts.imported,
            total = counts.stillArriving,
            inFlight = counts.inFlight,
        )
    }
}
