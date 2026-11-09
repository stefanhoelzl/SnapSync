package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.WorldEvent
import kotlin.test.Test

/**
 * **A photo planned for upload is looked up again just before its upload starts** when the process starting it never
 * walked it — here the extension found and declared it, and the app's top-up, which runs before any walk of its own,
 * starts its transfer. What the look-up finds decides: a photo gone from the library is never uploaded and is
 * withdrawn, and a library that cannot be read now is evidence of nothing, so the photo waits and is uploaded later.
 */
class PlannedPhotoLookupIntegrationTest {

    @Test
    @Verifies(
        spec = "photo-sharing",
        requirement = "Deleting or no longer sharing a photo withdraws it",
        scenario = "A deleted photo is withdrawn",
    )
    fun a_declared_photo_deleted_before_the_app_starts_its_upload_is_never_uploaded_and_is_withdrawn() = behaviour {
        given {
            joinedEvent()
            extensionUploadsOnly()
            photos("A")
            declaredWithNoJob("A")
            step("A deleted", act = { library.remove("A") }, until = { settled { true } })
        }

        whenever { theAppAloneUploads() }
        then {
            event<WorldEvent.ManifestPublished> { "A" in it.withdrawn }
            never<WorldEvent.SessionTransferCreated>()
        }
    }

    @Test
    @Verifies(spec = "delivery", requirement = "A photo is never lost on the way")
    fun a_declared_photo_the_library_cannot_look_up_waits_and_is_uploaded_once_it_can() = behaviour {
        given {
            joinedSavingToAlbum()
            extensionUploadsOnly()
            photos("A", "B")
            declaredWithNoJob("A", "B")
            // A's placement in the album, just before its transfer, waits: B is not looked up yet.
            step("the app's top-up holding at A's album placement", act = {
                library.holdAdds(true)
                theAppAloneUploads()
            }, until = { event<WorldEvent.LibraryAddHeld>() })
        }

        whenever {
            library.permission(PhotoGrant.DENIED)
            library.holdAdds(false)
        }
        then {
            never<WorldEvent.SessionTransferCreated> { it.key == primaryKey("B") }
            settled { true }
        }

        whenever {
            library.permission(PhotoGrant.GRANTED)
            os.foreground()
        }
        then { event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("B") } }
    }
}
