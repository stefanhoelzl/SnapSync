package app.snapsync.domain.album

import app.snapsync.domain.asset.AssetId
import app.snapsync.domain.sharing.PhotoAccess

/** What the album map holds for an event. */
sealed interface AlbumEntry {
    /** The album made for the event, and whether it has held a photo. */
    data class Mapped(val album: EventAlbumId, val fill: AlbumFill) : AlbumEntry

    /** No album was ever made for the event. */
    data object None : AlbumEntry

    /** The map could not be read: whether an album exists is unknown — so none is made beside one it lost sight of. */
    data object Unreadable : AlbumEntry
}

/** Whether a membership's album may be made or used now: the member wants one, and the grant lets the app write it. */
sealed interface AlbumWishVerdict {
    data object Wanted : AlbumWishVerdict

    data object NotWanted : AlbumWishVerdict

    /** No usable photo access: no album can be made or filled. Full or limited access both write albums (measured). */
    data object NoAccess : AlbumWishVerdict

    companion object {
        fun of(choice: AlbumChoice, access: PhotoAccess): AlbumWishVerdict = when (choice) {
            AlbumChoice.LibraryOnly -> NotWanted
            AlbumChoice.SaveToAlbum -> when (access) {
                PhotoAccess.FULL, PhotoAccess.LIMITED -> Wanted
                PhotoAccess.NONE -> NoAccess
            }
        }
    }
}

/** What becomes of an album the map holds, once it is known whether it is still there. */
sealed interface StoredAlbumVerdict {
    /** It is there: reused, so a rejoin keeps the photos the earlier membership placed. */
    data class Reuse(val album: EventAlbumId) : StoredAlbumVerdict

    /** The member deleted it and asked for the album again: a new one is made. */
    data object Recreate : StoredAlbumVerdict

    /** The member deleted it and did not ask: it stays deleted. */
    data object KeptDeleted : StoredAlbumVerdict

    companion object {
        fun of(album: EventAlbumId, standing: AlbumStanding, ask: AlbumAsk): StoredAlbumVerdict = when (standing) {
            AlbumStanding.Present -> Reuse(album)
            AlbumStanding.Deleted -> when (ask) {
                AlbumAsk.OptIn -> Recreate
                AlbumAsk.Replay -> KeptDeleted
            }
        }
    }
}

/** The album an import files a received photo into, read once before the import's own change. */
sealed interface ImportAlbumVerdict {
    data class Into(val album: EventAlbumId) : ImportAlbumVerdict

    /** No album: not wanted, never made, or one the member deleted — the photo lands where the library puts it. */
    data object CameraFolder : ImportAlbumVerdict

    companion object {
        fun of(choice: AlbumChoice, album: EventAlbumId, standing: AlbumStanding): ImportAlbumVerdict =
            when (choice) {
                AlbumChoice.LibraryOnly -> CameraFolder
                AlbumChoice.SaveToAlbum -> when (standing) {
                    AlbumStanding.Present -> Into(album)
                    AlbumStanding.Deleted -> CameraFolder
                }
            }
    }
}

/** Whether the member's own photos are placed in the album as their uploads are first queued, and where. */
sealed interface OwnPlacementVerdict {
    data class Place(val album: EventAlbumId, val assets: List<AssetId>) : OwnPlacementVerdict

    /** Nothing placed: not wanted, own photos stay in the camera folder here, no album yet, or nothing to place. */
    data object Skip : OwnPlacementVerdict

    companion object {
        fun of(
            choice: AlbumChoice,
            contents: AlbumContents,
            entry: AlbumEntry,
            assets: List<AssetId>,
        ): OwnPlacementVerdict = when (choice) {
            AlbumChoice.LibraryOnly -> Skip
            AlbumChoice.SaveToAlbum -> when (contents.ownPhotos) {
                OwnPhotoHome.StayInCameraFolder -> Skip
                OwnPhotoHome.IntoAlbum -> into(entry, assets)
            }
        }

        /** Never an album made here: the app makes it before sync needs it, so with none yet nothing is placed. */
        private fun into(entry: AlbumEntry, assets: List<AssetId>): OwnPlacementVerdict = when (entry) {
            is AlbumEntry.Mapped -> if (assets.isEmpty()) Skip else Place(entry.album, assets)
            AlbumEntry.None, AlbumEntry.Unreadable -> Skip
        }
    }
}

