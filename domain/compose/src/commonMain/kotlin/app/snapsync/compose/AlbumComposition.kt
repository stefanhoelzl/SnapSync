package app.snapsync.compose

import app.snapsync.application.album.AlbumForImport
import app.snapsync.application.album.EnsureEventAlbum
import app.snapsync.application.album.MarkAlbumFilled
import app.snapsync.application.album.PlaceOwnPhotos
import app.snapsync.data.album.AlbumCreation
import app.snapsync.data.album.AlbumFiling
import app.snapsync.data.album.AlbumMap
import app.snapsync.inbound.AppProcessOnly

/**
 * The event album in the app process (`docs/architecture.md`, "The layers"): its map over the shared database, the
 * library's album calls, and the use cases the entry points order. The app is the album's one creator — making it is
 * this class's alone (`@AppProcessOnly`); the upload extension's composition holds no way to. Each built once, over
 * [core], on first touch.
 */
internal class AlbumLayers(private val core: AppCore) {
    private val map: AlbumMap by lazy { AlbumMap(core.services.sharedDatabase) }
    private val filing: AlbumFiling by lazy { AlbumFiling(core.ports.gallery) }

    /** Making sure the joined membership's album exists — a join's, a Save's. */
    @OptIn(AppProcessOnly::class)
    val ensure: EnsureEventAlbum by lazy { EnsureEventAlbum(map, filing, AlbumCreation(core.ports.gallery)) }

    /** The member's own photos placed as their uploads are first queued; the extension builds its own. */
    val placeOwn: PlaceOwnPhotos by lazy { PlaceOwnPhotos(map, filing) }

    /** The album an import pass files into. */
    val forImport: AlbumForImport by lazy { AlbumForImport(map, filing) }

    /** An import landed in the album. */
    val markFilled: MarkAlbumFilled by lazy { MarkAlbumFilled(map, filing) }
}
