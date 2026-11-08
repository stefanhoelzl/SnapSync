package app.snapsync.data.receiving

import app.snapsync.data.gallery.GalleryTranslation.assetId
import app.snapsync.data.gallery.GalleryTranslation.platformId
import app.snapsync.data.gallery.PhotoGrants
import app.snapsync.data.receiving.DownloadDatabaseFile.Companion.read
import app.snapsync.data.sharing.SelectionSnapshots
import app.snapsync.data.sharing.SelectionState
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.receiving.AssetPresence
import app.snapsync.domain.sharing.ReadPlan
import app.snapsync.ports.GalleryRead
import app.snapsync.ports.GalleryReader

/**
 * Whether assets this device created still exist, asked of the library under the grant's read ([ReadPlan]) — the
 * read an import that recorded its asset but never its confirmation is adjudicated by, before anything creates a
 * second one. The grant decides which source may answer, and what a miss means:
 *
 * - **full grant** — a fetch by identifier: a miss is [AssetPresence.ABSENT];
 * - **partial grant** — the held selection: a miss is [AssetPresence.UNKNOWN], never absence, because app-created
 *   assets join the selection at creation only, so one created under a full grant is real but invisible after a
 *   downgrade (measured);
 * - **no grant, or an unread selection** — nothing can be seen: every answer is unknown, and the row waits.
 *
 * Batched: one call per pass for every unconfirmed row, and none when no row carries a marker. The iOS fetch is a
 * synchronous XPC round-trip no timeout can abandon, so it is asked outside the store's lock.
 */
class LibraryPresence(private val gallery: GalleryReader, private val selection: SelectionSnapshots) {

    /** The verdict for each of [ids] — an entry for every id asked about. */
    suspend fun of(ids: Set<AssetId>): Map<AssetId, AssetPresence> =
        when (val plan = ReadPlan.of(PhotoGrants.of(gallery.access()).access, selection.read)) {
            ReadPlan.Walk -> fetched(ids)
            is ReadPlan.Snapshot -> selected(ids, plan.selection)
            ReadPlan.NotReadable -> ids.associateWith { AssetPresence.UNKNOWN }
        }

    private suspend fun fetched(ids: Set<AssetId>): Map<AssetId, AssetPresence> =
        when (val read = gallery.assetsById(ids.mapTo(linkedSetOf()) { it.platformId() })) {
            is GalleryRead.Read -> read.value.mapTo(mutableSetOf()) { it.id.assetId() }
                .let { found -> ids.associateWith { AssetPresence.inLibrary(it in found) } }
            GalleryRead.NotReadable -> ids.associateWith { AssetPresence.UNKNOWN }
        }

    private fun selected(ids: Set<AssetId>, selection: SelectionState.Read): Map<AssetId, AssetPresence> =
        selection.assets.mapTo(mutableSetOf()) { it.asset.id.assetId() }
            .let { found -> ids.associateWith { AssetPresence.inSelection(it in found) } }
}
