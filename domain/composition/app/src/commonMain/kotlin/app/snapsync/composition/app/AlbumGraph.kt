package app.snapsync.composition.app

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
import app.snapsync.data.gallery.GrantRead
import app.snapsync.data.sharing.OwnPhotos
import app.snapsync.entrypoints.AlbumGatherPass
import app.snapsync.entrypoints.AlbumGathering
import app.snapsync.entrypoints.OnPhotoAccessChanged
import app.snapsync.inbound.AppProcessOnly
import app.snapsync.scheduling.Runtime

/**
 * The event album in the app process (`docs/architecture.md`, "The layers"): its map over the shared database, the
 * library's album calls and the use cases over them. The app is the album's one creator — this class is where it is
 * built (`@AppProcessOnly`); the upload extension's composition holds only a placement of own photos.
 */
@OptIn(AppProcessOnly::class)
class AlbumGraph(ports: AppPorts, services: ServicesGraph) {
    val map: AlbumMap = AlbumMap(services.sharedDatabase)
    val filing: AlbumFiling = AlbumFiling(ports.gallery)

    /** Making sure the joined membership's album exists — a join's, a Save's, the grant's. */
    val ensure: EnsureEventAlbum = EnsureEventAlbum(map, filing, AlbumCreation(ports.gallery))

    /** The member's own photos placed as their uploads are first queued; the extension builds its own. */
    val placeOwn: PlaceOwnPhotos = PlaceOwnPhotos(map, filing)

    /** The album an import pass files into. */
    val forImport: AlbumForImport = AlbumForImport(map, filing)

    /** An import landed in the album. */
    val markFilled: MarkAlbumFilled = MarkAlbumFilled(map, filing)

    /** The photo grant, each emission with the one before — followed only from host assembly. */
    val accessChanges: PhotoAccessChanges = PhotoAccessChanges(ports.gallery.grant)
}

/**
 * The event album's gather: what a join, a Save or a grant gathers into the album, and what a grant change does for
 * it — over the uploader's rows and the received photos, which the album itself is built before.
 */
@OptIn(AppProcessOnly::class)
class AlbumGatherGraph(
    runtime: Runtime,
    membership: MembershipGraph,
    grant: GrantRead,
    receiving: ReceivingGraph,
    uploader: UploaderGraph,
    ownPhotos: OwnPhotos,
    album: AlbumGraph,
) {
    private val pass: AlbumGatherPass = AlbumGatherPass(
        membership.branch,
        grant,
        ReceivedAlbumPhotos(receiving.counts),
        OwnAlbumPhotos(uploader.layers.rows, ownPhotos, album.filing),
        album.placeOwn,
        PlaceReceivedPhotos(album.map, album.filing),
    )

    /** The gather a join, a Save or a grant starts and does not await. */
    val gathering: AlbumGathering = AlbumGathering(runtime, pass)

    /** What a grant change does for the album. */
    val onAccessChanged: OnPhotoAccessChanged =
        OnPhotoAccessChanged(runtime, membership.branch, album.ensure, gathering)
}
