package app.snapsync.contract

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.BuildInfoContract
import app.snapsync.contracts.BuildInfoState
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.recordingName
import app.snapsync.contracts.replayerFor
import app.snapsync.contracts.verify
import app.snapsync.ports.BuildInfo
import kotlin.test.Test

/**
 * The XS's build facts, REPLAYED (`docs/architecture.md`): the CURRENT `IosBuildInfo` built on the OS fact iOS 18 gave
 * the root when `BuildInfo@IOS_DEVICE_APP.BELOW_IOS_26_1.rec` was recorded. A `Diverged` means the root's fact is now
 * asked otherwise: re-record on the XS (the `rig-channel` runbook).
 */
class BuildInfoReplayContractTest {

    private val belowIos261 = object : Binding<BuildInfoState, BuildInfo> {
        override val host = Host.IOS_DEVICE_APP
        override val kind = BindingKind.Replay
        override val precondition = "BELOW_IOS_26_1"
        override val reaches = setOf(BuildInfoState.NO_OS_DRIVEN_UPLOAD)

        override fun create(state: BuildInfoState, clauseId: String, log: CallLog): Entered<BuildInfo> {
            if (state !in reaches) return Entered.Unreachable("this recording holds an OS below 26.1; $state runs live")
            return replayerFor(RECORDINGS, recordingName(BuildInfoContract.name, host, null, precondition), clauseId) {
                buildInfoOn(replayedBuildFact(it), log, afterDispose = it::assertExhausted)
            }
        }
    }

    @Test
    fun `the recorded build facts below iOS 26_1 satisfy the BuildInfo contract`() =
        verify(BuildInfoContract, belowIos261)
}
