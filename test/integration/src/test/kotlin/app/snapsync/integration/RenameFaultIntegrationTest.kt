package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.BackendFault
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.RenameState
import app.snapsync.ui.state.ScreenMessage
import app.snapsync.ui.state.UiIntent
import kotlin.test.Test

/**
 * **A rename the service fails** (decision record `changes/tell-service-faults`): the dialog stays with the typed name
 * and says which way SnapSync failed, and the service's word that the photos were deleted is the event over — the
 * member stays in it. Over the real composed core, the failure played by the backend mock's per-operation lever.
 */
class RenameFaultIntegrationTest {

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "A failed rename keeps the dialog and never ends the membership",
        scenario = "A rename the service fails says which way",
    )
    fun a_rename_the_service_fails_says_it_is_having_trouble() =
        aRenameThatMeets(BackendFault.SERVER_ERROR, ScreenMessage.SERVICE_TROUBLE)

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "A failed rename keeps the dialog and never ends the membership",
        scenario = "A rename the service fails says which way",
    )
    fun a_rename_during_an_update_says_so() = aRenameThatMeets(BackendFault.MAINTENANCE, ScreenMessage.SERVICE_UPDATING)

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "A failed rename keeps the dialog and never ends the membership",
        scenario = "Offline rename",
    )
    fun a_rename_that_gets_no_answer_says_so_and_to_check_the_connection() =
        aRenameThatMeets(BackendFault.UNREACHABLE, ScreenMessage.SERVICE_UNREACHABLE)

    private fun aRenameThatMeets(fault: BackendFault, told: ScreenMessage) = behaviour {
        given { joinedEvent() }

        whenever {
            backend.fail(CHANGE, fault)
            rename("Ski Trip")
        }
        then {
            screen<Layer.Joined> { it.renameState == RenameState.Failed(told) && it.membership.name == Rig.EVENT_NAME }
            neverScreen<Layer.Joined> { it.deleted }
            neverScreen<Layer.CreateEvent>()
        }
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "A failed rename keeps the dialog and never ends the membership",
        scenario = "A rename that meets a deleted event says it is over",
    )
    @Verifies(
        spec = "manage-membership",
        requirement = "Any member renames the event for everyone until its photos are deleted",
    )
    fun a_rename_answered_with_the_photos_deleted_says_the_event_is_over_and_the_member_stays() = behaviour {
        given { joinedEvent() }

        whenever {
            backend.fail(CHANGE, BackendFault.ENDED_FOR_GOOD)
            rename("Ski Trip")
        }
        then {
            screen<Layer.Joined> { it.renameState == RenameState.Failed(ScreenMessage.EVENT_OVER) }
            screen<Layer.Joined> { it.deleted && it.membership.name == Rig.EVENT_NAME }
            neverScreen<Layer.CreateEvent>()
        }
    }

    @Test
    @Verifies(
        spec = "app-experience",
        requirement = "Taps keep working and never fire twice",
        scenario = "A failure does not freeze the app",
    )
    fun after_a_rename_the_service_failed_the_next_tap_still_runs() = behaviour {
        given {
            joinedEvent()
            step(
                "a rename the service failed",
                act = {
                    backend.fail(CHANGE, BackendFault.SERVER_ERROR)
                    rename("Ski Trip")
                },
                until = { screen<Layer.Joined> { it.renameState is RenameState.Failed } },
            )
        }

        whenever { tap(UiIntent.LeaveEvent) }
        then { screen<Layer.CreateEvent>() }
    }

    private companion object {
        const val CHANGE = "changeEvent"
    }
}
