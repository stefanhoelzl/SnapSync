package app.snapsync.presentation

import app.snapsync.domain.album.AlbumChoice

/** The album switch as the screens hold it — on or off — in the domain's words, where a choice crosses into the core. */
internal object AlbumSwitch {
    fun choice(on: Boolean): AlbumChoice = if (on) AlbumChoice.SaveToAlbum else AlbumChoice.LibraryOnly
}
