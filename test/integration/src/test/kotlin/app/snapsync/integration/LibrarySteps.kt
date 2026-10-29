package app.snapsync.integration

import app.snapsync.control.behaviour.Given
import app.snapsync.levers.LibraryPhotoKind

// The shared steps that put a member's own photos of a given kind or date in the library (`Steps.kt` takes the event's
// day's camera photos).

/** An own photo [id] of [kind], taken at [date], in the library — and whatever the app does about it done. */
suspend fun Given.photoTaken(
    id: String,
    kind: LibraryPhotoKind = LibraryPhotoKind.PHOTO,
    date: String = Defaults.PHOTO_DATE,
) = step("$id taken", act = { library.add(id, kind, date) }, until = { settled { true } })
