package app.snapsync.contracts

import app.snapsync.model.InviteLinkHints
import app.snapsync.model.UploaderPin
import app.snapsync.ports.DevControls
import app.snapsync.ports.DevHandlers
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which developer controls the build carries, and how they are set when a clause starts. */
enum class DevControlsState {
    /** A shipped build: no control is switched, and nothing resets the device from outside. */
    SHIPPED,

    /**
     * A rig build whose controls the channel switched: the app's uploader pinned off, invite hints honoured, new events
     * created plain — and a reset the channel can deliver ([DevControlsUnderTest.resetFromChannel]).
     */
    SWITCHED,
}

/** The port, and — on a build that has one — the control channel's reset. */
class DevControlsUnderTest(val controls: DevControls, val resetFromChannel: (suspend () -> Unit)? = null)

/**
 * What the build's developer controls promise the composition (`docs/architecture.md`): a shipped build answers every
 * control as shipped — no pin, hints ignored, events encrypted — and a rig build answers what its channel set, and
 * delivers the channel's reset to the composition that listened.
 */
object DevControlsContract : Contract<DevControlsState, DevControlsUnderTest>("DevControls") {

    /** The pin [DevControlsState.SWITCHED] carries: the app's uploader off, the extension on. */
    val PINNED = UploaderPin(app = false, extension = true)

    override val clauses = clauses {

        clause(
            "SHIPPED_EVERY_CONTROL_IS_AS_SHIPPED",
            DevControlsState.SHIPPED,
            covers = cells {
                on<DevControls> {
                    answers(DevControls::listen).returns()
                    answers(DevControls::uploaderPin).with(null)
                    answers(DevControls::inviteLinkHints).with(InviteLinkHints.Ignored)
                    answers(DevControls::createsPlainEvents).with(false)
                }
            },
        ) { subject ->
            subject.controls.listen(DevHandlers(onReset = { error("a shipped build resets nothing") }))
            assertNull(subject.controls.uploaderPin(), "both uploaders, as a shipped build runs them")
            assertEquals(InviteLinkHints.Ignored, subject.controls.inviteLinkHints(), "a link is an ordinary invite")
            assertFalse(subject.controls.createsPlainEvents(), "every event is created encrypted")
        }

        clause(
            "SWITCHED_EVERY_CONTROL_ANSWERS_THE_CHANNEL_AND_ITS_RESET_ARRIVES",
            DevControlsState.SWITCHED,
            covers = cells {
                on<DevControls> {
                    answers(DevControls::listen).returns()
                    answers(DevControls::uploaderPin).returns()
                    answers(DevControls::inviteLinkHints).with(InviteLinkHints.Honoured)
                    answers(DevControls::createsPlainEvents).with(true)
                    calls(DevHandlers::onReset)
                }
            },
        ) { subject ->
            val reset = assertNotNullReset(subject)
            var resets = 0
            subject.controls.listen(DevHandlers(onReset = { resets++ }))
            assertEquals(PINNED, subject.controls.uploaderPin(), "the pin the channel set")
            assertEquals(InviteLinkHints.Honoured, subject.controls.inviteLinkHints(), "a rig joins headlessly")
            assertTrue(subject.controls.createsPlainEvents(), "the channel switched encryption off")
            reset()
            assertEquals(1, resets, "the channel's reset reaches the composition that listened")
        }
    }

    private fun assertNotNullReset(subject: DevControlsUnderTest) =
        kotlin.test.assertNotNull(subject.resetFromChannel, "a switched build has a channel to reset from")
}
