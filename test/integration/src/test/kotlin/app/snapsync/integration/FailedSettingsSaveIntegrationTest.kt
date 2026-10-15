package app.snapsync.integration

import app.snapsync.control.Choices
import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.domain.sharing.Direction
import app.snapsync.levers.WorldEvent
import app.snapsync.levers.WriteKind
import app.snapsync.ui.state.Layer
import kotlin.test.Test

/**
 * **A settings change that cannot be saved applies nothing** (capability `manage-membership`): the membership the phone
 * would not write keeps every setting it had, and none of the change's effects happen — receiving goes on, and no
 * album appears.
 */
class FailedSettingsSaveIntegrationTest {

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "A settings change that cannot be saved says so and applies nothing",
        scenario = "A failed save applies nothing",
    )
    fun receiving_turned_off_but_not_saved_keeps_receiving() = behaviour {
        given {
            joinedEvent()
            step(
                "the membership's next update refused",
                act = { databases.fail("ledger.db", "membership", WriteKind.UPDATE) },
                until = {},
            )
        }

        whenever { reconfigure(Choices(direction = Direction.UploadOnly)) }
        then {
            event<WorldEvent.DatabaseFaulted> { it.table == "membership" && it.kind == WriteKind.UPDATE }
            neverScreen<Layer.Joined> { !it.membership.receives }
        }

        whenever {
            publicBackend.foreignDevice(DEV_F, listOf("FQ"))
            os.foreground()
        }
        then { event<WorldEvent.DownloadStarted>() }
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "A settings change that cannot be saved says so and applies nothing",
        scenario = "A failed album change creates no album",
    )
    fun an_album_turned_on_but_not_saved_creates_no_album() = behaviour {
        given {
            joinedEvent(Choices(saveToAlbum = false))
            step(
                "the membership's next update refused",
                act = { databases.fail("ledger.db", "membership", WriteKind.UPDATE) },
                until = {},
            )
        }

        whenever { reconfigure(Choices(saveToAlbum = true)) }
        then {
            event<WorldEvent.DatabaseFaulted> { it.table == "membership" && it.kind == WriteKind.UPDATE }
            never<WorldEvent.LibraryAlbumCreated>()
            neverScreen<Layer.Joined> { it.membership.savesToAlbum }
        }
    }
}
