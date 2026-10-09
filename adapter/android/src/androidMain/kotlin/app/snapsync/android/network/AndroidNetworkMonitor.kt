package app.snapsync.android.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import app.snapsync.model.NetworkAccess
import app.snapsync.ports.NetworkMonitor
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

/**
 * The Android [NetworkMonitor]: the app's DEFAULT network, as `ConnectivityManager`'s default-network callback reports
 * it, and whether the platform blocks it for this app.
 *
 * Two platform facts shape it:
 *  - A default network arrives as `onAvailable` followed by `onBlockedStatusChanged`, on registration as on every
 *    change of network — so a reading is published at the BLOCKED STATUS, never at `onAvailable`, which would open a
 *    blocked network with a moment of [NetworkAccess.Online].
 *  - With no network at all, nothing calls back. Absence is therefore read once at registration, from the active
 *    network's info: `activeNetwork` alone cannot tell absence from a block (it answers `null` for both), while the
 *    info of a blocked network is present and reads `BLOCKED`. Deprecated, but the one synchronous read that tells
 *    them apart.
 *
 * Both measured on the API 36 emulator, 2026-10-03 (`NetworkMonitorContract`'s BLOCKED clause fails without either):
 * with this package denied by the `OEM_DENY_3` firewall chain, `activeNetwork` is `null`, the info reads `BLOCKED`,
 * and the callback still delivers the network — `onAvailable` first, then `onBlockedStatusChanged(true)`; in airplane
 * mode `activeNetwork` and the info are both `null` and nothing calls back.
 *
 * Restricted is a METERED default network: cellular, a hotspot, a Wi-Fi the person marked
 * metered. Data Saver needs no reading of its own — it applies to metered networks only, where the network already
 * reads restricted, and in the background it shows up as a block (measured on the API 36 emulator, 2026-10-03: Data
 * Saver on unmetered Wi-Fi held nothing). Capabilities arrive between `onAvailable` and `onBlockedStatusChanged`, so
 * the first reading already carries them; a later change — a metered override lifted in place — publishes again.
 *
 * A block is anything the platform withholds the network for: Data Saver or a background restriction while the app is
 * in the background, a firewall chain an OEM's per-app switch sets. Each collection registers its own callback and
 * unregisters it on cancellation.
 */
class AndroidNetworkMonitor(context: Context) : NetworkMonitor {
    private val connectivity: ConnectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun watch(): Flow<NetworkAccess> = callbackFlow {
        val reading = DefaultNetworkReading()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = reading.available(network)

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                reading.capabilities(network, capabilities.isMetered())?.let { trySend(it) }
            }

            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                reading.blocked(network, blocked)?.let { trySend(it) }
            }

            override fun onLost(network: Network) {
                reading.lost(network)?.let { trySend(it) }
            }
        }
        connectivity.registerDefaultNetworkCallback(callback)
        @Suppress("DEPRECATION")
        if (connectivity.activeNetworkInfo == null) reading.absent()?.let { trySend(it) }
        awaitClose { connectivity.unregisterNetworkCallback(callback) }
    }.distinctUntilChanged()
}

/**
 * One collection's view of the default network, fed from the platform's callback thread and the collector's: each
 * input answers the access to publish, or `null` when there is nothing new to say.
 */
internal class DefaultNetworkReading {
    private var network: Network? = null
    private var published = false
    private var metered = false

    /** The current network's block status once the platform has told it; until then nothing is published for it. */
    private var blocked: Boolean? = null

    @Synchronized
    fun available(network: Network) {
        this.network = network
        metered = false
        blocked = null
    }

    @Synchronized
    fun capabilities(network: Network, metered: Boolean): NetworkAccess? {
        if (network != this.network) return null
        this.metered = metered
        // Before the block status the network is not yet published: the status reading carries this value.
        return if (blocked == false) NetworkAccess.Online(restricted = metered).also { published = true } else null
    }

    @Synchronized
    fun blocked(network: Network, blocked: Boolean): NetworkAccess? {
        if (network != this.network) return null
        this.blocked = blocked
        published = true
        return if (blocked) NetworkAccess.Blocked else NetworkAccess.Online(restricted = metered)
    }

    @Synchronized
    fun lost(network: Network): NetworkAccess? {
        if (network != this.network) return null
        this.network = null
        blocked = null
        published = true
        return NetworkAccess.Offline
    }

    /** No network at registration — unless the callback has already told a newer truth. */
    @Synchronized
    fun absent(): NetworkAccess? = if (published || network != null) {
        null
    } else {
        NetworkAccess.Offline.also {
            published = true
        }
    }
}

/** Metered as the platform's own transfer scheduling reads it: neither permanently nor temporarily unmetered. */
private fun NetworkCapabilities.isMetered(): Boolean =
    !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
        !hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)

/**
 * Suspends until the phone's default network is unmetered and not withheld from this app — what an upload held to
 * unrestricted networks waits for before it sends a byte.
 */
fun awaitUnrestrictedNetwork(context: Context): suspend () -> Unit {
    val monitor = AndroidNetworkMonitor(context)
    return { monitor.watch().first { it == NetworkAccess.Online(restricted = false) } }
}
