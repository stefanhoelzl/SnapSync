package app.snapsync.gallery

import app.snapsync.download.IosPhotoLibraryImporter
import app.snapsync.ios.discovery.libraryChangeTokenOf
import app.snapsync.ios.qos.photoKitReadLane
import app.snapsync.model.GalleryAccess
import app.snapsync.model.ImportRequest
import app.snapsync.model.ImportResult
import app.snapsync.permission.PhotoKitSelection
import app.snapsync.permission.PhotoLibraryPermission
import app.snapsync.ports.Gallery
import app.snapsync.ports.GalleryHandlers
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.LibraryChangeToken
import app.snapsync.selection.SelectionSnapshotLane
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Photos.PHPhotoLibrary
import kotlin.concurrent.Volatile

/**
 * The app's PhotoKit [Gallery]: the shared [IosGalleryReader] every read and album write goes through, plus the
 * members only the foreground process has — the permission dialog and the limited-library picker (UIKit, through
 * [PhotoLibraryPermission]), the partial grant's selection observer, the import of foreign photos, and the library's
 * change token.
 *
 * **App process only**, and placed by linkage to say so: this module is one the upload extension never links, so
 * the extension cannot hold a change token, a walk memo or an observer even by accident (capability
 * `background-upload` — its 32 MB limit leaves no room for a held walk).
 *
 * What it observes reaches the handlers registered by [listen]: the selection snapshots from the observer's one
 * serial lane ([SelectionSnapshotLane]), which opens only while [observeChanges] is on and the grant is partial; an
 * import's placeholder from inside its change block, and its outcome from its completion.
 */
class IosGallery(
    private val reader: IosGalleryReader,
    private val permission: PhotoLibraryPermission,
    scope: CoroutineScope,
    private val log: Logger = Logger.withTag("gallery"),
) : Gallery, GalleryReader by reader {

    @Volatile
    private var handlers: GalleryHandlers? = null

    private val importer = IosPhotoLibraryImporter()

    // ONE serial lane for every read, change and emission — the ordering the lane class exists for.
    private val selection = SelectionSnapshotLane(
        permission = permission.permission,
        scope = scope,
        lane = Dispatchers.Default.limitedParallelism(1),
        platform = PhotoKitSelection(),
    )

    init {
        // The lane replays its latest snapshot, so one read before the handlers arrive is kept, not lost.
        scope.launch { selection.snapshots.collect { snapshot -> handlers?.onChanged(snapshot) } }
    }

    override fun listen(handlers: GalleryHandlers) {
        this.handlers = handlers
    }

    override fun observeChanges(enabled: Boolean) = selection.observe(enabled)

    override suspend fun requestAccess(): GalleryAccess = permission.requestAccess()

    override suspend fun widenSelection(): GalleryAccess = permission.widenSelection()

    /**
     * Without registered handlers there is nowhere to record the marker that keeps the created asset from being
     * uploaded back, so nothing is created: an error, and an import the caller retries later.
     */
    override suspend fun import(request: ImportRequest): ImportResult {
        val handlers = handlers ?: return ImportResult.Failed("no import handlers registered")
            .also { log.e { "import of ${request.ref.sourceAssetId} before listen — nothing created" } }
        return importer.import(request, handlers)
    }

    /**
     * `PHPhotoLibrary.currentChangeToken`, compared with `isEqual`. Not a cursor: never archived, stored, or handed
     * to `fetchPersistentChanges(since:)`. On [photoKitReadLane] because the read is a synchronous XPC round-trip
     * into `assetsd`; measured about 2 ms in darwinbg (SE2, iOS 26.6.2).
     */
    override suspend fun changeToken(): LibraryChangeToken? = withContext(photoKitReadLane) {
        libraryChangeTokenOf(PHPhotoLibrary.sharedPhotoLibrary().currentChangeToken)
    }
}
