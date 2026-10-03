@file:OptIn(ExperimentalForeignApi::class)

package app.snapsync.network

import app.snapsync.objc.objcBoundary
import co.touchlab.kermit.Logger
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Network.nw_path_get_status
import platform.Network.nw_path_get_unsatisfied_reason
import platform.Network.nw_path_is_constrained
import platform.Network.nw_path_is_expensive
import platform.Network.nw_path_monitor_cancel
import platform.Network.nw_path_monitor_create
import platform.Network.nw_path_monitor_set_queue
import platform.Network.nw_path_monitor_set_update_handler
import platform.Network.nw_path_monitor_start
import platform.darwin.dispatch_queue_create

/**
 * One path a monitor reported: `nw_path_get_status`, `nw_path_get_unsatisfied_reason`, `nw_path_is_expensive` and
 * `nw_path_is_constrained`, as Network.framework spells them.
 */
internal data class PathReading(val status: UInt, val reason: UInt, val expensive: Boolean = false, val constrained: Boolean = false)

/**
 * **The operating-system boundary of [IosNetworkMonitor]**: the `nw_path_monitor` it runs, reduced to the two values
 * the adapter decides on (`docs/architecture.md`, "Hosts CI cannot reach are recorded at the operating-system boundary
 * and replayed on every build"). It sits below every decision the adapter makes — how a reading maps to an access —
 * so a replay runs the CURRENT mapping against what iOS reported on a device. Same shape as `BackgroundTaskApi`.
 *
 * `internal`: the recording and replaying implementations live in this module's rig-gated source set and its tests.
 */
internal interface NetworkPathApi {
    /**
     * Starts a monitor of the default path. [onPath] receives the path at that moment — Network.framework reports it
     * promptly after the start — and then every change, on a queue of the monitor's own. Answers the monitor's stop.
     */
    fun start(onPath: (PathReading) -> Unit): () -> Unit
}

/** The real `nw_path_monitor`. The only implementation a production build contains. */
internal object SystemNetworkPathApi : NetworkPathApi {
    private val log = Logger.withTag("NetworkPathApi")

    override fun start(onPath: (PathReading) -> Unit): () -> Unit {
        val monitor = nw_path_monitor_create()
        nw_path_monitor_set_queue(monitor, dispatch_queue_create("app.snapsync.network", null))
        nw_path_monitor_set_update_handler(monitor) { path ->
            objcBoundary(log, "pathMonitor.update") {
                onPath(
                    PathReading(
                        nw_path_get_status(path),
                        nw_path_get_unsatisfied_reason(path),
                        expensive = nw_path_is_expensive(path),
                        constrained = nw_path_is_constrained(path),
                    ),
                )
            }
        }
        nw_path_monitor_start(monitor)
        return { nw_path_monitor_cancel(monitor) }
    }
}
