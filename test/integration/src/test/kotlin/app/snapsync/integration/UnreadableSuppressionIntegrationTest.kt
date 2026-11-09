package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import kotlin.test.Test

/**
 * **No photo goes up while the record of received photos cannot be read** (capability `receiving-photos`): that
 * record is what tells a received photo from the member's own, so iOS's uploader that cannot read it uploads nothing —
 * and once it reads again, the member's own photos go up.
 */
class UnreadableSuppressionIntegrationTest {

    @Test
    @Verifies(spec = "receiving-photos", requirement = "Received photos are never shared back")
    fun ios_uploader_uploads_nothing_while_the_received_photos_record_cannot_be_read() = behaviour {
        given {
            extensionUploadsOnly()
            joinedEvent()
            photos("A")
            step(
                "the received photos' record unreadable",
                act = { databases.unreadable(DOWNLOADS_DB, on = true) },
                until = {},
            )
        }

        whenever { cycle() }
        then {
            never<WorldEvent.UploadJobCreated>()
            settled { true }
        }

        whenever {
            databases.unreadable(DOWNLOADS_DB, on = false)
            cycle()
        }
        then { event<WorldEvent.UploadJobCreated> { it.job == primaryJob("A") } }
    }

    private companion object {
        const val DOWNLOADS_DB = "downloads.db"
    }
}
