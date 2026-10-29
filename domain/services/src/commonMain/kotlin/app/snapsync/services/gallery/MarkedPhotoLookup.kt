package app.snapsync.services.gallery

import app.snapsync.data.gallery.assetId
import app.snapsync.data.gallery.photoAccess
import app.snapsync.data.gallery.platformId
import app.snapsync.data.sharing.SelectionSnapshots
import app.snapsync.data.sharing.SelectionState
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.sharing.PhotoAccess
import app.snapsync.domain.sharing.ReadPlan
import app.snapsync.domain.time.Timestamp
import app.snapsync.model.ReceivedPhotoName
import app.snapsync.ports.AssetFilter
import app.snapsync.ports.GalleryAssetResources
import app.snapsync.ports.GalleryRead
import app.snapsync.ports.GalleryReader

/**
 * The library's SnapSync-marked photos inside a capture-date window: what a join reads
 * to recognise photos an earlier install received, by the mark each one's filename carries ([ReceivedPhotoName]).
 *
 * The window is the CALLER's, spanning the dates of the event's photos — never one member's capture range, since
 * receiving covers what every member shares, and a member's range may reach outside the event's own dates. Candidates
 * are narrowed by that window alone, not by an exact capture date: a received photo's date can drift from the union's
 * (Android fills it from the file's EXIF), and a missed candidate is a duplicate download. Assets in [known] — the ones
 * the download store already records — are never read.
 *
 * What is read follows the grant, by the read plan upload discovery takes ([ReadPlan], decision record
 * `changes/selection-is-the-walk`):
 * - **full grant** — one facts read of the whole library narrowed to the window,
 *   then ONE batched resource read for the names (on iOS a name is a resource: ~3.45 ms a photo, measured SE2);
 * - **partial grant** — the selection the app already holds, which carries every name: no platform read;
 * - **unread partial grant** — nothing found (an empty map). An unread selection is not an empty one, but adoption
 *   can only miss here, never do harm: a photo it does not recognise is downloaded again, as before this existed. It
 *   answers rather than waits, because a background wake never opens the selection observer and its imports must
 *   not stall behind a read that will not come (limited access is an accepted gap of the spec);
 * - **no usable grant** — `null`: the library could not be read at all, and the caller asks again once it can.
 */
class MarkedPhotoLookup(
    private val gallery: GalleryReader,
    private val selection: SelectionSnapshots,
) {

    /**
     * Every marked photo in `[from, until]` outside [known], by the token its name carries, or `null` when the library
     * cannot be read.
     */
    suspend fun markedIn(from: Timestamp, until: Timestamp, known: Set<AssetId>): Map<String, AssetId>? {
        val access = gallery.access().photoAccess()
        return when (val plan = ReadPlan.of(access, selection.read)) {
            ReadPlan.Walk -> fromLibrary(from, until, known)
            is ReadPlan.Snapshot -> fromSnapshot(plan.selection.assets, known) { it.within(from, until) }
            ReadPlan.NotReadable -> if (access == PhotoAccess.LIMITED) emptyMap() else null
        }
    }

    /**
     * The marked photos in a [selection] outside [known], WHATEVER their capture date — a read of memory
     * only, which a selection change asks before it publishes the selection. No date narrows it: a member's range may
     * reach outside the event's dates, so a received photo may be dated anywhere a member could share from, and a
     * marked photo of another event only costs the one full pass that adopts nothing.
     */
    fun markedAmong(selection: SelectionState.Read, known: Set<AssetId>): Map<String, AssetId> =
        fromSnapshot(selection.assets, known) { true }

    private fun fromSnapshot(
        assets: List<GalleryAssetResources>,
        known: Set<AssetId>,
        inWindow: (CaptureDate) -> Boolean,
    ): Map<String, AssetId> = assets
        .filter { it.asset.id.assetId() !in known && inWindow(CaptureDate.of(it.asset.created)) }
        .mapNotNull { it.marked() }
        .toMap()

    private suspend fun fromLibrary(from: Timestamp, until: Timestamp, known: Set<AssetId>): Map<String, AssetId>? {
        // The whole library (event albums' folders included, as every read now reads them).
        val assets = when (val read = gallery.assets(AssetFilter(from.at, until.at))) {
            is GalleryRead.Read -> read.value
            GalleryRead.NotReadable -> return null
        }
        // The reader may return more than the window (it narrows only what the platform can express); the window decides.
        val ids = assets.filter { CaptureDate.of(it.created).within(from, until) }
            .map { it.id.assetId() }
            .filterTo(mutableSetOf()) { it !in known }
        if (ids.isEmpty()) return emptyMap()
        return when (val read = gallery.resources(ids.mapTo(linkedSetOf()) { it.platformId() })) {
            is GalleryRead.Read -> read.value.mapNotNull { it.marked() }.toMap()
            GalleryRead.NotReadable -> emptyMap()
        }
    }

    /** This asset's mark — the token its primary resource's name carries — or `null` when it carries none. */
    private fun GalleryAssetResources.marked(): Pair<String, AssetId>? {
        val primary = resources.firstOrNull { it.kind.role() == ResourceRole.PRIMARY } ?: return null
        return ReceivedPhotoName.tokenOf(primary.originalFilename)?.let { it to asset.id.assetId() }
    }
}
