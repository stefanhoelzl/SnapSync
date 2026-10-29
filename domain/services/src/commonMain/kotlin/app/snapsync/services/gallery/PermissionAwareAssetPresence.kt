package app.snapsync.services.gallery

import app.snapsync.data.gallery.assetId
import app.snapsync.data.sharing.SelectionState
import app.snapsync.domain.asset.AssetId
import app.snapsync.model.AssetPresence
import app.snapsync.model.GalleryAccess
import kotlinx.coroutines.flow.StateFlow

/**
 * The one [ImportedAssetPresence] the app's consumers hold: it decides **which source may answer** by the
 * current photo-access grant, so the download feature never has to (the
 * same reason the read plan has: the grant decides the source, never a consumer).
 *
 * The distinction that matters is not *can we look* but **is a miss trustworthy**. Only a view of the
 * whole library may report [AssetPresence.ABSENT]; everywhere else a miss means "not visible from here",
 * and treating that as absence clears a live marker, imports a second copy, and orphans the first — the
 * defect this capability exists to prevent.
 *
 * - **`GRANTED`** → [library], a real fetch by identifier. Both verdicts are authoritative.
 * - **`LIMITED`** → the held [selection] snapshot. A hit is [AssetPresence.PRESENT]; a miss is
 *   [AssetPresence.UNKNOWN], **never** `ABSENT`, because app-created assets join the selection at
 *   creation time only, so one created under a full grant is real but invisible after a downgrade
 *   (measured). Answering from the snapshot also costs no library
 *   read — the app already holds it, and it sees exactly what a fetch would see under this grant.
 * - **`DENIED` / `NOT_DETERMINED`** → [AssetPresence.UNKNOWN]. A query returns nothing for assets that
 *   exist, and imports cannot succeed anyway, so there is nothing to gain by guessing. A row simply
 *   waits; if access returns, it is adjudicated then.
 *
 * Seated in `compose/` because choosing between sources by a third port's state is composition, and
 * because both halves are already available here.
 */
class PermissionAwareAssetPresence(
    private val permission: StateFlow<GalleryAccess>,
    private val library: ImportedAssetPresence,
    private val selection: StateFlow<SelectionState>,
) : ImportedAssetPresence {

    override suspend fun presence(localIds: Set<AssetId>): Map<AssetId, AssetPresence> =
        when (permission.value) {
            GalleryAccess.GRANTED -> library.presence(localIds)
            GalleryAccess.LIMITED -> {
                // An unread selection is the honest gap between a grant turning partial and the first
                // observer emission: nothing is known to be selected, and we may not go looking.
                val selected = when (val state = selection.value) {
                    is SelectionState.Read -> state.assets.mapTo(mutableSetOf()) { it.asset.id.assetId() }
                    SelectionState.Unread -> emptySet()
                }
                localIds.associateWith {
                    if (it in selected) AssetPresence.PRESENT else AssetPresence.UNKNOWN
                }
            }
            GalleryAccess.DENIED, GalleryAccess.NOT_DETERMINED ->
                localIds.associateWith { AssetPresence.UNKNOWN }
        }
}
