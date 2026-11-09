package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.FileOp
import app.snapsync.levers.FilePlace
import app.snapsync.levers.WorldEvent
import kotlin.test.Test

/**
 * **A disk that fails the app's own photo on its way out** (`files.fail` on the upload staging area), read off the
 * operating system's session, the backend and the storage the app takes: a photo whose staged file could not be sealed
 * is staged again at the next opening and shared; one whose file could not be hashed still goes, whole.
 */
class UploadStagingFaultIntegrationTest {

    @Test
    @Verifies(spec = "delivery", requirement = "A photo is never lost on the way")
    fun a_photo_whose_staged_file_could_not_be_sealed_is_staged_again_and_shared() = behaviour {
        given {
            appUploadsOnly()
            joinedEvent()
            photos("A")
        }

        // Its plaintext not read, its sealed head not written, the sealed file not moved into place: no upload starts,
        // and the next opening stages it afresh.
        for ((op, times) in listOf(FileOp.READ to 2, FileOp.WRITE to 2, FileOp.MOVE to 1)) {
            whenever {
                files.fail(FilePlace.UPLOAD_STAGING, op, times)
                os.foreground()
            }
            then {
                event<WorldEvent.FilesFaulted> { it.place == FilePlace.UPLOAD_STAGING && it.op == op }
                never<WorldEvent.SessionTransferCreated>()
                settled { true }
            }
        }

        whenever { os.foreground() }
        then { event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("A") } }
        whenever { uploadSession.complete() }
        then { event<WorldEvent.BytesStored> { it.asset == "A" } }
    }

    @Test
    @Verifies(spec = "delivery", requirement = "A photo is never lost on the way")
    fun a_photo_whose_staged_file_could_not_be_hashed_is_still_shared_whole() = behaviour {
        given {
            creatingPlain()
            appUploadsOnly()
            joinedEvent()
            photos("A")
        }

        // The digest a later attempt would resume from cannot be read off the staged file: the upload goes without one.
        whenever {
            files.fail(FilePlace.UPLOAD_STAGING, FileOp.READ, 2)
            os.foreground()
        }
        then {
            event<WorldEvent.FilesFaulted> { it.place == FilePlace.UPLOAD_STAGING && it.op == FileOp.READ }
            event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("A") }
        }
        whenever { uploadSession.complete() }
        then { event<WorldEvent.BytesStored> { it.asset == "A" } }
    }
}
