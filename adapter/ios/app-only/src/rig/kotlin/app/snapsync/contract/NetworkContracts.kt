@file:OptIn(ExperimentalForeignApi::class)

package app.snapsync.contract

import app.snapsync.contracts.Binding
import app.snapsync.contracts.BindingKind
import app.snapsync.contracts.CONTRACT_REFUSED
import app.snapsync.contracts.Entered
import app.snapsync.contracts.Host
import app.snapsync.contracts.NetworkMonitorContract
import app.snapsync.contracts.NetworkState
import app.snapsync.contracts.Recorder
import app.snapsync.contracts.Replayer
import app.snapsync.network.IosNetworkMonitor
import app.snapsync.network.NetworkPathApi
import app.snapsync.network.PathReading
import app.snapsync.network.SystemNetworkPathApi
import app.snapsync.ports.NetworkMonitor
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Network.nw_path_status_invalid
import platform.Network.nw_path_status_satisfiable
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_status_unsatisfied
import platform.Network.nw_path_unsatisfied_reason_cellular_denied
import platform.Network.nw_path_unsatisfied_reason_local_network_denied
import platform.Network.nw_path_unsatisfied_reason_not_available
import platform.Network.nw_path_unsatisfied_reason_vpn_inactive
import platform.Network.nw_path_unsatisfied_reason_wifi_denied

/*
 * `nw_path_monitor`'s operating-system boundary as TEXT, and the bindings of `NetworkMonitorContract` on the simulator
 * app (live) and the entitled device (recorded) (`docs/architecture.md`, "Hosts CI cannot reach are recorded at the
 * operating-system boundary and replayed on every build").
 *
 * Compiled into this module's `iosMain` only under `-Psnapsync.rig=true` (where the device records), and into `iosTest`
 * otherwise (where CI replays) — one file, so the recorder and the replayer cannot spell a call differently.
 */

private val STATUSES = mapOf(
    nw_path_status_invalid to "invalid",
    nw_path_status_satisfied to "satisfied",
    nw_path_status_unsatisfied to "unsatisfied",
    nw_path_status_satisfiable to "satisfiable",
)

private val REASONS = mapOf(
    nw_path_unsatisfied_reason_not_available to "notAvailable",
    nw_path_unsatisfied_reason_cellular_denied to "cellularDenied",
    nw_path_unsatisfied_reason_wifi_denied to "wifiDenied",
    nw_path_unsatisfied_reason_local_network_denied to "localNetworkDenied",
    nw_path_unsatisfied_reason_vpn_inactive to "vpnInactive",
)

/** A reading by Network.framework's names; a value no name covers is kept as its number, so nothing is lost. */
private fun PathReading.render(): String =
    "status=${STATUSES[status] ?: status} reason=${REASONS[reason] ?: reason}"

private fun String.parseReading(): PathReading {
    fun value(key: String, names: Map<UInt, String>): UInt {
        val text = substringAfter("$key=").substringBefore(' ')
        return names.entries.firstOrNull { it.value == text }?.key ?: text.toUInt()
    }
    return PathReading(value("status", STATUSES), value("reason", REASONS))
}

/**
 * Passes every call to [real] and records it in the clause block [recorder] has open: the start, answered with the
 * FIRST path the monitor reported — the one a clause reads — and the cancel. Later paths reach the adapter but are not
 * recorded: no clause reads past the first, and a path that changes mid-clause is not a state a clause is entered in.
 */
internal class RecordingNetworkPathApi(private val real: NetworkPathApi, private val recorder: Recorder) : NetworkPathApi {
    override fun start(onPath: (PathReading) -> Unit): () -> Unit {
        var first = true
        val stop = real.start { reading ->
            if (first) {
                first = false
                recorder.record("start()", reading.render())
            }
            onPath(reading)
        }
        return {
            stop()
            recorder.record("cancel()", "done")
        }
    }
}

/** Answers the start with the recorded first path, delivered at once, and expects the cancel — exactly and in order. */
internal class ReplayingNetworkPathApi(private val replayer: Replayer) : NetworkPathApi {
    override fun start(onPath: (PathReading) -> Unit): () -> Unit {
        onPath(replayer.answer("start()").parseReading())
        return { replayer.answer("cancel()") }
    }
}

/**
 * The real `nw_path_monitor` in the simulator app — the one CI host whose process runs Network.framework against a real
 * network. The simulator shares its Mac's network, which no binding can take down, so this host presents only `ONLINE`.
 */
class SimAppNetworkMonitorBinding : Binding<NetworkState, NetworkMonitor> {
    override val host = Host.IOS_SIM_APP
    override val kind = BindingKind.Live
    override val reaches = setOf(NetworkState.ONLINE)

    override fun create(state: NetworkState, clauseId: String): Entered<NetworkMonitor> =
        if (state == NetworkState.ONLINE) {
            Entered.Ready(IosNetworkMonitor())
        } else {
            Entered.Unreachable("the simulator shares its Mac's network, which no binding can take down")
        }
}

/** The device online — Wi-Fi joined — recording every `nw_path_monitor` call and iOS's answer. */
internal class DeviceNetworkOnlineBinding(private val recorder: Recorder) : Binding<NetworkState, NetworkMonitor> {
    override val host = Host.IOS_DEVICE_APP
    override val kind = BindingKind.Live
    override val reaches = setOf(NetworkState.ONLINE)
    override val precondition = "ONLINE"

    override fun create(state: NetworkState, clauseId: String): Entered<NetworkMonitor> =
        recordingIn(state, NetworkState.ONLINE, recorder, clauseId)
}

/** The device in airplane mode, Wi-Fi off too, recording every `nw_path_monitor` call and iOS's answer. */
internal class DeviceNetworkOfflineBinding(private val recorder: Recorder) : Binding<NetworkState, NetworkMonitor> {
    override val host = Host.IOS_DEVICE_APP
    override val kind = BindingKind.Live
    override val reaches = setOf(NetworkState.OFFLINE)
    override val precondition = "OFFLINE"

    override fun create(state: NetworkState, clauseId: String): Entered<NetworkMonitor> =
        recordingIn(state, NetworkState.OFFLINE, recorder, clauseId)
}

/**
 * The recording monitor for a clause in [state], when it is the [held] condition the phone was put in; any other state
 * is unreachable in this run, and opens no block.
 */
private fun recordingIn(state: NetworkState, held: NetworkState, recorder: Recorder, clauseId: String): Entered<NetworkMonitor> {
    if (state != held) return Entered.Unreachable("this run records the phone $held; $state is another run's, or no host's")
    recorder.open(clauseId)
    return Entered.Ready(IosNetworkMonitor(RecordingNetworkPathApi(SystemNetworkPathApi, recorder)))
}

/**
 * Runs the network contract under the condition the person recording states — `?network=online` or
 * `?network=offline` — which they set on the phone first: a binding cannot take a phone offline, and reading the
 * condition through the adapter under test would file whatever it answers as the truth. A clause then fails when the
 * phone was not in the stated condition, and that recording is not committed.
 */
internal fun recordNetwork(params: Map<String, String>): String = when (params["network"]) {
    "online" -> recordAppOnDevice(NetworkMonitorContract, null, "ONLINE") { DeviceNetworkOnlineBinding(it) }
    "offline" -> recordAppOnDevice(NetworkMonitorContract, null, "OFFLINE") { DeviceNetworkOfflineBinding(it) }
    else -> CONTRACT_REFUSED +
        "state the phone's condition: ?network=online (Wi-Fi joined) or ?network=offline (airplane mode, Wi-Fi off).\n"
}
