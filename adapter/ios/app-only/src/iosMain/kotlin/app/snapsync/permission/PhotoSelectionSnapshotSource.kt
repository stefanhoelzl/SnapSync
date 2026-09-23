package app.snapsync.permission

import app.snapsync.model.ConfinedTo
import app.snapsync.gallery.PhotoKitCandidateSource
import app.snapsync.model.PermissionStatus
import app.snapsync.model.Resource
import app.snapsync.ports.PhotoSelectionChangeSource
import app.snapsync.selection.SelectionPlatform
import app.snapsync.selection.SelectionSnapshotLane
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import platform.Foundation.NSSortDescriptor
import platform.Photos.PHAsset
import platform.Photos.PHChange
import platform.Photos.PHFetchOptions
import platform.Photos.PHFetchResult

/**
 * The iOS [PhotoSelectionChangeSource] (capability `limited-photo-access`): observes the photo
 * library **only while permission is [PermissionStatus.LIMITED]** and emits the full current
 * selection as resources — once when observation begins (the cold-launch baseline read; opening the
 * app is the user action that makes it in-flow) and once per change ([PhotoSelectionObserver] fires
 * for the in-app picker, Settings-side edits, and iCloud sync alike).
 *
 * Every read here is **in-flow** (capability `limited-photo-access`): the baseline is one scope query per
 * observation start, and each change reads the **pushed** `fetchResultAfterChanges` (never a fresh
 * scope query). Change details are consumed as whole snapshots, not itemized deltas — bulk changes
 * arrive non-incremental (measured), so the reliable path is reload-and-let-the-ledger-dedup, which
 * is exactly the port's emission contract.
 *
 * The per-asset resource mapping is delegated to the shared enumerator seam
 * ([PhotoLibrary.resources] — the ext-safe `PhotoLibraryResourceEnumerator` in production), bounded
 * by the empty cutoff (`""` admits every asset; the policy filters downstream, in one place). Its
 * cost is one platform round-trip per **selected** asset — selections are hand-picked and small.
 *
 * Snapshots conflate: the flow keeps only the newest unprocessed snapshot (each is the whole
 * selection, so an unconsumed older one is superseded by construction, and emission never blocks the
 * observer callback). The ordering — one serial lane for the baseline, every change and every emission — is
 * [SelectionSnapshotLane]'s, platform-free and tested on the JVM; this file is only the PhotoKit binding.
 */
class PhotoSelectionSnapshotSource(
    permission: StateFlow<PermissionStatus>,
    scope: CoroutineScope,
    source: PhotoKitCandidateSource,
    ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : PhotoSelectionChangeSource by SelectionSnapshotLane(
    permission = permission,
    scope = scope,
    // ONE serial lane for every read, change and emission — the ordering the lane class exists for.
    lane = ioDispatcher.limitedParallelism(1),
    platform = PhotoKitSelection(source),
)

/**
 * PhotoKit behind [SelectionSnapshotLane]. Every member runs on the lane, so [observer] needs no lock — PhotoKit
 * holds observers weakly, which is why it is retained here at all.
 */
private class PhotoKitSelection(private val source: PhotoKitCandidateSource) : SelectionPlatform<PHFetchResult, PHChange> {

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
    override suspend fun snapshot(of: PHFetchResult): List<Resource> =
        source.candidatesFrom(of).flatMap { it.resources() }
}
