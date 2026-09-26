package app.snapsync.permission

import app.snapsync.model.ConfinedTo
import app.snapsync.gallery.photoKitRawAssets
import app.snapsync.ios.qos.photoKitReadLane
import kotlinx.coroutines.withContext
import app.snapsync.model.GalleryAccess
import app.snapsync.model.SelectionSnapshot
import app.snapsync.selection.SelectionPlatform
import platform.Foundation.NSSortDescriptor
import platform.Photos.PHAsset
import platform.Photos.PHChange
import platform.Photos.PHFetchOptions
import platform.Photos.PHFetchResult

/**
 * The PhotoKit binding of [IosGallery]'s selection observer (capability `photo-access`): observes the photo
 * library **only while observation is on and permission is [GalleryAccess.LIMITED]** and delivers the full current
 * selection with its resources — once when observation begins (the cold-launch baseline read; opening the
 * app is the user action that makes it in-flow) and after each change ([PhotoSelectionObserver] fires
 * for the in-app picker, Settings-side edits, and iCloud sync alike).
 *
 * Every read here is **in-flow** (capability `photo-access`): the baseline is one scope query per
 * observation start, and each change reads the **pushed** `fetchResultAfterChanges` (never a fresh
 * scope query). Change details are consumed as whole snapshots, not itemized deltas — bulk changes
 * arrive non-incremental (measured), so the reliable path is reload-and-let-the-ledger-dedup, which
 * is exactly the port's emission contract.
 *
 * What the limited-access prompt does around these reads is measured per release, never a rule (SE2, with the
 * suppression key in both bundles): reads of an unchanged library and the app's own creations raised none on
 * iOS 26.5.2 and 26.6.2; a camera photo OUTSIDE the selection leaked a queued prompt on 26.5/26.5.2 and none on
 * 26.6.2 (11 reads, 4 launch-and-kill cycles, n = 1). No clause can observe a system alert. See
 * changes/archive/2026-09-21-correct-limited-access-alert-rule.
 *
 * The per-asset resource mapping is the shared ext-safe one ([photoKitRawAssets], also behind
 * `IosGalleryReader.resources`), unbounded by any policy: the policy filters downstream, in one place. Its
 * cost is one platform round-trip per **selected** asset — selections are hand-picked and small.
 *
 * Snapshots conflate: the flow keeps only the newest unprocessed snapshot (each is the whole
 * selection, so an unconsumed older one is superseded by construction, and emission never blocks the
 * observer callback). So do the reads behind them: at most one enumeration runs at a time, and changes that
 * arrive while it runs are folded into one more enumeration for the latest. The ordering and that folding —
 * one serial lane for the baseline, every change and every emission — are [SelectionSnapshotLane]'s,
 * platform-free and tested on the JVM; this file is only the PhotoKit binding.
 *
 * Every member runs on the lane, so [observer] needs no lock — PhotoKit holds observers weakly, which is why it is
 * retained here at all.
 */
internal class PhotoKitSelection : SelectionPlatform<PHFetchResult, PHChange, SelectionSnapshot> {

    @ConfinedTo("selection")
    private var observer: PhotoSelectionObserver? = null

    override fun startObserving(onChange: (PHChange) -> Unit) {
        val obs = PhotoSelectionObserver { change -> onChange(change) }
        obs.register()
        observer = obs
    }

    override fun stopObserving() {
        observer?.unregister()
        observer = null
    }

    // The baseline: ONE sorted scope query. Sorted so PhotoKit can hand incremental change details against it;
    // correctness never depends on that (snapshots re-enumerate whole).
    override suspend fun baseline(): PHFetchResult {
        val options = PHFetchOptions().apply {
            sortDescriptors = listOf(NSSortDescriptor.sortDescriptorWithKey("creationDate", ascending = true))
        }
        return PHAsset.fetchAssetsWithOptions(options)
    }

    // The pushed result — never a fresh scope query. Null details = a change unrelated to the held fetch (e.g. an
    // album edit); the selection did not move, so there is nothing to emit.
    override fun after(held: PHFetchResult, change: PHChange): PHFetchResult? =
        change.changeDetailsForFetchResult(held)?.fetchResultAfterChanges

    // Read the resources straight off the HELD result, eagerly: deferring the resource read would leave a later
    // consumer holding only identifiers, and reaching the assets again off-flow would be an autonomous library
    // fetch, which the read discipline forbids. The selection is hand-picked and small, so eagerness costs little.
    // On the PhotoKit read lane, at its pinned QoS, like every other resource read (see `IosGalleryReader`).
    override suspend fun snapshot(of: PHFetchResult): SelectionSnapshot =
        withContext(photoKitReadLane) { SelectionSnapshot(photoKitRawAssets(of)) }
}
