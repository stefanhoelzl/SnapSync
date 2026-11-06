package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.PhotoGrant
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.SyncHealth
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * A member who joined before granting photo access grants it while the app is in front — a host who creates an event
 * first and allows photos from the status line's "Allow access" afterwards. The total of what they share is counted
 * once their received photos are recognised, so the grant's own recognition must be followed by a count: without one,
 * the line stays on "Loading…" until the app is next opened.
 */
@Verifies(spec = "sync-status", requirement = "The status stays current while the app is open")
class LateGrantStatusIntegrationTest {

    @Test
    fun a_grant_given_while_in_front_leaves_the_neutral_line() = behaviour {
        given {
            photoAccess(PhotoGrant.NOT_DETERMINED)
            step(
                "the join's photo dialog answered with no",
                act = { library.requestAnswer(PhotoGrant.DENIED) },
                until = {},
            )
            joinedEvent()
            step("in front, without access", act = { os.foreground() }, until = {
                screen<Layer.Joined> { it.health is SyncHealth.NeedsAccess }
                settled { true }
            })
        }

        // Within the spec's "about two seconds" of the grant, the line says what was read.
        whenever {
            library.permission(PhotoGrant.GRANTED)
            advance(2.seconds)
        }
        then {
            screen<Layer.Joined> { it.health == SyncHealth.InSync }
            settled { true }
        }
    }
}
