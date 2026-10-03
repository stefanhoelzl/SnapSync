package app.snapsync.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The member's mobile-data choice as the rule each photo transfer carries (capability `mobile-data`). */
class TransferNetworkTest {

    @Test
    fun `the choice maps to the rule`() {
        assertEquals(TransferNetwork.ANY, transferNetworkOf(mobileData = true))
        assertEquals(TransferNetwork.UNRESTRICTED_ONLY, transferNetworkOf(mobileData = false))
    }

    @Test
    fun `an unreadable membership gets the stricter rule`() {
        // Holding a transfer for Wi-Fi only delays it; sending it over mobile data the member refused cannot be undone.
        assertEquals(TransferNetwork.UNRESTRICTED_ONLY, transferNetworkOf(membership = null))
    }

    @Test
    fun `a restricted network is online and tells no network notice`() {
        assertNull(NetworkNotice.of(NetworkAccess.Online(restricted = true)))
        assertNull(NetworkNotice.of(NetworkAccess.Online(restricted = false)))
        assertEquals(NetworkNotice.OFFLINE, NetworkNotice.of(NetworkAccess.Offline))
        assertEquals(NetworkNotice.BLOCKED, NetworkNotice.of(NetworkAccess.Blocked))
    }
}
