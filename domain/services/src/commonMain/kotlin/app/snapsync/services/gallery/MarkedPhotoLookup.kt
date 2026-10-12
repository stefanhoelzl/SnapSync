package app.snapsync.services.gallery

import app.snapsync.model.AssetId
import app.snapsync.model.CaptureDate
import app.snapsync.model.RESOURCE_META_ORIGINAL_FILENAME
import app.snapsync.model.ReceivedPhotoName
import app.snapsync.model.Resource
import app.snapsync.model.ResourceRole
import app.snapsync.model.SelectionPolicy
import app.snapsync.model.SelectionScope
import app.snapsync.model.factsFromResources
import app.snapsync.model.receivedWindow
import app.snapsync.ports.AssetFilter
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
 * What is read follows the grant, as upload discovery's does ([selectionScope], decision record
 * `changes/selection-is-the-walk`):
 * - **full grant** — one facts read of the whole library narrowed to the window,
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
     * Every marked photo in `[from, until]` outside [known], by the token its name carries, or `null` when the library
     * cannot be read.
     */
    suspend fun markedIn(from: CaptureDate, until: CaptureDate, known: Set<AssetId>): Map<String, AssetId>? {
        val window = receivedWindow(from, until)
        return when (val scope = selectionScope()) {
            is SelectionScope.Scoped -> fromSnapshot(scope.resources, window, known)
            SelectionScope.Unread -> emptyMap()
            SelectionScope.Unrestricted -> fromLibrary(window, AssetFilter(from.at, until.at), known)
        }
    }

    /**
     * The marked photos among a selection snapshot's [resources] outside [known], WHATEVER their capture date — a read
     * of memory only, which a selection change asks before it publishes the snapshot. No date narrows it: a member's
     * range may reach outside the event's dates, so a received photo may be dated anywhere a member could share from,
     * and a marked photo of another event only costs the one full pass that adopts nothing.
     */
    fun markedAmong(resources: List<Resource>, known: Set<AssetId>): Map<String, AssetId> =
        fromSnapshot(resources, window = null, known)

    private fun fromSnapshot(
        resources: List<Resource>,
        window: SelectionPolicy?,
        known: Set<AssetId>,
    ): Map<String, AssetId> {
        val inWindow = factsFromResources(
            resources,
        ).filter { it.assetId !in known && (window == null || window.admits(it)) }.mapTo(mutableSetOf()) { it.assetId }
        return resources
            .filter { it.assetId in inWindow && it.isPrimary() }
            .mapNotNull { r ->
                r.metadata[RESOURCE_META_ORIGINAL_FILENAME]?.let(
                    ReceivedPhotoName::tokenOf,
                )?.let { it to r.assetId }
            }
            .toMap()
    }

    private suspend fun fromLibrary(window: SelectionPolicy, filter: AssetFilter, known: Set<AssetId>): Map<String, AssetId>? {
        // The whole library (event albums' folders included, as every read now reads them).
        val facts = when (val read = gallery.assets(filter)) {
            is GalleryRead.Read -> read.value.map { it.facts() }
            GalleryRead.NotReadable -> return null
        }
        // The reader may return more than the policy (it narrows only what the platform can express); the policy decides.
        val ids = facts.filter { it.assetId !in known && window.admits(it) }.mapTo(mutableSetOf()) { it.assetId }
        if (ids.isEmpty()) return emptyMap()
        val assets = when (val read = gallery.resources(ids.mapTo(linkedSetOf()) { it.platformId() })) {
            is GalleryRead.Read -> read.value
            GalleryRead.NotReadable -> return emptyMap()
        }
        return assets.mapNotNull { asset ->
            val primary = asset.resources.firstOrNull { it.kind.role() == ResourceRole.PRIMARY } ?: return@mapNotNull null
            ReceivedPhotoName.tokenOf(primary.originalFilename)?.let { it to asset.asset.id.assetId() }
        }.toMap()
    }

    // The snapshot's resources are keyed by [uploadKey]'s `"<assetId>-<role>.<ext>"`; the primary one names the photo.
    private fun Resource.isPrimary(): Boolean = filename.startsWith("$assetId-${ResourceRole.PRIMARY.wire}.")
}
