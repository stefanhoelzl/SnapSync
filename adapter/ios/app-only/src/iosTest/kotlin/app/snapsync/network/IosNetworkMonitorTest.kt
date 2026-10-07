@file:OptIn(ExperimentalForeignApi::class)

package app.snapsync.network

import app.snapsync.model.NetworkAccess
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import platform.Network.nw_path_status_invalid
import platform.Network.nw_path_status_satisfiable
import platform.Network.nw_path_status_satisfied
import platform.Network.nw_path_status_unsatisfied
import platform.Network.nw_path_unsatisfied_reason_cellular_denied
import platform.Network.nw_path_unsatisfied_reason_local_network_denied
import platform.Network.nw_path_unsatisfied_reason_not_available
import platform.Network.nw_path_unsatisfied_reason_wifi_denied
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The path-to-access mapping, over readings handed in through the adapter's seam. The BLOCKED rows are what no host
 * reaches (`IosNetworkMonitor`'s documentation): this pins what the adapter makes of Apple's documented reasons, not
 * that iOS reports them.
 */
class IosNetworkMonitorTest {

    private fun reading(status: UInt, reason: UInt = nw_path_unsatisfied_reason_not_available) = PathReading(
        status,
        reason,
    )

    @Test
    fun `a satisfied path is online and so is a satisfiable one`() {
        assertEquals(NetworkAccess.Online(restricted = false), networkAccessOf(reading(nw_path_status_satisfied)))
        assertEquals(NetworkAccess.Online(restricted = false), networkAccessOf(reading(nw_path_status_satisfiable)))
    }

    @Test
    fun `an expensive or constrained path is online and restricted`() {
        // Capability `mobile-data`: cellular or a hotspot (expensive), Low Data Mode (constrained), or both.
        val online = nw_path_status_satisfied
        val none = nw_path_unsatisfied_reason_not_available
        assertEquals(
            NetworkAccess.Online(restricted = true),
            networkAccessOf(PathReading(online, none, expensive = true)),
        )
        assertEquals(
            NetworkAccess.Online(restricted = true),
            networkAccessOf(PathReading(online, none, constrained = true)),
        )
        assertEquals(
            NetworkAccess.Online(restricted = true),
            networkAccessOf(PathReading(online, none, expensive = true, constrained = true)),
        )
        assertEquals(NetworkAccess.Online(restricted = false), networkAccessOf(PathReading(online, none)))
    }

    @Test
    fun `a path denied to the app is blocked`() {
        assertEquals(
            NetworkAccess.Blocked,
            networkAccessOf(reading(nw_path_status_unsatisfied, nw_path_unsatisfied_reason_cellular_denied)),
        )
        assertEquals(
            NetworkAccess.Blocked,
            networkAccessOf(reading(nw_path_status_unsatisfied, nw_path_unsatisfied_reason_wifi_denied)),
        )
    }

    @Test
    fun `any other unsatisfied path is offline and so is an invalid one`() {
        assertEquals(NetworkAccess.Offline, networkAccessOf(reading(nw_path_status_unsatisfied)))
        assertEquals(
            NetworkAccess.Offline,
            networkAccessOf(reading(nw_path_status_unsatisfied, nw_path_unsatisfied_reason_local_network_denied)),
        )
        assertEquals(NetworkAccess.Offline, networkAccessOf(reading(nw_path_status_invalid)))
    }

    @Test
    fun `a collection opens on the current path and publishes each change once and then stops its monitor`() = runTest {
        var stopped = 0
        val paths = object : NetworkPathApi {
            override fun start(onPath: (PathReading) -> Unit): () -> Unit {
                onPath(reading(nw_path_status_satisfied))
                onPath(reading(nw_path_status_satisfied))
                onPath(reading(nw_path_status_unsatisfied, nw_path_unsatisfied_reason_cellular_denied))
                return { stopped++ }
            }
        }
        val monitor = IosNetworkMonitor(paths)

        assertEquals(NetworkAccess.Online(restricted = false), monitor.watch().first())
        assertEquals(
            listOf(NetworkAccess.Online(restricted = false), NetworkAccess.Blocked),
            monitor.watch().take(2).toList(),
        )
        assertEquals(2, stopped)
    }
}
