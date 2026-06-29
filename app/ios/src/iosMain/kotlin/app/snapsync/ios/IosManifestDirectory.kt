package app.snapsync.ios

import app.snapsync.gallery.IosManifestStore
import app.snapsync.status.ManifestDirectory

/**
 * The iOS [ManifestDirectory]: the App-Group on-disk manifest directory the extension writes and the
 * app reads/prunes, over [IosManifestStore]. [assetIds] is the set of assets with a PENDING manifest
 * file; [prune] removes one (the storage-truth backstop to the extension's prune-on-success). Pure
 * platform file I/O — wiring-only and untestable (the set/prune logic over it lives in tested
 * `:domain:status`).
 */
class IosManifestDirectory(private val store: IosManifestStore) : ManifestDirectory {
    override suspend fun assetIds(): Set<String> = store.pendingAssetIds()
    override suspend fun prune(assetId: String) = store.prunePending(assetId)
}
