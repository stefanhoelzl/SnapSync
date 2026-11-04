package app.snapsync.compose

import app.snapsync.application.album.AlbumForImport
import app.snapsync.application.album.EnsureEventAlbum
import app.snapsync.application.album.MarkAlbumFilled
import app.snapsync.application.album.OwnAlbumPhotos
import app.snapsync.application.album.PlaceOwnPhotos
import app.snapsync.application.album.PlaceReceivedPhotos
import app.snapsync.application.album.ReceivedAlbumPhotos
import app.snapsync.data.album.AlbumCreation
import app.snapsync.data.album.AlbumFiling
import app.snapsync.data.album.AlbumMap
import app.snapsync.data.album.PhotoAccessChanges
import app.snapsync.domain.album.AlbumContents
import app.snapsync.entrypoints.AlbumGatherPass
import app.snapsync.entrypoints.AlbumGathering
import app.snapsync.entrypoints.OnPhotoAccessChanged
import app.snapsync.inbound.AppProcessOnly

/**
 * The event album in the app process (`docs/architecture.md`, "The layers"): its map over the shared database, the
 * library's album calls, the use cases over them, the gather and the photo grant's follower. The app is the album's
 * one creator and its one gatherer — this class is where both are built (`@AppProcessOnly`); the upload extension's
 * composition holds only a placement of own photos. Each built once, over [core], on first touch.
 */
@OptIn(AppProcessOnly::class)
internal class AlbumLayers(private val core: AppCore) {
    private val map: AlbumMap by lazy { AlbumMap(core.services.sharedDatabase) }
    private val filing: AlbumFiling by lazy { AlbumFiling(core.ports.gallery) }

    /** What the event album holds on this phone, for the status host's album wording. */
    val contents: AlbumContents get() = filing.contents

    /** Making sure the joined membership's album exists — a join's, a Save's, the grant's. */
    val ensure: EnsureEventAlbum by lazy { EnsureEventAlbum(map, filing, AlbumCreation(core.ports.gallery)) }

    /** The member's own photos placed as their uploads are first queued; the extension builds its own. */
    val placeOwn: PlaceOwnPhotos by lazy { PlaceOwnPhotos(map, filing) }

    /** The album an import pass files into. */
    val forImport: AlbumForImport by lazy { AlbumForImport(map, filing) }

    /** An import landed in the album. */
    val markFilled: MarkAlbumFilled by lazy { MarkAlbumFilled(map, filing) }

    private val gatherPass: AlbumGatherPass by lazy {
        AlbumGatherPass(
            core.work.membership.branch,
            core.grant,
            ReceivedAlbumPhotos(core.receiving.counts),
            OwnAlbumPhotos(core.appUploader.layers.rows, core.ownPhotos, filing),
            placeOwn,
            PlaceReceivedPhotos(map, filing),
        )
    }

    /** The gather a join, a Save or a grant starts and does not await. */
    val gathering: AlbumGathering by lazy { AlbumGathering(core.runtime, gatherPass) }

    /** The photo grant, each emission with the one before — followed only from the UI launch. */
    val accessChanges: PhotoAccessChanges by lazy { PhotoAccessChanges(core.ports.gallery.grant) }

    /** What a grant change does for the album. */
    val onAccessChanged: OnPhotoAccessChanged by lazy {
        OnPhotoAccessChanged(core.runtime, core.work.membership.branch, ensure, gathering)
    }
}
