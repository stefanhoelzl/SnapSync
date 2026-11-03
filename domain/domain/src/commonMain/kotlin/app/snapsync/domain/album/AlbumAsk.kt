package app.snapsync.domain.album

import app.snapsync.domain.membership.Membership

/**
 * Who asks for the event album, and so whether an album the member deleted comes back: the member deleted it, so only
 * their deliberate opt-in recreates it.
 */
enum class AlbumAsk {
    /** The member asked for the album: a join or a rejoin with it on, or a settings Save that turned it on. */
    OptIn,

    /** Anything else: the photo grant's replay at a launch, or a settings Save that left the album as it was. */
    Replay,
    ;

    companion object {
        /** What a settings Save from [before] to [after] asks: an opt-in only when it turned the album on. */
        fun ofSave(before: Membership.Active, after: Membership.Active): AlbumAsk =
            if (after.share.saveToAlbum && !before.share.saveToAlbum) OptIn else Replay
    }
}
