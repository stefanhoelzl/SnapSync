package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.BackendFault
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.TransferEnd
import app.snapsync.levers.WorldEvent
import kotlin.test.Test

/**
 * **A cut-off upload goes on only once the service says where its bytes stand** (`UploadStandings`), read off the
 * operating system's session and the backend: an upload an Android revoke cut off is resumed from what the service
 * holds — and while the service cannot be asked, none is started; once it answers, the photo goes up.
 */
class UploadStandingIntegrationTest {

    @Test
    @Verifies(
        spec = "delivery",
        requirement = "A photo is never lost on the way",
        scenario = "A failed upload is retried",
    )
    fun a_cut_off_upload_whose_standing_cannot_be_asked_waits_and_goes_up_once_it_can() = behaviour {
        given {
            androidProcess()
            appUploadsOnly()
            joinedEvent()
            photos("A")
            step("A uploading", act = { os.foreground() }, until = {
                event<WorldEvent.SessionTransferCreated>()
                settled { true }
            })
            step("access revoked in Settings, A's upload cut off with the process", act = {
                library.permission(PhotoGrant.DENIED)
                os.foreground()
            }, until = {
                event<WorldEvent.SessionTransferEnded> { it.outcome.how == TransferEnd.How.FAILED }
                settled { true }
            })
        }

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

    private companion object {
        /** The question where a file's bytes stand. */
        const val UPLOAD_STATE = "uploadState"
        const val ALWAYS = 10_000
    }
}
