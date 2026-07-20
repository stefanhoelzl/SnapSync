package app.snapsync.permission

import app.snapsync.model.PermissionStatus
import app.snapsync.model.Resource
import app.snapsync.ports.PhotoLibrary
import app.snapsync.ports.PhotoSelectionChangeSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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
 * Every read here is **in-flow** — measured storm-free on device: the baseline is one scope query per
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
 * observer callback).
 */
class PhotoSelectionSnapshotSource(
    private val permission: StateFlow<PermissionStatus>,
    private val scope: CoroutineScope,
    private val enumerator: PhotoLibrary,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : PhotoSelectionChangeSource {

    private val _snapshots = MutableSharedFlow<List<Resource>>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val snapshots: Flow<List<Resource>> = _snapshots

    // Retained: PHPhotoLibrary holds observers weakly. Touched only on [scope] (serial main), so the
    // register/unregister dance needs no lock.
    private var observer: PhotoSelectionObserver? = null
    private var heldFetch: PHFetchResult? = null

    init {
        scope.launch {
            permission.collect { status ->
                if (status == PermissionStatus.LIMITED) beginObserving() else endObserving()
            }
        }
    }

    private fun beginObserving() {
        if (observer != null) return
        val obs = PhotoSelectionObserver { change -> scope.launch { onChange(change) } }
        obs.register()
        observer = obs
        scope.launch(ioDispatcher) {
            // The baseline: ONE sorted scope query. Sorted so PhotoKit can hand incremental change
            // details against it; correctness never depends on that (snapshots re-enumerate whole).
            val options = PHFetchOptions().apply {
                sortDescriptors = listOf(NSSortDescriptor.sortDescriptorWithKey("creationDate", ascending = true))
            }
            val baseline = PHAsset.fetchAssetsWithOptions(options)
            heldFetch = baseline
            emitSnapshot(baseline)
        }
    }

    private fun endObserving() {
        observer?.unregister()
        observer = null
        heldFetch = null
    }

    private suspend fun onChange(change: PHChange) {
        val held = heldFetch ?: return
        // The pushed result — never a fresh scope query. Null details = a change unrelated to the
        // held fetch (e.g. an album edit); the selection did not move, so there is nothing to emit.
        val details = change.changeDetailsForFetchResult(held) ?: return
        val after = details.fetchResultAfterChanges
        heldFetch = after
        scope.launch(ioDispatcher) { emitSnapshot(after) }
    }

    private suspend fun emitSnapshot(result: PHFetchResult) {
        val ids = buildList {
            var i = 0uL
            while (i < result.count) {
                (result.objectAtIndex(i) as? PHAsset)?.let { add(it.localIdentifier) }
                i++
            }
        }
        _snapshots.emit(enumerator.resources(ids, since = ""))
    }
}
