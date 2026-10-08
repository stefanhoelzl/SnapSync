package app.snapsync.contract

import app.snapsync.background.IosBackgroundTime
import app.snapsync.contracts.BackgroundTimeContract
import app.snapsync.contracts.BackgroundTimeState
import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.replayerFor
import app.snapsync.contracts.verify
import app.snapsync.ports.BackgroundTime
import co.touchlab.kermit.Logger
import kotlin.test.Test

/**
 * The device's background time running out, REPLAYED (`docs/architecture.md`): the CURRENT [IosBackgroundTime] runs
 * against what iOS answered — and the expiry it delivered — when `BackgroundTime@IOS_DEVICE_APP.rec` was recorded on the
 * SE2 sent to the home screen, and the current clauses judge. A `Diverged` means the adapter now asks iOS something else:
 * re-record (the `rig-channel` runbook).
 */
class BackgroundTimeReplayContractTest {

    private val binding = object : Binding<BackgroundTimeState, BackgroundTime> {
        override val host = Host.IOS_DEVICE_APP
        override val kind = BindingKind.Replay
        override val reaches = setOf(BackgroundTimeState.TIME_RUNS_OUT)

        override fun create(state: BackgroundTimeState, clauseId: String): Entered<BackgroundTime> {
            if (state !in reaches) {
                return Entered.Unreachable(
                    "this recording holds the app's time running out; $state runs live",
                )
            }
            return replayerFor(RECORDINGS, RECORDING, clauseId) { replayer ->
                Entered.Ready(
                    IosBackgroundTime(Logger.withTag("contract"), ReplayingBackgroundTimeApi(replayer)),
                    dispose = replayer::assertExhausted,
                )
            }
        }
    }

    @Test
    fun `the recorded device's expiry satisfies the BackgroundTime contract`() = verify(BackgroundTimeContract, binding)

    private companion object {
        const val RECORDING = "BackgroundTime@IOS_DEVICE_APP"
    }
}
