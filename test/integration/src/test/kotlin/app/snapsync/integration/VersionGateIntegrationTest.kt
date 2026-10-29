package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.Acts
import app.snapsync.control.behaviour.Given
import app.snapsync.control.behaviour.Seen
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.Layer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * The version gate end to end, over the REAL composed core, driven through the control protocol.
 *
 * Nothing here is simulated between the backend port and the screen. The backend mock answers a genuine `426`, as the
 * real backend does; the REAL authenticated backend composed in `AppCore` hands it to the
 * REAL `AppVersionGate`, which records it; and the REAL container reduces it into `UiState`. What each of those steps
 * proves separately is asserted separately — `CredentialedBackendTest` for the branch, `BackendContract` for the
 * refusal — and what only this can prove is
 * that they are CONNECTED, which is exactly the class of defect a device meets and no unit test sees.
 *
 * The second test is the one that matters most in the field. A refusal that never clears is a member stuck on an
 * update screen after they have already updated, and nothing about the screen itself would reveal it: the wiring
 * that heals it is a single `onServed` on a status nobody thinks about.
 */
class VersionGateIntegrationTest {

    @Test
    @Verifies(
        spec = "app-update-required",
        requirement = "An outdated app is told so at first contact",
        scenario = "Joined member with an outdated app",
    )
    @Verifies(
        spec = "app-update-required",
        requirement = "The notice says what to install and where",
        scenario = "Minimum version named",
    )
    fun a_refused_build_reaches_the_update_screen_carrying_the_minimum_and_the_remedy() = behaviour {
        given {
            joinedEvent()
            outdated()
        }

        // Any backend call at all — the gate precedes every route, which is the whole reason a refused build cannot
        // do anything and the screen may say so unconditionally.
        lateinit var refused: Seen<Layer.UpdateRequired>
        whenever { anyBackendCall() }
        then { refused = screen<Layer.UpdateRequired>() }
        assertEquals("0.4", refused.value.minimumVersion, "the screen names the version the backend named")
        assertNotNull(refused.value.store, "and offers the remedy, or it is a dead end")
    }

    @Test
    @Verifies(spec = "app-update-required", requirement = "The notice clears by itself")
    @Verifies(
        spec = "app-update-required",
        requirement = "The notice clears by itself",
        scenario = "Served again",
    )
    fun updating_clears_the_refusal_and_the_screen_goes_away() = behaviour {
        given {
            joinedEvent()
            outdated()
            refused()
        }

        // The member updates. Nothing else changes — no restart, no reset, no re-join.
        lateinit var next: Seen<WorldEvent.UiChanged>
        whenever {
            build.declare("0.4")
            anyBackendCall()
        }
        then { next = event<WorldEvent.UiChanged> { it.ui.layer !is Layer.UpdateRequired } }
        assertIs<Layer.Joined>(next.value.ui.layer, "back to the member's ordinary screen, not a stuck one")
    }

    @Test
    @Verifies(spec = "app-update-required", requirement = "The notice clears by itself")
    @Verifies(
        spec = "app-update-required",
        requirement = "The notice clears by itself",
        scenario = "Served again",
    )
    fun an_updated_build_whose_first_answer_is_a_refusal_of_the_request_is_cleared_too() = behaviour {
        // Served means past the version check, not a success: once updated, the first answer this member gets is a
        // 404 for an event the nightly sweep deleted — and that answer, too, says this build is no longer refused.
        given {
            val joined = joinedEvent()
            outdated()
            refused()
            step("the event swept", act = { backend.sweep(joined) }, until = { event<WorldEvent.EventDeleted>() })
        }

        whenever {
            build.declare("0.4")
            anyBackendCall()
        }
        then { event<WorldEvent.UiChanged> { it.ui.layer !is Layer.UpdateRequired } } // the 404 cleared the notice
    }

    @Test
    @Verifies(spec = "app-update-required", requirement = "A newer app is never mistaken for an older one")
    fun a_served_build_never_sees_the_screen() = behaviour {
        // The negative direction, and it is not ceremony: a gate that reported on every response would park
        // every member on an update screen, and the two tests above would still pass.
        given {
            joinedEvent()
            step("a build the backend serves", act = {
                backend.minAppVersion("0.4")
                build.declare("0.9")
            }, until = {})
        }

        whenever { anyBackendCall() }
        then {
            event<WorldEvent.BackendReceived>() // the call reached the backend
            neverScreen<Layer.UpdateRequired>() // a served build is not refused
        }
    }

    /** The backend requires 0.4, and this build declares 0.3 — one that predates it. */
    private suspend fun Given.outdated() = step("an outdated build", act = {
        backend.minAppVersion("0.4")
        build.declare("0.3")
    }, until = {})

    /** A backend call met the version gate, and the update screen is up. */
    private suspend fun Given.refused() =
        step("refused", act = { anyBackendCall() }, until = {
            screen<Layer.UpdateRequired>()
            settled { true }
        })

    /**
     * A backend call that changes nothing the screen shows: the download reconcile reads the joined event's union.
     * The member joins BEFORE the gate is raised, so the join itself is served.
     */
    private suspend fun Acts.anyBackendCall() {
        os.foreground()
    }
}
