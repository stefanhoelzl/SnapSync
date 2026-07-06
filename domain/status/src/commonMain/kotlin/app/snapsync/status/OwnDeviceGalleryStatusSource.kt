package app.snapsync.status

import app.snapsync.gallery.GalleryResourceEnumerator
import app.snapsync.gallery.GalleryStatusSource
import app.snapsync.gallery.RESOURCE_META_CREATION_DATE
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
 * **The capture-date cutoff scopes the total too** (capability `photo-date-cutoff`): an asset whose
 * `creationDate` precedes [photoCutoff] is neither uploaded nor listed in the manifest, so counting it
 * would peg upload progress permanently below 100% ("pending" forever). The total therefore counts only
 * assets at or after the cutoff — the same set the upload cycle admits — so the joined screen settles to
 * "in sync" once every in-scope asset is uploaded. `null` cutoff = whole-library (today's behavior).
 *
 * [size] is a level-triggered count; [refresh] re-enumerates and recomputes it (invoked on foreground
 * entry / library change / (re)join by the composition root).
 */
class OwnDeviceGalleryStatusSource(
    private val enumerator: GalleryResourceEnumerator,
    private val suppressedLocalIds: suspend () -> Set<String> = { emptySet() },
    private val photoCutoff: suspend () -> String? = { null },
) : GalleryStatusSource {

    private val _size = MutableStateFlow(0)

    /** The upload total `N`: the count of this device's OWN in-scope assets (downloads + pre-cutoff excluded). */
    override val size: StateFlow<Int> = _size.asStateFlow()

    suspend fun refresh() {
        val suppressed = suppressedLocalIds()
        val cutoff = photoCutoff()
        // Own universe = enumerated assets minus downloads (echo) minus pre-cutoff (photo-date-cutoff) —
        // exactly the set the upload cycle admits, so completeness can reach 100%.
        _size.value = enumerator.enumerate()
            .asSequence()
            .filter { cutoff == null || (it.metadata[RESOURCE_META_CREATION_DATE] ?: "") >= cutoff }
            .map { it.assetId }
            .filter { it !in suppressed }
            .distinct()
            .count()
    }
}
