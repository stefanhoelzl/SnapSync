package app.snapsync.application.album

import app.snapsync.application.EntryPointCall
import app.snapsync.data.album.AlbumFiling
import app.snapsync.data.album.AlbumMap
import app.snapsync.data.receiving.DownloadRead
import app.snapsync.data.receiving.ReceivingCounts
import app.snapsync.data.store.StoreWrite
import app.snapsync.domain.album.AlbumAdd
import app.snapsync.domain.album.AlbumChoice
import app.snapsync.domain.album.AlbumEntry
import app.snapsync.domain.album.EventAlbumId
import app.snapsync.domain.album.FillMarkVerdict
import app.snapsync.domain.album.ImportAlbumVerdict
import app.snapsync.domain.album.OwnPhotoHome
import app.snapsync.domain.album.OwnPlacementVerdict
import app.snapsync.domain.album.ReceivedPlacementVerdict
import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.event.EventId
import app.snapsync.domain.membership.Membership
import app.snapsync.inbound.AppProcessOnly
import app.snapsync.inbound.Log
import app.snapsync.inbound.of

/**
 * Place the member's own photos in their album as their uploads are first queued — the one album use case the upload
 * extension holds: it only ever adds to an album the app made, never makes one. Best-effort: a failed add is logged and
 * placement never holds up an upload.
 */
class PlaceOwnPhotos(private val map: AlbumMap, private val filing: AlbumFiling) {
    private val log = Log("PlaceOwnPhotos")

    @EntryPointCall
    suspend operator fun invoke(membership: Membership.Active, assets: List<AssetId>): PlaceOutcome =
        when (OwnPlacementVerdict.home(membership.share.album, filing.contents)) {
            OwnPhotoHome.IntoAlbum -> into(membership.event.id, assets)
            OwnPhotoHome.StayInCameraFolder -> PlaceOutcome.Skipped
        }

    private suspend fun into(event: EventId, assets: List<AssetId>): PlaceOutcome =
        when (val verdict = OwnPlacementVerdict.of(map.entry(event), assets)) {
            is OwnPlacementVerdict.Place -> placed(filing.add(verdict.album, verdict.assets), verdict.album, assets)
            OwnPlacementVerdict.Skip -> PlaceOutcome.Skipped
        }

    private suspend fun placed(add: AlbumAdd, album: EventAlbumId, assets: List<AssetId>) = PlaceOutcome.of(add).also {
        log.i("own photos placed", "album" of album.value, "count" of assets.size, "added" of add)
    }
}

/**
 * File photos this device RECEIVED for [an event][invoke] into its album — a gather's received half; on a folder
 * phone a MOVE out of the camera folder, after which the album has held a photo. The app's alone: the extension never
 * gathers.
 */
class PlaceReceivedPhotos @AppProcessOnly constructor(private val map: AlbumMap, private val filing: AlbumFiling) {
    private val log = Log("PlaceReceivedPhotos")

    @EntryPointCall
    suspend operator fun invoke(event: EventId, assets: List<AssetId>): PlaceOutcome =
        when (val verdict = ReceivedPlacementVerdict.of(map.entry(event), assets)) {
            is ReceivedPlacementVerdict.Place -> moved(event, verdict, filing.add(verdict.album, verdict.assets))
            ReceivedPlacementVerdict.Skip -> PlaceOutcome.Skipped
        }

    private suspend fun moved(event: EventId, verdict: ReceivedPlacementVerdict.Place, add: AlbumAdd): PlaceOutcome {
        log.i("received photos placed", "album" of verdict.album.value, "count" of verdict.assets.size, "added" of add)
        when (FillMarkVerdict.afterMove(filing.contents, add)) {
            FillMarkVerdict.Mark -> marked(map.markFilled(event))
            FillMarkVerdict.Leave -> Unit
        }
        return PlaceOutcome.of(add)
    }

    private suspend fun marked(written: StoreWrite) = when (written) {
        StoreWrite.Written -> Unit
        is StoreWrite.Failed -> log.w("album not marked filled", "why" of written.detail)
    }
}

/** What a placement came to. */
sealed interface PlaceOutcome {
    data object Placed : PlaceOutcome

    /** The library did not take the add: logged, nothing else depends on it. */
    data object NotPlaced : PlaceOutcome

    /** Nothing to place, or nowhere to place it. */
    data object Skipped : PlaceOutcome

    companion object {
        fun of(add: AlbumAdd): PlaceOutcome = when (add) {
            AlbumAdd.Added -> Placed
            AlbumAdd.NotAdded -> NotPlaced
        }
    }
}

/**
 * The album the joined membership's imports file into, read once before an import pass so the library's change does no
 * lookup of its own. A folder album the member emptied, deleted or renamed answers none: the photo lands in the camera
 * folder rather than bringing the album back.
 */
class AlbumForImport(private val map: AlbumMap, private val filing: AlbumFiling) {
    @EntryPointCall
    suspend operator fun invoke(membership: Membership.Active): ImportAlbumVerdict = when (membership.share.album) {
        AlbumChoice.SaveToAlbum -> chosen(map.entry(membership.event.id))
        AlbumChoice.LibraryOnly -> ImportAlbumVerdict.CameraFolder
    }

    private suspend fun chosen(entry: AlbumEntry): ImportAlbumVerdict = when (entry) {
        is AlbumEntry.Mapped ->
            ImportAlbumVerdict.of(entry.album, filing.contents.standing(entry.fill, filing.presence(entry.album)))
        AlbumEntry.None, AlbumEntry.Unreadable -> ImportAlbumVerdict.CameraFolder
    }
}

/** An import pass filed a photo into [the album][invoke]: on a folder phone it has now held one. */
class MarkAlbumFilled(private val map: AlbumMap, private val filing: AlbumFiling) {
    private val log = Log("MarkAlbumFilled")

    @EntryPointCall
    suspend operator fun invoke(event: EventId, landedIn: EventAlbumId) =
        when (FillMarkVerdict.afterImport(filing.contents, map.entry(event), landedIn)) {
            FillMarkVerdict.Mark -> marked(map.markFilled(event))
            FillMarkVerdict.Leave -> Unit
        }

    private suspend fun marked(written: StoreWrite) = when (written) {
        StoreWrite.Written -> log.i("album marked filled")
        is StoreWrite.Failed -> log.w("album not marked filled", "why" of written.detail)
    }
}

/** What this device received for an event, for a gather to file: receiving's own record of its imports. */
class ReceivedAlbumPhotos(private val counts: ReceivingCounts) {
    @EntryPointCall
    operator fun invoke(event: EventId): ReceivedAlbumRead = when (val read = counts.importedOf(event)) {
        is DownloadRead.Ok -> ReceivedAlbumRead.Read(read.value.sorted())
        is DownloadRead.Unavailable -> ReceivedAlbumRead.Unavailable(read.detail)
    }
}

/** The photos received for an event, or that the store could not say. */
sealed interface ReceivedAlbumRead {
    data class Read(val assets: List<AssetId>) : ReceivedAlbumRead

    data class Unavailable(val detail: String) : ReceivedAlbumRead
}
