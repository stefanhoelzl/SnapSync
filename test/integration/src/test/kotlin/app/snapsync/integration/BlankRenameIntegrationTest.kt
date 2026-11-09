package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.RenameState
import app.snapsync.ui.state.ScreenMessage
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **A rename to nothing but spaces is refused on the phone**: the name trims to nothing, so the event keeps its name,
 * and the dialog says the name was refused.
 */
class BlankRenameIntegrationTest {

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "A failed rename keeps the dialog and never ends the membership",
        scenario = "A rejected name",
    )
    fun a_name_of_only_spaces_is_refused_and_changes_nothing() = behaviour {
        given { joinedEvent() }

        lateinit var failed: Seen<Layer.Joined>
        whenever { rename("   ") }
        then {
            failed = screen<Layer.Joined> { it.renameState is RenameState.Failed }
            never<WorldEvent.EventRenamed>()
            neverScreen<Layer.Joined> { it.membership.name != Defaults.EVENT_NAME }
        }
        assertEquals(RenameState.Failed(ScreenMessage.RENAME_NAME_REFUSED), failed.value.renameState)
    }
}
