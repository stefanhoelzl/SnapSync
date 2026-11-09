package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.BackendFault
import app.snapsync.levers.FileOp
import app.snapsync.levers.FilePlace
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.WorldEvent
import kotlin.test.Test

/**
 * **A cut-off upload goes on only once the service says where its bytes stand** (`UploadStandings`), read off the
 * operating system's session and the backend: an upload an Android revoke cut off is resumed from what the service
 * holds — and while the service cannot be asked, none is started; once it answers, the photo goes up. An answer that is
 * not about its bytes sends it whole, and a staged file the phone cannot look for is staged afresh.
 */
@Verifies(
    spec = "delivery",
    requirement = "A photo is never lost on the way",
    scenario = "A failed upload is retried",
)
class UploadStandingIntegrationTest {

    @Test
    fun a_cut_off_upload_whose_standing_cannot_be_asked_waits_and_goes_up_once_it_can() = behaviour {
        given { cutOff("A") }

        whenever {
            backend.fail(UPLOAD_STATE, BackendFault.UNREACHABLE, ALWAYS)
            library.permission(PhotoGrant.GRANTED)
            os.foreground()
        }
        then {
            never<WorldEvent.SessionTransferCreated>()
            settled { true }
        }

        whenever {
            backend.recover(UPLOAD_STATE)
            os.foreground()
        }
        then { event<WorldEvent.SessionTransferCreated>() }

        whenever { uploadSession.complete() }
        then { event<WorldEvent.BytesStored> { it.asset == "A" } }
    }

    @Test
    fun a_cut_off_upload_whose_standing_the_service_answers_with_a_fault_goes_whole() = behaviour {
        given { cutOff("A") }

        for (fault in listOf(BackendFault.SERVER_ERROR, BackendFault.MALFORMED)) {
            whenever {
                backend.fail(UPLOAD_STATE, fault)
                library.permission(PhotoGrant.GRANTED)
                os.foreground()
            }
            then {
                event<WorldEvent.BackendReceived> { it.operation == UPLOAD_STATE }
                event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("A") }
                settled { true }
            }
            given { cutOffByARevoke() }
        }

        whenever {
            library.permission(PhotoGrant.GRANTED)
            os.foreground()
            uploadSession.complete()
        }
        then { event<WorldEvent.BytesStored> { it.asset == "A" } }
    }

    @Test
    fun a_cut_off_upload_whose_staged_file_cannot_be_looked_for_is_staged_afresh_without_asking() = behaviour {
        given { cutOff("A") }

        whenever {
            files.fail(FilePlace.UPLOAD_STAGING, FileOp.EXISTS)
            library.permission(PhotoGrant.GRANTED)
            os.foreground()
        }
        then {
            event<WorldEvent.FilesFaulted> { it.place == FilePlace.UPLOAD_STAGING && it.op == FileOp.EXISTS }
            event<WorldEvent.SessionTransferCreated> { it.key == primaryKey("A") }
            never<WorldEvent.BackendReceived> { it.operation == UPLOAD_STATE }
        }

        whenever { uploadSession.complete() }
        then { event<WorldEvent.BytesStored> { it.asset == "A" } }
    }

    /** On an Android phone sharing through the app's session, [photo]'s upload cut off by a revoke in Settings. */
    private suspend fun Given.cutOff(photo: String) {
        androidProcess()
        appUploadsOnly()
        joinedEvent()
        photos(photo)
        uploading(photo)
        cutOffByARevoke()
    }

    private companion object {
        /** The question where a file's bytes stand. */
        const val UPLOAD_STATE = "uploadState"
        const val ALWAYS = 10_000
    }
}
