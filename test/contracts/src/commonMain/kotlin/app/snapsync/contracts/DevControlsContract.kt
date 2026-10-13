@file:OptIn(CompositionOnly::class)

package app.snapsync.contracts

import app.snapsync.inbound.Completion
import app.snapsync.inbound.CompositionOnly
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
     * A rig build whose controls the channel switched away from a shipped build's: the app's uploader pinned off, invite
     * hints honoured, new events created encrypted (a shipped build creates plain ones until encrypted creation is
     * re-enabled, `PENDING_CLEANUPS.md`), the network grace shortened — and a reset the channel can deliver ([DevControlsUnderTest.resetFromChannel]).
     */
    SWITCHED,
}

/** The port, and — on a build that has one — the control channel's reset. */
class DevControlsUnderTest(val controls: DevControls, val resetFromChannel: (suspend () -> Unit)? = null)

/**
 * What the build's developer controls promise the composition (`docs/architecture.md`): a shipped build answers every
 * control as shipped — no pin, hints ignored, events plain — and a rig build answers what its channel set, and
 * delivers the channel's reset to the composition that listened.
 * A switched build may also shorten the network watch's grace, which a shipped build never does.
 */
object DevControlsContract : Contract<DevControlsState, DevControlsUnderTest>("DevControls") {

    /** The pin [DevControlsState.SWITCHED] carries: the app's uploader off, the extension on. */
    val PINNED = UploaderPin(app = false, extension = true)

    /** The network grace [DevControlsState.SWITCHED] carries, in milliseconds: a test host's short one. */
    const val SHORT_GRACE_MILLIS = 200L

    override val clauses = clauses {

        clause(
            "SHIPPED_EVERY_CONTROL_IS_AS_SHIPPED",
            DevControlsState.SHIPPED,
            covers = cells {
                on<DevControls> {
                    answers(DevControls::inbound).returns()
                    answers(DevControls::uploaderPin).with(null)
                    answers(DevControls::inviteLinkHints).with(InviteLinkHints.Ignored)
                    answers(DevControls::createsPlainEvents).with(true)
                    answers(DevControls::networkGraceMillis).with(null)
                }
            },
        ) { subject ->
            subject.controls.inbound(
                DevHandlers(onReset = backgroundOf { _, _ -> error("a shipped build resets nothing") }),
            )
            assertNull(subject.controls.uploaderPin(), "both uploaders, as a shipped build runs them")
            assertEquals(InviteLinkHints.Ignored, subject.controls.inviteLinkHints(), "a link is an ordinary invite")
            assertTrue(subject.controls.createsPlainEvents(), "every event is created plain")
            assertNull(subject.controls.networkGraceMillis(), "a missing network waits the app's own grace")
        }

        clause(
            "SWITCHED_EVERY_CONTROL_ANSWERS_THE_CHANNEL_AND_ITS_RESET_ARRIVES",
            DevControlsState.SWITCHED,
            covers = cells {
                on<DevControls> {
                    answers(DevControls::inbound).returns()
                    answers(DevControls::uploaderPin).returns()
                    answers(DevControls::inviteLinkHints).with(InviteLinkHints.Honoured)
                    answers(DevControls::createsPlainEvents).with(false)
                    answers(DevControls::networkGraceMillis).returns()
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
            assertEquals(InviteLinkHints.Honoured, subject.controls.inviteLinkHints(), "a rig joins headlessly")
            assertFalse(subject.controls.createsPlainEvents(), "the channel switched encryption on")
            assertEquals(SHORT_GRACE_MILLIS, subject.controls.networkGraceMillis(), "the grace the host shortened")
            reset()
            assertEquals(1, resets, "the channel's reset reaches the composition that listened")
        }
    }

    private fun assertNotNullReset(subject: DevControlsUnderTest) =
        kotlin.test.assertNotNull(subject.resetFromChannel, "a switched build has a channel to reset from")
}
