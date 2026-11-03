package app.snapsync.contracts

import app.snapsync.ports.DevControls
import app.snapsync.ports.UploaderPin
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which developer controls the build carries, and how they are set when a clause starts. */
enum class DevControlsState {
    /** A shipped build: no control is switched. */
    SHIPPED,

    /**
     * A rig build whose controls the channel switched away from a shipped build's: the app's uploader pinned off, and
     * new events created encrypted (a shipped build creates plain ones until encrypted creation is re-enabled,
     * `PENDING_CLEANUPS.md`).
     */
    SWITCHED,
}

/** The port under test. */
class DevControlsUnderTest(val controls: DevControls)

/**
 * What the build's developer controls promise the composition (`docs/architecture.md`): a shipped build answers every
 * control as shipped — no pin, events plain — and a rig build answers what its channel set.
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
                    answers(DevControls::uploaderPin).with(null)
                    answers(DevControls::createsPlainEvents).with(true)
                }
            },
        ) { subject ->
            assertNull(subject.controls.uploaderPin(), "both uploaders, as a shipped build runs them")
            assertTrue(subject.controls.createsPlainEvents(), "every event is created plain")
        }

        clause(
            "SWITCHED_EVERY_CONTROL_ANSWERS_THE_CHANNEL",
            DevControlsState.SWITCHED,
            covers = cells {
                on<DevControls> {
                    answers(DevControls::uploaderPin).returns()
                    answers(DevControls::createsPlainEvents).with(false)
                }
            },
        ) { subject ->
            assertEquals(PINNED, subject.controls.uploaderPin(), "the pin the channel set")
            assertFalse(subject.controls.createsPlainEvents(), "the channel switched encryption on")
        }
    }
}
