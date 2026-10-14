package app.snapsync.integration

import app.snapsync.control.behaviour.Given
import app.snapsync.levers.AlbumKind

// The named steps that make the device an Android phone where one of its systems answers as Android's does — each the
// one answer a scenario depends on, never a whole "platform": the app branches on what a port answers, not on a name.

/**
 * The photo library is an Android phone's, whose albums are folders, and the app started over it: the library's kind is
 * read as the app composes, so the app relaunches, as it starts on a phone.
 */
suspend fun Given.androidLibrary() = step(
    "an Android phone's photo library",
    act = {
        library.albumKind(AlbumKind.FOLDER)
        host.relaunch()
    },
    until = { settled { true } },
)
