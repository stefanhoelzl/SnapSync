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

/**
 * The operating system is an Android phone's, which carries the library-change wake (WorkManager's content-URI trigger),
 * and the app started over it: what the system carries is read as the app composes, so the app relaunches.
 */
suspend fun Given.androidLibraryWake() = step(
    "an Android phone's library-change wake",
    act = {
        wake.carriesLibraryWake(true)
        host.relaunch()
    },
    until = { settled { true } },
)

/**
 * The phone is Android in how its process holds uploads: the app's uploads run in its own process, which dies with it,
 * and revoking photo access in Settings ends that process. Both are read by the app as it starts, so it relaunches.
 */
suspend fun Given.androidProcess() = step(
    "an Android phone's process",
    act = {
        uploadSession.inProcess(true)
        playedOs.revokingAccessEndsTheProcess(true)
        host.relaunch()
    },
    until = { settled { true } },
)
