@file:OptIn(CompositionOnly::class)

package app.snapsync.contracts

import app.snapsync.inbound.Completion
import app.snapsync.inbound.CompositionOnly
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
     * A rig build whose controls the channel switched away from a shipped build's: the app's uploader pinned off, new
     * events created encrypted (a shipped build creates plain ones until encrypted creation is re-enabled,
     * `PENDING_CLEANUPS.md`) — and a reset the channel can deliver ([DevControlsUnderTest.resetFromChannel]).
     */
    SWITCHED,
}

/** The port, and — on a build that has one — the control channel's reset. */
class DevControlsUnderTest(val controls: DevControls, val resetFromChannel: (suspend () -> Unit)? = null)

/**
 * What the build's developer controls promise the composition (`docs/architecture.md`): a shipped build answers every
 * control as shipped — no pin, events plain — and a rig build answers what its channel set, and
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
                    answers(DevControls::inbound).returns()
                    answers(DevControls::uploaderPin).with(null)
                    answers(DevControls::createsPlainEvents).with(true)
                }
            },
        ) { subject ->
            subject.controls.inbound(
                DevHandlers(onReset = backgroundOf { _, _ -> error("a shipped build resets nothing") }),
            )
            assertNull(subject.controls.uploaderPin(), "both uploaders, as a shipped build runs them")
            assertTrue(subject.controls.createsPlainEvents(), "every event is created plain")
        }

        clause(
            "SWITCHED_EVERY_CONTROL_ANSWERS_THE_CHANNEL_AND_ITS_RESET_ARRIVES",
            DevControlsState.SWITCHED,
            covers = cells {
                on<DevControls> {
                    answers(DevControls::inbound).returns()
                    answers(DevControls::uploaderPin).returns()
                    answers(DevControls::createsPlainEvents).with(false)
                    calls(DevHandlers::onReset, Completion::class)
                    handle<Completion>().answers(Completion::complete).returns()
                }
            },
        ) { subject ->
            val reset = assertNotNullReset(subject)
            var resets = 0
            subject.controls.inbound(
                DevHandlers(
                    onReset = backgroundOf { _, done ->
                        resets++
                        done.complete()
                    },
                ),
            )
            assertEquals(PINNED, subject.controls.uploaderPin(), "the pin the channel set")
            assertFalse(subject.controls.createsPlainEvents(), "the channel switched encryption on")
            reset()
            assertEquals(1, resets, "the channel's reset reaches the composition that listened")
        }
    }

    private fun assertNotNullReset(subject: DevControlsUnderTest) =
        kotlin.test.assertNotNull(subject.resetFromChannel, "a switched build has a channel to reset from")
}
