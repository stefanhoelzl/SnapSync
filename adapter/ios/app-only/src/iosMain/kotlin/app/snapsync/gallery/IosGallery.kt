package app.snapsync.gallery

import app.snapsync.download.IosPhotoLibraryImporter
import app.snapsync.inbound.CompositionOnly
import app.snapsync.ios.discovery.libraryChangeTokenOf
import app.snapsync.ios.qos.photoKitReadLane
import app.snapsync.model.BeforeListen
import app.snapsync.model.HandlerSlot
import app.snapsync.permission.PhotoKitSelection
import app.snapsync.permission.PhotoLibraryPermission
import app.snapsync.ports.Gallery
import app.snapsync.ports.GalleryHandlers
import app.snapsync.ports.GalleryImportRequest
import app.snapsync.ports.GalleryReader
import app.snapsync.ports.ImportOutcome
import app.snapsync.ports.LibraryChangeToken
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.SelectionSnapshot
import app.snapsync.selection.SelectionSnapshotLane
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Photos.PHPhotoLibrary

/**
 * The app's PhotoKit [Gallery]: the shared [IosGalleryReader] every read and album write goes through, plus the
 * members only the foreground process has — the permission dialog and the limited-library picker (UIKit, through
 * [PhotoLibraryPermission]), the partial grant's selection observer, the import of foreign photos, and the library's
 * change token.
 *
 * **App process only**, and placed by linkage to say so: this module is one the upload extension never links, so
 * the extension cannot hold a change token, a walk memo or an observer even by accident (its
 * 32 MB limit leaves no room for a held walk).
 *
 * The selection snapshots arrive on [selection], from the observer's one serial lane ([SelectionSnapshotLane]), which
 * opens only while [observeChanges] is on and the grant is partial. What [listen]'s handlers hear is an import's
 * placeholder from inside its change block, and its outcome from its completion.
 */
class IosGallery(
    private val reader: IosGalleryReader,
    private val permission: PhotoLibraryPermission,
    scope: CoroutineScope,
    private val log: Logger = Logger.withTag("gallery"),
) : Gallery, GalleryReader by reader {

    private val handlers = HandlerSlot<GalleryHandlers>("Gallery", BeforeListen.Logged(log))

    override val grant: StateFlow<LibraryGrant> = permission.grant

    private val read = MutableStateFlow<SelectionSnapshot?>(null)
    override val selection: StateFlow<SelectionSnapshot?> = read.asStateFlow()

    /** Read through the permission adapter, so the read is published to [grant] too. */
    override fun access(): LibraryGrant = permission.current()

    private val importer = IosPhotoLibraryImporter()

    // ONE serial lane for every read, change and emission — the ordering the lane class exists for.
    private val lane = SelectionSnapshotLane(
        permission = permission.grant,
        scope = scope,
        lane = Dispatchers.Default.limitedParallelism(1),
        platform = PhotoKitSelection(),
    )

    init {
        scope.launch { lane.snapshots.collect { read.value = it } }
    }

    @CompositionOnly
    override fun inbound(handlers: GalleryHandlers) = this.handlers.set(handlers)

    override fun observeChanges(enabled: Boolean) = lane.observe(enabled)

    override suspend fun requestAccess(): LibraryGrant = permission.requestAccess()

    override suspend fun widenSelection(): LibraryGrant = permission.widenSelection()

    /**
     * Without registered handlers there is nowhere to record the marker that keeps the created asset from being
     * uploaded back, so nothing is created: an error, and an import the caller retries later.
     */
    override suspend fun import(request: GalleryImportRequest): ImportOutcome {
        val handlers = handlers.orNull("the import of ${request.correlation}")
            ?: return ImportOutcome.Failed("no import handlers registered")
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
