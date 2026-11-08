package app.snapsync.data.receiving

import app.snapsync.data.gallery.PhotoGrants
import app.snapsync.data.gallery.assetId
import app.snapsync.data.gallery.platformId
import app.snapsync.data.gallery.role
import app.snapsync.data.sharing.SelectionSnapshots
import app.snapsync.data.sharing.SelectionState
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.asset.CaptureDate
import app.snapsync.domain.asset.ResourceRole
import app.snapsync.domain.receiving.ReceivedPhotoName
import app.snapsync.domain.receiving.RecognitionWindow
import app.snapsync.domain.sharing.ReadPlan
import app.snapsync.ports.AssetFilter
import app.snapsync.ports.GalleryAssetResources
import app.snapsync.ports.GalleryRead
import app.snapsync.ports.GalleryReader

/**
 * The library's SnapSync-marked photos: what a recognition pass reads to recognise photos an earlier install received,
 * by the mark each one's filename carries ([ReceivedPhotoName]). Candidates are narrowed by the pass's window alone,
 * never an exact capture date: a received photo's date can drift from the union's (Android fills it from the file's
 * EXIF), and a missed candidate is a duplicate download. Assets in `known` — the ones the store already records — are
 * never read.
 *
 * What is read follows the grant, by the read plan sharing takes ([ReadPlan], `changes/selection-is-the-walk`):
 * - **full grant** — one facts read of the whole library narrowed to the window, then ONE batched resource read for
 *   the names (on iOS a name is a resource: ~3.45 ms a photo, measured SE2);
 * - **partial grant** — the selection the app already holds, which carries every name: no platform read;
 * - **unread partial grant** — read as an empty selection ([NOTHING_READ]): nothing found. An unread selection is not
 *   an empty one, but recognition can only miss here, never do harm: a photo it does not recognise is downloaded again.
 *   It answers rather than waits, because a background wake never opens the selection observer and its imports must
 *   not stall behind a read that will not come;
 * - **no usable grant** — [MarkedRead.NotReadable]: the caller asks again once it can.
 */
class MarkedPhotos(private val gallery: GalleryReader, private val selection: SelectionSnapshots) {

    /** Every marked photo in [window] outside [known], by the token its name carries. */
    suspend fun inWindow(window: RecognitionWindow, known: Set<AssetId>): MarkedRead =
        when (val plan = ReadPlan.of(PhotoGrants.of(gallery.access()).access, selection.read ?: NOTHING_READ)) {
            ReadPlan.Walk -> fromLibrary(window, known)
            is ReadPlan.Snapshot -> MarkedRead.Found(
                marked(plan.selection.assets, known) { it.within(window.from, window.until) },
            )
            ReadPlan.NotReadable -> MarkedRead.NotReadable
        }

    /**
     * The marked photos of [selection] outside [known], WHATEVER their capture date — a read of memory only, which a
     * selection change asks before it publishes the selection.
     */
    fun among(
        selection: SelectionState.Read,
        known: Set<AssetId>,
    ): Map<String, AssetId> = marked(selection.assets, known) { true }

    private suspend fun fromLibrary(window: RecognitionWindow, known: Set<AssetId>): MarkedRead =
        when (val read = gallery.assets(AssetFilter(window.from.at, window.until.at))) {
            // The reader may return more than the window, narrowing only what the platform can express: it decides.
            is GalleryRead.Read -> named(
                read.value.filter { CaptureDate.of(it.created).within(window.from, window.until) }
                    .map { it.id.assetId() }.filterTo(mutableSetOf()) { it !in known },
            )
            GalleryRead.NotReadable -> MarkedRead.NotReadable
        }

    /** The names of [ids]' resources, read in ONE batch — and no read at all for none. */
    private suspend fun named(ids: Set<AssetId>): MarkedRead = when (ids.isEmpty()) {
        true -> MarkedRead.Found(emptyMap())
        false -> resourcesOf(ids)
    }

    private suspend fun resourcesOf(ids: Set<AssetId>): MarkedRead =
        when (val read = gallery.resources(ids.mapTo(linkedSetOf()) { it.platformId() })) {
            is GalleryRead.Read -> MarkedRead.Found(read.value.mapNotNull { it.mark() }.toMap())
            GalleryRead.NotReadable -> MarkedRead.Found(emptyMap())
        }

    private fun marked(
        assets: List<GalleryAssetResources>,
        known: Set<AssetId>,
        inWindow: (CaptureDate) -> Boolean,
    ): Map<String, AssetId> = assets
        .filter { it.asset.id.assetId() !in known && inWindow(CaptureDate.of(it.asset.created)) }
        .mapNotNull { it.mark() }
        .toMap()
}

/** The marked photos found, by token, or that the library could not be read. */
sealed interface MarkedRead {
    class Found(val byToken: Map<String, AssetId>) : MarkedRead

    data object NotReadable : MarkedRead
}

/** This asset's mark — the token its primary resource's name carries — or `null` when it carries none. */
/** A selection not read yet, as recognition reads it: one that holds no marked photo. */
private val NOTHING_READ = SelectionState.Read(emptyList())

private fun GalleryAssetResources.mark(): Pair<String, AssetId>? =
    resources.firstOrNull { it.kind.role() == ResourceRole.PRIMARY }?.let { primary ->
        ReceivedPhotoName.tokenOf(primary.originalFilename)?.let { it to asset.id.assetId() }
    }
