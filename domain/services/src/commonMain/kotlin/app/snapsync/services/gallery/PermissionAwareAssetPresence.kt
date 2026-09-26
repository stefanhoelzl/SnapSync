package app.snapsync.services.gallery

import app.snapsync.model.AssetId
import app.snapsync.model.AssetPresence
import app.snapsync.model.GalleryAccess
import app.snapsync.model.Resource
import app.snapsync.services.gallery.ImportedAssetPresence
import kotlinx.coroutines.flow.StateFlow

/**
 * The one [ImportedAssetPresence] the app's consumers hold: it decides **which source may answer** by the
 * current photo-access grant, so the download feature never has to (capability `receiving-photos`; the
 * same shape, and the same reason, as [PermissionAwareCandidateSource]).
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
 *   (measured, capability `photo-access`). Answering from the snapshot also costs no library
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
    private val selection: StateFlow<List<Resource>?>,
) : ImportedAssetPresence {

    override suspend fun presence(localIds: Set<AssetId>): Map<AssetId, AssetPresence> =
        when (permission.value) {
            GalleryAccess.GRANTED -> library.presence(localIds)
            GalleryAccess.LIMITED -> {
                // A null snapshot is the honest gap between a grant turning partial and the first
                // observer emission: nothing is known to be selected, and we may not go looking.
                val selected = selection.value.orEmpty().mapTo(mutableSetOf()) { it.assetId }
                localIds.associateWith {
                    if (it in selected) AssetPresence.PRESENT else AssetPresence.UNKNOWN
                }
            }
            GalleryAccess.DENIED, GalleryAccess.NOT_DETERMINED ->
                localIds.associateWith { AssetPresence.UNKNOWN }
        }
}
