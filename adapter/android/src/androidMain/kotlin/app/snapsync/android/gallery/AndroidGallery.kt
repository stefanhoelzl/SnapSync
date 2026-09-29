package app.snapsync.android.gallery

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import app.snapsync.android.permission.AndroidPhotoPermission
import app.snapsync.model.GalleryAccess
import app.snapsync.model.GalleryRead
import app.snapsync.model.ImportRequest
import app.snapsync.model.ImportResult
import app.snapsync.model.SelectionSnapshot
import app.snapsync.ports.Gallery
import app.snapsync.ports.GalleryHandlers
import app.snapsync.ports.LibraryChangeToken
import app.snapsync.model.runCatchingCancellable
import co.touchlab.kermit.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app's Android [Gallery] (capability `photo-access`): the [AndroidGalleryReader], plus the permission requests
 * ([AndroidPhotoPermission]), the partial grant's selection observer, and the library's change token.
 *
 * - **The selection observer** watches the image and video collections while [observeChanges] is on **and** the grant
 *   is partial — a baseline snapshot when it opens and one per change — and closes when the grant moves away. Under a
 *   partial grant MediaStore answers the selection, so a snapshot is the default gallery's whole readable content.
 *   Android raises no prompt for a read, so nothing here rations them.
 * - **The change token** is each external volume's MediaStore version and generation: the generation moves on every
 *   change to the volume's media, and the version on a rebuild of its database.
 * - **Imports** are phase 4's: this answers a failure that consumed nothing, so a later build retries it.
 */
class AndroidGallery(
    context: Context,
    private val permission: AndroidPhotoPermission,
    private val scope: CoroutineScope,
    private val log: Logger = Logger.withTag("gallery"),
) : AndroidGalleryReader(context, permission::current, log), Gallery {

    private var handlers: GalleryHandlers? = null
    private var observing = false
    private var observer: ContentObserver? = null
    private val reads = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            reads.receiveAsFlow().collect {
                if (observer == null) return@collect
                val snapshot = readable { items(Query()).map { it.rawAsset() } }
                if (snapshot is GalleryRead.Read) handlers?.onChanged(SelectionSnapshot(snapshot.value))
            }
        }
        scope.launch { permission.permission.collect { reconcile() } }
    }

    override fun listen(handlers: GalleryHandlers) {
        this.handlers = handlers
    }

    override fun observeChanges(enabled: Boolean) {
        observing = enabled
        reconcile()
    }

    override suspend fun requestAccess(): GalleryAccess = permission.requestAccess()

    override suspend fun widenSelection(): GalleryAccess = permission.widenSelection()

    override suspend fun import(request: ImportRequest): ImportResult {
        val result = ImportResult.Failed("Android imports no photo yet")
        handlers?.onImportSettled(request.ref, result)
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
        val wanted = observing && permission.permission.value == GalleryAccess.LIMITED
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
