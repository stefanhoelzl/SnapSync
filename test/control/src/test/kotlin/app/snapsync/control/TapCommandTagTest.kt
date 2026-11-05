package app.snapsync.control

import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.EntryOutcomeKind
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import kotlin.test.Test

/**
 * The work a tap's command starts carries the act's tag (`docs/testing.md`, "How a test waits"), so a test waits on its
 * own act's `done` through the command, not only through the tap. A tap is answered once it is handed over, and the
 * command it fires is fired from the screen's container afterwards: this holds that the command's delivery still
 * belongs to the act — whatever delivers the tap to the container.
 */
class TapCommandTagTest {

    @Test
    fun a_join_confirmed_on_the_screen_is_delivered_carrying_the_act_s_tag() = behaviour {
        given {
            step("in front", act = { os.foreground() }, until = { settled { true } })
            step(
                "an event created, its join gate open",
                act = { createEvent("Probe", "2026-06-01T10:00", "2026-06-02T10:00") },
                until = { screen<Layer.JoiningEvent> { it.phase is JoinPhase.Detailed } },
            )
        }
        whenever { confirmJoin() }
        then {
            inOrder {
                event<WorldEvent.Delivered> { it.entry == "OnJoinConfirmed" && it.tag != null }
                event<WorldEvent.Done> {
                    it.entry == "OnJoinConfirmed" && it.outcome == EntryOutcomeKind.COMPLETED && it.tag != null
                }
            }
        }
    }
}
