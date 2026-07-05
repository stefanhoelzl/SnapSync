package app.snapsync.status

import app.snapsync.gallery.GalleryResourceEnumerator
import app.snapsync.gallery.GalleryStatusSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The own-device upload **total** `N` (capability `sync-status`): the count of this device's OWN
 * qualifying assets. It is enumeration-only — **no** storage LIST — so it stays honest the instant a
 * photo is taken, before the background extension uploads anything (completeness comes separately from
 * the ledger, see [LedgerCountsSource]).
 *
 * **The upload universe is this device's OWN photos** — the gallery minus any asset this device
 * *downloaded and imported* from other contributors (capability `photo-download`). Downloaded photos
 * live in the library (so the raw gallery count includes them) but are **suppressed from upload**, so
 * counting them would peg upload progress permanently below 100% (e.g. "9 of 11" when 2 are
 * downloaded). [suppressedLocalIds] (the download store's `createdLocalId` set, byte-identical to the
 * enumerator's `assetId` form) is excluded from the total.
 *
 * [size] is a level-triggered count; [refresh] re-enumerates and recomputes it (invoked on foreground
 * entry / library change by the composition root).
 */
class OwnDeviceGalleryStatusSource(
    private val enumerator: GalleryResourceEnumerator,
    private val suppressedLocalIds: suspend () -> Set<String> = { emptySet() },
) : GalleryStatusSource {

    private val _size = MutableStateFlow(0)

    /** The upload total `N`: the count of this device's OWN qualifying assets (downloads excluded). */
    override val size: StateFlow<Int> = _size.asStateFlow()

    suspend fun refresh() {
        val suppressed = suppressedLocalIds()
        // Own universe = enumerated assets minus the ones this device downloaded + imported.
        _size.value = enumerator.enumerate()
            .asSequence()
            .map { it.assetId }
            .filter { it !in suppressed }
            .distinct()
            .count()
    }
}
