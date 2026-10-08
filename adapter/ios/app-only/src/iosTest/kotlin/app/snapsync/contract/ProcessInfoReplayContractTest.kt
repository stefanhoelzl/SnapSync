package app.snapsync.contract

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.ProcessInfoContract
import app.snapsync.contracts.ProcessInfoState
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.recordingName
import app.snapsync.contracts.replayerFor
import app.snapsync.contracts.verify
import app.snapsync.ports.ProcessInfo
import app.snapsync.protection.IosProcessInfo
import kotlin.test.Test

/**
 * The device's protected-data read with its screen locked, REPLAYED (`docs/architecture.md`): the CURRENT
 * [IosProcessInfo] runs against what iOS answered when `ProcessInfo@IOS_DEVICE_APP.LOCKED.rec` was recorded on the SE2,
 * and the current clauses judge. A `Diverged` means the adapter now asks iOS something else: re-record (the
 * `rig-channel` runbook).
 */
class ProcessInfoReplayContractTest {

    private val locked = object : Binding<ProcessInfoState, ProcessInfo> {
        override val host = Host.IOS_DEVICE_APP
        override val kind = BindingKind.Replay
        override val reaches = setOf(ProcessInfoState.LOCKED)
        override val precondition = "LOCKED"

        override fun create(state: ProcessInfoState, clauseId: String, log: CallLog): Entered<ProcessInfo> {
            if (state !in reaches) {
                return Entered.Unreachable(
                    "this recording holds the phone locked; $state runs live elsewhere",
                )
            }
            val name = recordingName(ProcessInfoContract.name, host, null, precondition)
            return replayerFor(RECORDINGS, name, clauseId) { replayer ->
                Entered.Ready(
                    IosProcessInfo(ReplayingProtectedDataApi(replayer)).recorded(log),
                    dispose = replayer::assertExhausted,
                )
            }
        }
    }

    @Test
    fun `the recorded locked device satisfies the ProcessInfo contract`() = verify(ProcessInfoContract, locked)
}
