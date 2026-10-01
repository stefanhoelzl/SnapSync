package app.snapsync.services.gallery

import app.snapsync.model.AssetId
import app.snapsync.model.EventEnd
import app.snapsync.model.EventStart
import app.snapsync.model.GalleryRead
import app.snapsync.model.RESOURCE_META_ORIGINAL_FILENAME
import app.snapsync.model.ReceivedPhotoName
import app.snapsync.model.Resource
import app.snapsync.model.ResourceRole
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.SelectionScope
import app.snapsync.model.eventWindow
import app.snapsync.model.factsFromResources
import app.snapsync.ports.GalleryReader

/**
 * The library's SnapSync-marked photos inside one event's window (capability `receiving-photos`): what a join reads
 * to recognise photos an earlier install received, by the mark each one's filename carries ([ReceivedPhotoName]).
 *
 * The window is the EVENT's range, never the member's capture range — receiving covers the whole event, and the
 * event's range is fixed at creation. Candidates are narrowed by that window alone, not by an exact capture date: a
 * received photo's date can drift from the union's (Android fills it from the file's EXIF), and a missed candidate is
 * a duplicate download. Assets in [known] — the ones the download store already records — are never read.
 *
 * What is read follows the grant, as upload discovery's does ([selectionScope], decision record
 * `changes/selection-is-the-walk`):
 * - **full grant** — one facts read of the whole library (event albums' folders included) narrowed to the window,
 *   then ONE batched resource read for the names (on iOS a name is a resource: ~3.45 ms a photo, measured SE2);
 * - **partial grant** — the selection snapshot the app already holds, which carries every name: no platform read;
 * - **unread partial grant** — nothing found (an empty map). An unread selection is not an empty one, but adoption
 *   can only miss here, never do harm: a photo it does not recognise is downloaded again, as before this existed. It
 *   answers rather than waits, because a background wake never opens the selection observer and its imports must
 *   not stall behind a read that will not come (limited access is an accepted gap of the spec);
 * - **no usable grant** — `null`: the library could not be read at all, and the caller asks again once it can.
 */
class MarkedPhotoLookup(
    private val gallery: GalleryReader,
    private val selectionScope: () -> SelectionScope,
) {

    /**
     * Every marked photo in `[startsAt, endsAt]` outside [known], by the token its name carries, or `null` when the
     * library cannot be read. A null [endsAt] (a membership saved before events carried an end) bounds the window
     * below only.
     */
    suspend fun markedIn(startsAt: EventStart, endsAt: EventEnd?, known: Set<AssetId>): Map<String, AssetId>? {
        val window = eventWindow(startsAt, endsAt)
        return when (val scope = selectionScope()) {
            is SelectionScope.Scoped -> fromSnapshot(scope.resources, window, known)
            SelectionScope.Unread -> emptyMap()
            SelectionScope.Unrestricted -> fromLibrary(window, known)
        }
    }

    private fun fromSnapshot(resources: List<Resource>, window: SelectionPolicy, known: Set<AssetId>): Map<String, AssetId> {
        val inWindow = factsFromResources(resources).filter { it.assetId !in known && window.admits(it) }.mapTo(mutableSetOf()) { it.assetId }
        return resources
            .filter { it.assetId in inWindow && it.isPrimary() }
            .mapNotNull { r -> r.metadata[RESOURCE_META_ORIGINAL_FILENAME]?.let(ReceivedPhotoName::tokenOf)?.let { it to r.assetId } }
            .toMap()
    }

    private suspend fun fromLibrary(window: SelectionPolicy, known: Set<AssetId>): Map<String, AssetId>? {
        // The whole library, not the sharing candidates: on Android a received photo filed into an event album lives in
        // a folder the candidates leave out, and it must be recognised all the same.
        val facts = (gallery.libraryAssets(window) as? GalleryRead.Read)?.value ?: return null
        // The reader may return more than the policy (it narrows only what the platform can express); the policy decides.
        val ids = facts.filter { it.assetId !in known && window.admits(it) }.mapTo(mutableSetOf()) { it.assetId }
        if (ids.isEmpty()) return emptyMap()
        val assets = (gallery.resources(ids) as? GalleryRead.Read)?.value ?: return emptyMap()
        return assets.mapNotNull { asset ->
            val primary = asset.rawResources.firstOrNull { it.role == ResourceRole.PRIMARY } ?: return@mapNotNull null
            ReceivedPhotoName.tokenOf(primary.originalFilename)?.let { it to asset.assetId }
        }.toMap()
    }

    // The snapshot's resources are keyed by [uploadKey]'s `"<assetId>-<role>.<ext>"`; the primary one names the photo.
    private fun Resource.isPrimary(): Boolean = filename.startsWith("$assetId-${ResourceRole.PRIMARY.wire}.")
}
