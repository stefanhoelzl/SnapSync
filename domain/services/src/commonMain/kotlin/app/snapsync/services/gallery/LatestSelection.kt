package app.snapsync.services.gallery

import app.snapsync.model.Fact
import app.snapsync.model.GalleryAccess
import app.snapsync.model.Resource
import app.snapsync.model.SelectionScope
import app.snapsync.model.SelectionSnapshot
import app.snapsync.model.resourcesFrom
import app.snapsync.model.selectionPhotos
import app.snapsync.model.selectionScope
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * **The latest selection snapshot** of a partial grant — set only by [follow], from the
 * gallery's selection observer. The walk-vs-snapshot decision is DERIVED per read from the current grant and this cell
 * ([scopeUnder]), so it has exactly one owner and no stored mode can go stale across a permission flip. `null` is "not
 * read yet", which is NOT an empty selection: it derives `SelectionScope.Unread`, and the app's upload admission
 * withholds on it.
 */
class LatestSelection {
    private val latest = MutableStateFlow<List<Resource>?>(null)

    /** The snapshot as its readers hold it — the permission-aware sources answer a partial grant from it. */
    val snapshot: StateFlow<List<Resource>?> get() = latest

    /** What upload discovery may read right now, under [permission] — see [selectionScope]. */
    fun scopeUnder(permission: GalleryAccess): SelectionScope = selectionScope(permission, latest.value)

    /** How many photos the selection holds under [permission], for a bug report — see [selectionPhotos]. */
    fun photosUnder(permission: GalleryAccess): Fact<Int> = selectionPhotos(permission, latest.value)

    /**
     * Wait until a presence read under [permission] can be answered: under a partial grant the answer comes from the
     * snapshot, which is unread until the observer's first emission, so this waits for it — a sweep that ran first would
     * get UNKNOWN for every row. Under any other grant there is nothing to wait for. If the emission never comes this
     * never returns, which costs the same deferral without the wasted lookup.
     */
    suspend fun awaitAnswerable(permission: GalleryAccess) {
        if (permission == GalleryAccess.LIMITED) latest.filterNotNull().first()
    }

    /**
     * Follow the gallery's selection [changes]: each one becomes the latest snapshot, then [onChanged] runs — one
     * emission, ONE read serving every consumer: one discovery serves both the status total
     * and the enqueue. Returns only when [changes] is closed.
     */
    suspend fun follow(changes: ReceiveChannel<SelectionSnapshot>, onChanged: suspend () -> Unit) {
        for (change in changes) {
            latest.value = resourcesFrom(change.assets)
            onChanged()
        }
    }
}