/** Whether photos this device received are filed into the album by a gather — on a folder phone, a MOVE. */
sealed interface ReceivedPlacementVerdict {
    data class Place(val album: EventAlbumId, val assets: List<AssetId>) : ReceivedPlacementVerdict

    data object Skip : ReceivedPlacementVerdict

    companion object {
        fun of(entry: AlbumEntry, assets: List<AssetId>): ReceivedPlacementVerdict = when (entry) {
            is AlbumEntry.Mapped -> if (assets.isEmpty()) Skip else Place(entry.album, assets)
            AlbumEntry.None, AlbumEntry.Unreadable -> Skip
        }
    }
}

/**
 * Whether a photo landing in the album marks it as having held one — what later tells an emptied folder from a new
 * one.
 */
enum class FillMarkVerdict {
    Mark,
    Leave,
    ;

    companion object {
        /** After an import into [landedIn]: a folder album the event still maps to [landedIn] is marked. */
        fun afterImport(contents: AlbumContents, entry: AlbumEntry, landedIn: EventAlbumId): FillMarkVerdict =
            when (contents) {
                AlbumContents.SharedAndReceived -> Leave
                AlbumContents.Received -> when (entry) {
                    is AlbumEntry.Mapped -> if (entry.album == landedIn) Mark else Leave // a recreation raced it
                    AlbumEntry.None, AlbumEntry.Unreadable -> Leave
                }
            }

        /** After a gather's move of received photos: a folder album the library took them into is marked. */
        fun afterMove(contents: AlbumContents, landed: AlbumAdd): FillMarkVerdict = when (landed) {
            AlbumAdd.Added -> when (contents) {
                AlbumContents.SharedAndReceived -> Leave
                AlbumContents.Received -> Mark
            }
            AlbumAdd.NotAdded -> Leave
        }
    }
}

/** What the library did with an add to an album. */
enum class AlbumAdd { Added, NotAdded }

/** What a gather of the photos already held places, for a membership it was asked for. */
sealed interface GatherScopeVerdict {
    /** Received photos always; the member's own only where they go into the album ([OwnPhotoHome]). */
    data class Gather(val own: OwnPhotoHome) : GatherScopeVerdict

    data object NotWanted : GatherScopeVerdict

    /** No usable photo access: every add would fail. */
    data object NoAccess : GatherScopeVerdict

    companion object {
        fun of(choice: AlbumChoice, access: PhotoAccess, contents: AlbumContents): GatherScopeVerdict =
            when (AlbumWishVerdict.of(choice, access)) {
                AlbumWishVerdict.Wanted -> Gather(contents.ownPhotos)
                AlbumWishVerdict.NotWanted -> NotWanted
                AlbumWishVerdict.NoAccess -> NoAccess
            }
    }
}

/** What a change of the photo grant does for the joined membership's album. */
enum class GrantVerdict {
    /** No usable access now: nothing. */
    Nothing,

    /** Usable at the launch's first look, or usable before and after (limited → full): the album is ensured only. */
    EnsureOnly,

    /** Access became usable while the app runs: the album is ensured, then what is already held is gathered into it. */
    EnsureAndGather,
    ;

    companion object {
        /** [before] is `null` for the launch's first look, which is the grant as it stood, not a change. */
        fun of(before: PhotoAccess?, now: PhotoAccess): GrantVerdict = when (now) {
            PhotoAccess.NONE -> Nothing
            PhotoAccess.FULL, PhotoAccess.LIMITED -> if (before == PhotoAccess.NONE) EnsureAndGather else EnsureOnly
        }
    }
}
