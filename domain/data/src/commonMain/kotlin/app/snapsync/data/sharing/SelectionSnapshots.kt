package app.snapsync.data.sharing

import app.snapsync.data.gallery.assetId
import app.snapsync.data.gallery.facts
import app.snapsync.domain.asset.AssetFacts
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.sharing.PhotoAccess
import app.snapsync.ports.GalleryAssetResources
import app.snapsync.ports.GalleryRead
import app.snapsync.ports.SelectionSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map

/**
 * The selection under a partial grant, as last read — set only by [published], from the gallery's selection observer,
 * the sanctioned read points (the start's baseline and each selection change). Under a partial grant the selection is
 * the gallery, so it arrives already read, with its resources, and no later library read is needed.
 *
 * [SelectionState.Unread] until the first read: the app holds no selection then, which is not an empty one — an
 * empty selection would say every photo left.
 */
class SelectionSnapshots {
    private val latest = MutableStateFlow<SelectionState>(SelectionState.Unread)

    val state: StateFlow<SelectionState> get() = latest

    /** The selection as last read, or `null` while none has been. */
    val read: SelectionState.Read? get() = latest.value as? SelectionState.Read

    /**
     * The gallery's selection [changes], each published: it becomes the latest selection, then the returned flow
     * emits — one emission, ONE read serving every reader. [beforePublish] sees each selection BEFORE any reader can:
     * what it withholds is withheld before a cycle could admit from it.
     */
    fun published(
        changes: Flow<SelectionSnapshot?>,
        beforePublish: suspend (SelectionState.Read) -> Unit,
    ): Flow<Unit> = changes.filterNotNull().map { change ->
        val read = SelectionState.Read(change.assets)
        beforePublish(read)
        latest.value = read
    }

    /** How many photos the selection holds — photos, not resources — or `null` while none has been read. */
    val selectedPhotos: Int?
        get() = when (val state = latest.value) {
            is SelectionState.Read -> state.assets.size
            SelectionState.Unread -> null
        }

    /**
     * Wait until a read under [access] can be answered: under a partial grant the answer is the selection, unread until
     * the observer's first emission, so this waits for it; under any other grant there is nothing to wait for. If the
     * emission never comes this never returns, which costs the same deferral without a wasted lookup.
     */
    suspend fun awaitAnswerable(access: PhotoAccess) = when (access) {
        PhotoAccess.LIMITED -> awaitRead()
        PhotoAccess.FULL, PhotoAccess.NONE -> Unit
    }

    /** Wait until a selection has been read. Never returns if none ever is. */
    suspend fun awaitRead() {
        latest.filterIsInstance<SelectionState.Read>().firstOrNull()
    }
}

/** The selection as the app holds it. */
sealed interface SelectionState {
    data object Unread : SelectionState

    class Read(val assets: List<GalleryAssetResources>) : SelectionState {
        /** The selected assets' facts, for admission. */
        fun facts(): List<AssetFacts> = assets.map { it.asset.facts() }

        /** The selected assets' resources, in hand. */
        fun resources(): AssetResources = SnapshotResources(assets)
    }
}

/** Resources already in hand: the selection carries every selected asset's. */
class SnapshotResources(private val assets: List<GalleryAssetResources>) : AssetResources {
    override suspend fun of(ids: Set<AssetId>): GalleryRead<List<GalleryAssetResources>> =
        GalleryRead.Read(assets.filter { it.asset.id.assetId() in ids })
}
