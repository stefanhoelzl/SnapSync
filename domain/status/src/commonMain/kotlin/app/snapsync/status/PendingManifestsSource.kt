package app.snapsync.status

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The **in-flight** set: assets the extension has started (an on-disk manifest exists in the App
 * Group) but the completeness listing has not yet reported complete. [inFlight] is a level-triggered
 * holder; its `size` is the [SyncProgress.pending] count (available, but it does not drive
 * classification). [refresh] re-reads the on-disk manifests and, as a backstop to the extension's
 * own prune-on-success, **prunes** the on-disk manifest file of any asset the listing now reports
 * complete (and excludes it from the in-flight set).
 */
interface PendingManifestsSource {
    val inFlight: StateFlow<Set<String>>
    suspend fun refresh()
}

/**
 * The on-disk App-Group manifest directory the extension writes and the app reads/prunes. [assetIds]
 * is the set of assets with an on-disk (pending) manifest file; [prune] deletes one asset's manifest
 * file (idempotent — deleting an already-gone file is a no-op). The iOS adapter wraps Foundation file
 * I/O over the shared container; a fake backs it in tests.
 */
interface ManifestDirectory {
    suspend fun assetIds(): Set<String>
    suspend fun prune(assetId: String)
}

/**
 * The real [PendingManifestsSource]: reads the on-disk manifests from a [ManifestDirectory], prunes
 * the files of assets the [completed] listing already reports complete (the backstop to the
 * extension's prune-on-success — completions delivered to the app while the extension was suspended),
 * and exposes the remainder as the in-flight set.
 */
class DirectoryPendingManifestsSource(
    private val directory: ManifestDirectory,
    private val completed: CompletedAssetsSource,
) : PendingManifestsSource {

    private val _inFlight = MutableStateFlow<Set<String>>(emptySet())
    override val inFlight: StateFlow<Set<String>> = _inFlight.asStateFlow()

    override suspend fun refresh() {
        val onDisk = directory.assetIds()
        val complete = completed.completed.value
        for (assetId in onDisk.intersect(complete)) directory.prune(assetId)
        _inFlight.value = onDisk - complete
    }
}

/**
 * A settable, in-memory [PendingManifestsSource]: holds its truth synchronously and re-emits on
 * [set]. Used by the desktop harness and integration tests; the iOS app backs the seam with the
 * App-Group manifest directory ([DirectoryPendingManifestsSource]) instead. [refresh] is inert here.
 */
class MutablePendingManifestsSource(initial: Set<String> = emptySet()) : PendingManifestsSource {
    private val _inFlight = MutableStateFlow(initial)
    override val inFlight: StateFlow<Set<String>> = _inFlight.asStateFlow()

    override suspend fun refresh() = Unit

    fun set(assetIds: Set<String>) {
        _inFlight.value = assetIds
    }
}
