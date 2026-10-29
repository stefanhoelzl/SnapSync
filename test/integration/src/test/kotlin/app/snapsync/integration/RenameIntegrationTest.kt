package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.JoinedEvent
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.RenameState
import app.snapsync.ui.state.ScreenMessage
import app.snapsync.ui.state.UiIntent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Seam ↔ UI-state integration for the event rename, driven through the control protocol's rename tap (`Person.rename`) over the
 * real core — asserting **`UiState` AND backend outcomes**: the backend's event carries the new name, the joined
 * membership carries the ECHOED name (which is the heading the status screen renders), and a failure destroys
 * nothing.
 */
class RenameIntegrationTest {

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Any member renames the event for everyone until its photos are deleted",
    )
    fun a_rename_rewrites_the_backend_marker_and_the_heading_follows() = behaviour {
        val (event, _) = given { joinedWeekend() } // the heading starts at the joined name

        lateinit var renamed: Seen<Layer.Joined>
        whenever { rename("Ana's 30th") }
        then {
            screen<Layer.Joined> { it.renameState == RenameState.Succeeded }
            // The backend outcome: the shared marker the OTHER members read is what changed.
            event<WorldEvent.EventRenamed> { it.event == event && it.name == "Ana's 30th" }
            // The membership outcome: this device's membership followed, and nothing else moved. Its name IS the
            // heading the status screen renders.
            renamed = screen<Layer.Joined> { it.membership.name == "Ana's 30th" }
        }
        assertEquals(event, renamed.value.membership.eventId, "same membership — a rename never re-joins")
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Any member renames the event for everyone until its photos are deleted",
    )
    fun the_backend_TRIMMED_name_is_what_lands_everywhere() = behaviour {
        val (event, _) = given { joinedWeekend() }

        // One value, both places, no whitespace anywhere: the echo is the single source.
        whenever { rename("   Ana's 30th   ") }
        then {
            screen<Layer.Joined> { it.renameState == RenameState.Succeeded }
            event<WorldEvent.EventRenamed> { it.event == event && it.name == "Ana's 30th" }
            never<WorldEvent.EventRenamed> { it.name != "Ana's 30th" }
            screen<Layer.Joined> { it.membership.name == "Ana's 30th" }
        }
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "A failed rename keeps the dialog and never ends the membership",
        scenario = "A rejected name",
    )
    fun a_rejected_name_fails_and_changes_nothing() = behaviour {
        given { joinedWeekend() }

        // Over the backend mock's (and the real backend's) 100-character bound.
        lateinit var failed: Seen<Layer.Joined>
        whenever { rename("x".repeat(101)) }
        then {
            failed = screen<Layer.Joined> { it.renameState is RenameState.Failed }
            never<WorldEvent.EventRenamed>() // the marker is untouched
            neverScreen<Layer.Joined> { it.membership.name != "Weekend" } // the membership is untouched
        }
        assertEquals(RenameState.Failed(ScreenMessage.RENAME_NAME_REFUSED), failed.value.renameState)
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "A failed rename keeps the dialog and never ends the membership",
        scenario = "A rename that meets a missing event keeps the member joined",
    )
    fun a_rename_of_a_SWEPT_event_leaves_the_membership_joined_and_intact() = behaviour {
        val before = given {
            val (event, membership) = joinedWeekend()
            // The nightly sweep deleted the event — every subsequent request 404s.
            step("the event swept", act = { backend.sweep(event) }, until = { event<WorldEvent.EventDeleted>() })
            membership
        }

        lateinit var failed: Seen<Layer.Joined>
        whenever { rename("Ana's 30th") }
        then {
            failed = screen<Layer.Joined> { it.renameState is RenameState.Failed }
            // THE INVARIANT: a 404 is ONE witness, and the self-leave needs two. The membership must survive a rename
            // against a swept event byte for byte — the config is the only record of the join, and losing it is
            // unrecoverable. Its name is the heading, so that survives too.
            neverScreen<Layer.Joined> { it.membership != before }
            neverScreen<Layer.CreateEvent>()
        }
        // A 404 arrives as the GENERIC failure — it has no distinct meaning here by design.
        assertEquals(RenameState.Failed(ScreenMessage.RENAME_FAILED), failed.value.renameState)
        assertEquals(before, failed.value.membership, "the membership survives the 404 unchanged")
    }

    @Test
    @Verifies(
        spec = "manage-membership",
        requirement = "Any member renames the event for everyone until its photos are deleted",
    )
    fun the_reset_command_clears_the_latch_so_a_second_rename_starts_clean() = behaviour {
        val (event, _) = given {
            joinedWeekend().also {
                step("renamed", act = { rename("First") }, until = {
                    screen<Layer.Joined> { it.renameState == RenameState.Succeeded }
                    settled { true }
                })
            }
        }

        whenever { tap(UiIntent.RenameStatusConsumed) }
        then { screen<Layer.Joined> { it.renameState == RenameState.Idle } }

        whenever { rename("Second") }
        then {
            screen<Layer.Joined> { it.renameState == RenameState.Succeeded }
            event<WorldEvent.EventRenamed> { it.event == event && it.name == "Second" }
            screen<Layer.Joined> { it.membership.name == "Second" }
        }
    }

    // ---- helpers --------------------------------------------------------------------------------

    /** An event created as "Weekend" and joined, its heading shown. Answers its id and the membership the screen shows. */
    private suspend fun Given.joinedWeekend(): Pair<String, JoinedEvent> {
        val event = createdEvent(name = "Weekend")
        lateinit var joined: Seen<Layer.Joined>
        step("joined", act = { confirmJoin() }, until = {
            joined = screen<Layer.Joined> { it.membership.name == "Weekend" }
            settled { true }
        })
        return event to joined.value.membership
    }
}
