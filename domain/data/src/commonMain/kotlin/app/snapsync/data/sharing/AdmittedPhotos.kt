package app.snapsync.data.sharing

import app.snapsync.domain.asset.AssetId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The member's own photos the membership admits, as last counted — the total `N` the status counts against. Held in
 * memory for the app's process, and `null` until a count has been taken: never a placeholder zero, which the status
 * would read as "counted nothing" and settle on (`SNAPSYNC-14`, `SNAPSYNC-16`). A count that could not be taken leaves
 * it as it was, so what it holds is always the last count that was.
 */
class AdmittedPhotos {
    private val held = MutableStateFlow<Set<AssetId>?>(null)

    /** The admitted photos as last counted, or `null` before the first count. */
    val state: StateFlow<Set<AssetId>?> = held.asStateFlow()

    /** A count was taken: these are the photos the membership admits now. */
    fun counted(ids: Set<AssetId>) {
        held.value = ids
    }
}
