package app.snapsync.contracts

import app.snapsync.model.NetworkAccess
import app.snapsync.ports.NetworkMonitor
import kotlinx.coroutines.flow.first
import kotlin.test.assertEquals

/** The states a device's network can be found in, as far as a clause cares. */
enum class NetworkState {
    /** The device has a network this app may use. */
    ONLINE,

    /** The device has no network at all (airplane mode). */
    OFFLINE,

    /** The device has a network the operating system withholds from this app. */
    BLOCKED,
}

/**
 * What the network watch promises (`docs/architecture.md` — this list IS the port's specification): a collection's
 * first value is the access at that moment, told apart by cause.
 *
 * Each clause reads only the FIRST value, because that is the promise a caller decides on: an adapter that opens with a
 * guess and corrects it a moment later would flash the wrong state at a user, and fails here.
 */
object NetworkMonitorContract : Contract<NetworkState, NetworkMonitor>("NetworkMonitor") {

    override val clauses = clauses {

        clause("A_CONNECTED_DEVICE_READS_ONLINE", NetworkState.ONLINE) { monitor ->
            assertEquals(NetworkAccess.ONLINE, monitor.firstReading())
        }

        clause("A_DEVICE_WITHOUT_A_NETWORK_READS_OFFLINE", NetworkState.OFFLINE) { monitor ->
            assertEquals(NetworkAccess.OFFLINE, monitor.firstReading())
        }

        clause("A_NETWORK_WITHHELD_FROM_THE_APP_READS_BLOCKED", NetworkState.BLOCKED) { monitor ->
            assertEquals(NetworkAccess.BLOCKED, monitor.firstReading())
        }
    }

    /** The platform answers on its own queue, so the wait is a real one; silence reads `NotWithin`. */
    private suspend fun NetworkMonitor.firstReading(): NetworkAccess =
        withinRealTime(HANDOFF_ANSWER_MILLIS) { watch().first() }
}
