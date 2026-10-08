package app.snapsync.contract

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CallLog
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.NetworkMonitorContract
import app.snapsync.contracts.NetworkState
import app.snapsync.contracts.proxy.recorded
import app.snapsync.contracts.recordingName
import app.snapsync.contracts.replayerFor
import app.snapsync.contracts.verify
import app.snapsync.network.IosNetworkMonitor
import app.snapsync.ports.NetworkMonitor
import kotlin.test.Test

/**
 * The device's `nw_path_monitor`, REPLAYED (`docs/architecture.md`): the CURRENT [IosNetworkMonitor] runs against what
 * iOS reported when `NetworkMonitor@IOS_DEVICE_APP.ONLINE.rec` (Wi-Fi joined) and `….OFFLINE.rec` (airplane mode) were
 * recorded on the SE2, and the current clauses judge.
 *
 * One binding per recording, because the network is a precondition a person sets between the two runs, never a state a
 * binding enters. A `Diverged` means the adapter now asks iOS something else: re-record (the `rig-channel` runbook).
 */
class NetworkReplayContractTest {

    private class ReplayBinding(condition: String) {
        val name = recordingName(NetworkMonitorContract.name, Host.IOS_DEVICE_APP, null, condition)

        fun create(clauseId: String, log: CallLog): Entered<NetworkMonitor> =
            replayerFor(RECORDINGS, name, clauseId) { replayer ->
                Entered.Ready(
                    IosNetworkMonitor(ReplayingNetworkPathApi(replayer)).recorded(log),
                    dispose = replayer::assertExhausted,
                )
            }
    }

    private val online = object : Binding<NetworkState, NetworkMonitor> {
        override val host = Host.IOS_DEVICE_APP
        override val kind = BindingKind.Replay
        override val reaches = setOf(NetworkState.ONLINE)
        override val precondition = "ONLINE"
        private val replay = ReplayBinding(precondition)

        override fun create(state: NetworkState, clauseId: String, log: CallLog) = replay.create(clauseId, log)
    }

    private val offline = object : Binding<NetworkState, NetworkMonitor> {
        override val host = Host.IOS_DEVICE_APP
        override val kind = BindingKind.Replay
        override val reaches = setOf(NetworkState.OFFLINE)
        override val precondition = "OFFLINE"
        private val replay = ReplayBinding(precondition)

        override fun create(state: NetworkState, clauseId: String, log: CallLog) = replay.create(clauseId, log)
    }

    private val restricted = object : Binding<NetworkState, NetworkMonitor> {
        override val host = Host.IOS_DEVICE_APP
        override val kind = BindingKind.Replay
        override val reaches = setOf(NetworkState.RESTRICTED)
        override val precondition = "RESTRICTED"
        private val replay = ReplayBinding(precondition)

        override fun create(state: NetworkState, clauseId: String, log: CallLog) = replay.create(clauseId, log)
    }

    @Test
    fun `the recorded restricted device satisfies the NetworkMonitor contract`() = verify(
        NetworkMonitorContract,
        restricted,
    )

    @Test
    fun `the recorded online device satisfies the NetworkMonitor contract`() = verify(NetworkMonitorContract, online)

    @Test
    fun `the recorded offline device satisfies the NetworkMonitor contract`() = verify(NetworkMonitorContract, offline)
}
