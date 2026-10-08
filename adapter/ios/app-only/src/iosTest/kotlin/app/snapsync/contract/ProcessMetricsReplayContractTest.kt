package app.snapsync.contract

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.ProcessMetricsContract
import app.snapsync.contracts.ProcessMetricsState
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.replayerFor
import app.snapsync.contracts.verify
import app.snapsync.metrics.MetricKitProcessMetrics
import app.snapsync.ports.ProcessMetrics
import kotlin.test.Test

/**
 * MetricKit's delivery on the device, REPLAYED (`docs/architecture.md`): the CURRENT [MetricKitProcessMetrics] runs
 * against the subscription's answer and the payloads MetricKit handed over when `ProcessMetrics@IOS_DEVICE_APP.rec` was
 * recorded on the SE2, and the current clauses judge. A `Diverged` means the adapter now asks MetricKit something else:
 * re-record (the `rig-channel` runbook).
 */
class ProcessMetricsReplayContractTest {

    private val binding = object : Binding<ProcessMetricsState, ProcessMetrics> {
        override val host = Host.IOS_DEVICE_APP
        override val kind = BindingKind.Replay
        override val reaches = setOf(ProcessMetricsState.PROVIDER_DELIVERS)

        override fun create(state: ProcessMetricsState, clauseId: String, log: CallLog): Entered<ProcessMetrics> {
            if (state !in reaches) return Entered.Unreachable("an iOS app has a provider; $state is another process's")
            return replayerFor(RECORDINGS, RECORDING, clauseId) { replayer ->
                Entered.Ready(
                    MetricKitProcessMetrics(ReplayingMetricKitApi(replayer)).recorded(log),
                    dispose = replayer::assertExhausted,
                )
            }
        }
    }

    @Test
    fun `the recorded delivery satisfies the ProcessMetrics contract`() = verify(ProcessMetricsContract, binding)

    private companion object {
        const val RECORDING = "ProcessMetrics@IOS_DEVICE_APP"
    }
}
