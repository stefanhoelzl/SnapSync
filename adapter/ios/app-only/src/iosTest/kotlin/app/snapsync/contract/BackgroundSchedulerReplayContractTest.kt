package app.snapsync.contract

import app.snapsync.contracts.BackgroundSchedulerContract
import app.snapsync.contracts.BackgroundSchedulerState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.Recording
import app.snapsync.contracts.Replayer
import app.snapsync.contracts.ScheduledWakes
import app.snapsync.contracts.verify
import kotlin.test.Test

/**
 * The entitled device's `BGTaskScheduler`, REPLAYED (`docs/architecture.md`): the CURRENT
 * [app.snapsync.ios.urlsession.IosBackgroundScheduler] runs against what iOS answered when
 * `test/contracts/recordings/BackgroundScheduler@IOS_DEVICE_APP.rec` was recorded, and the current clauses judge.
 *
 * A change that asks iOS something else — another request attribute, another order, a call more or fewer — reads
 * `Diverged`: re-record on the device (the `rig-channel` runbook). A recorded answer that violates a clause reads
 * `Failed`, which re-recording does not fix.
 */
class BackgroundSchedulerReplayContractTest {

    private val recording = RECORDINGS[RECORDING]?.let(Recording::parse)

    private val binding = object : Binding<BackgroundSchedulerState, ScheduledWakes> {
        override val host = Host.IOS_DEVICE_APP
        override val kind = BindingKind.Replay
        override val reaches = setOf(BackgroundSchedulerState.EMPTY)

        override fun create(state: BackgroundSchedulerState, clauseId: String): Entered<ScheduledWakes> {
            val tape = recording
                ?: return Entered.Unreachable("no recording $RECORDING.rec — record it on a device over the rig")
            val block = tape.blocks[clauseId]
                ?: return Entered.Unreachable("$RECORDING.rec holds no block for $clauseId — re-record")
            val replayer = Replayer(clauseId, block)
            return schedulerInState(ReplayingBackgroundTaskApi(replayer), afterDispose = replayer::assertExhausted)
        }
    }

    @Test
    fun `the recorded device scheduler satisfies the BackgroundScheduler contract`() =
        verify(BackgroundSchedulerContract, binding)

    private companion object {
        const val RECORDING = "BackgroundScheduler@IOS_DEVICE_APP"
    }
}
