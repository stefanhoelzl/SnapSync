package app.snapsync.contract

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.ScheduledWakes
import app.snapsync.contracts.WakeContract
import app.snapsync.contracts.WakeState
import app.snapsync.contracts.replayerFor
import app.snapsync.contracts.verify
import kotlin.test.Test

/**
 * The entitled device's `BGTaskScheduler`, REPLAYED (`docs/architecture.md`): the CURRENT
 * [app.snapsync.background.IosWake] runs against what iOS answered when
 * `test/contracts/recordings/BackgroundScheduler@IOS_DEVICE_APP.rec` was recorded, and the current clauses judge.
 *
 * A change that asks iOS something else — another request attribute, another order, a call more or fewer — reads
 * `Diverged`: re-record on the device (the `rig-channel` runbook). A recorded answer that violates a clause reads
 * `Failed`, which re-recording does not fix.
 */
class WakeReplayContractTest {

    private val binding = object : Binding<WakeState, ScheduledWakes> {
        override val host = Host.IOS_DEVICE_APP
        override val kind = BindingKind.Replay
        override val reaches = setOf(WakeState.EMPTY)

        override fun create(state: WakeState, clauseId: String): Entered<ScheduledWakes> {
            return replayerFor(RECORDINGS, RECORDING, clauseId) { replayer ->
                schedulerInState(ReplayingBackgroundTaskApi(replayer), afterDispose = replayer::assertExhausted)
            }
        }
    }

    @Test
    fun `the recorded device scheduler satisfies the Wake contract`() =
        verify(WakeContract, binding)

    private companion object {
        const val RECORDING = "BackgroundScheduler@IOS_DEVICE_APP"
    }
}
