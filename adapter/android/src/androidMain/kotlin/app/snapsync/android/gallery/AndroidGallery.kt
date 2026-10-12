package app.snapsync.android.gallery

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import app.snapsync.android.permission.AndroidPhotoPermission
import app.snapsync.model.BeforeListen
import app.snapsync.model.HandlerSlot
import app.snapsync.model.ImportRequest
import app.snapsync.model.runCatchingCancellable
import app.snapsync.ports.Gallery
import app.snapsync.ports.GalleryHandlers
import app.snapsync.ports.GalleryRead
import app.snapsync.ports.ImportOutcome
import app.snapsync.ports.LibraryChangeToken
import app.snapsync.ports.LibraryGrant
import app.snapsync.ports.SelectionSnapshot
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's Android [Gallery]: the [AndroidGalleryReader], plus the permission requests
 * ([AndroidPhotoPermission]), the partial grant's selection observer, and the library's change token.
 *
 * - **The selection observer** watches the image and video collections while [observeChanges] is on **and** the grant
 *   is partial — a baseline snapshot when it opens and one per change — and closes when the grant moves away. Under a
 *   partial grant MediaStore answers the selection, so a snapshot is the default gallery's whole readable content.
 *   Android raises no prompt for a read, so nothing here rations them.
 * - **The change token** is each external volume's MediaStore version and generation: the generation moves on every
 *   change to the volume's media, and the version on a rebuild of its database.
 * - **Imports** rebuild a foreign photo in the event album's folder, or the camera folder when there is none
 *   ([MediaStoreImport]): pending, recorded, written, published.
 */
class AndroidGallery(
    context: Context,
    private val permission: AndroidPhotoPermission,
    private val scope: CoroutineScope,
    private val log: Logger = Logger.withTag("gallery"),
) : AndroidGalleryReader(context, permission::current, log), Gallery {

    private val handlers = HandlerSlot<GalleryHandlers>("Gallery", BeforeListen.Logged(log))

    override val grant: StateFlow<LibraryGrant> = permission.grant

    private val read = MutableStateFlow<SelectionSnapshot?>(null)
    override val selection: StateFlow<SelectionSnapshot?> = read.asStateFlow()
    private var observing = false
    private var observer: ContentObserver? = null
    private val reads = Channel<Unit>(Channel.CONFLATED)
    private val importer = MediaStoreImport(context, log)

    init {
        scope.launch {
            reads.receiveAsFlow().collect {
                if (observer == null) return@collect
                val snapshot = readable { items(Query()).map { it.withResources() } }
                if (snapshot is GalleryRead.Read) read.value = SelectionSnapshot(snapshot.value)
            }
        }
        scope.launch { permission.grant.collect { reconcile() } }
    }

    override fun listen(handlers: GalleryHandlers) = this.handlers.set(handlers)

    override fun observeChanges(enabled: Boolean) {
        observing = enabled
        reconcile()
    }

    override suspend fun requestAccess(): LibraryGrant = permission.requestAccess()

    override suspend fun widenSelection(): LibraryGrant = permission.widenSelection()

    override suspend fun import(request: ImportRequest): ImportOutcome {
        val handlers = handlers.orNull("the import of ${request.ref.sourceAssetId}")
            ?: return ImportOutcome.Failed("no import handlers registered")
        val result = withContext(Dispatchers.IO) {
            importer.import(request) { id -> handlers.onImportPlaceholder(request.ref, id) }
        }
        handlers.onImportSettled(request.ref, result)
        return result
    }

    override suspend fun changeToken(): LibraryChangeToken? = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            VolumeGenerations(
                MediaStore.getExternalVolumeNames(appContext).sorted().associateWith { volume ->
                    MediaStore.getVersion(appContext, volume) to MediaStore.getGeneration(appContext, volume)
                },
            )
        }.onFailure { log.w(it) { "the media provider gave no change token" } }.getOrNull()
    }

    /** Open the observer while observation is on and the grant is partial; close it otherwise. */
    @Synchronized
    private fun reconcile() {
        val wanted = observing && permission.grant.value == LibraryGrant.LIMITED
        val open = observer
        when {
            wanted && open == null -> {
                val fresh = object : ContentObserver(Handler(Looper.getMainLooper())) {
                    override fun onChange(selfChange: Boolean, uri: Uri?) {
                        reads.trySend(Unit)
                    }
                }
                COLLECTIONS.forEach { appContext.contentResolver.registerContentObserver(it, true, fresh) }
                observer = fresh
                log.i { "selection observer opened" }
                reads.trySend(Unit) // the baseline
            }
            !wanted && open != null -> {
                appContext.contentResolver.unregisterContentObserver(open)
                observer = null
                log.i { "selection observer closed" }
            }
        }
    }
}

/** One reading of every external volume's MediaStore version and generation; equal by value. */
private data class VolumeGenerations(val volumes: Map<String, Pair<String, Long>>) : LibraryChangeToken {
    override fun sameLibraryAs(other: LibraryChangeToken): Boolean = other is VolumeGenerations && other == this
}
