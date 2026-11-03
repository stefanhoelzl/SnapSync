@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package app.snapsync.data.network

import app.snapsync.domain.network.NetworkAccess
import app.snapsync.domain.network.NetworkNotice
import app.snapsync.domain.network.NetworkReturn
import app.snapsync.mock.NetworkMock
import app.snapsync.query.network.NetworkStatusLookup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import app.snapsync.ports.NetworkAccess as PortAccess

/** What the member is told about the network, and the operating system's reading in the domain's words. */
class NetworkStatusTest {

    @Test
    fun `what is told is shown, a shown missing network's return is announced once, and hiding announces none`() =
        runTest {
            val status = NetworkStatus()
            val query = NetworkStatusLookup(status)
            val returns = mutableListOf<Unit>()
            backgroundScope.launch { status.returned.collect { returns += it } }
            runCurrent()

            assertEquals(NetworkNotice.GRACE, status.waitBefore(NetworkAccess.Offline))
            assertEquals(NetworkReturn.UNCHANGED, status.publish(NetworkAccess.Offline))
            assertEquals(NetworkAccess.Offline, query.access.value)

            assertEquals(NetworkReturn.RETURNED, status.publish(NetworkAccess.Online(restricted = false)))
            runCurrent()
            assertEquals(1, returns.size)

            status.publish(NetworkAccess.Blocked)
            status.hide()
            runCurrent()
            assertEquals(NetworkAccess.Online(restricted = false), query.access.value)
            assertEquals(1, returns.size, "hiding is no return")
        }

    @Test
    fun `the readings are the port's, in the domain's words`() = runTest {
        val network = NetworkMock()
        val readings = NetworkReadings(network.port())
        val seen = mutableListOf<NetworkAccess>()
        for (access in listOf(PortAccess.Online(true), PortAccess.Offline, PortAccess.Blocked)) {
            network.access = access
            seen += readings.watch().first()
        }
        assertEquals(listOf(NetworkAccess.Online(true), NetworkAccess.Offline, NetworkAccess.Blocked), seen)
    }
}
