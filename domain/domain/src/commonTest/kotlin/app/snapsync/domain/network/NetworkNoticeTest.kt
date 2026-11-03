package app.snapsync.domain.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration

/** Slow to warn, instant to clear (decision record `changes/tell-when-offline`, D2, D4). */
class NetworkNoticeTest {

    private val online = NetworkNotice(NetworkAccess.Online(restricted = false))
    private val offline = NetworkNotice(NetworkAccess.Offline)

    @Test
    fun `a network going missing is told only after the grace, everything else at once`() {
        assertEquals(NetworkNotice.GRACE, online.waitBefore(NetworkAccess.Offline))
        assertEquals(NetworkNotice.GRACE, online.waitBefore(NetworkAccess.Blocked))
        assertEquals(Duration.ZERO, online.waitBefore(NetworkAccess.Online(restricted = true)))
        // A changed cause while a notice already shows, and the return.
        assertEquals(Duration.ZERO, offline.waitBefore(NetworkAccess.Blocked))
        assertEquals(Duration.ZERO, offline.waitBefore(NetworkAccess.Online(restricted = false)))
        assertEquals(Duration.ZERO, NetworkNotice(NetworkAccess.Blocked).waitBefore(NetworkAccess.Offline))
    }

    @Test
    fun `only a shown missing network coming back is the return`() {
        assertEquals(NetworkReturn.RETURNED, offline.returns(NetworkAccess.Online(restricted = true)))
        assertEquals(NetworkReturn.RETURNED, NetworkNotice(NetworkAccess.Blocked).returns(NetworkAccess.Online(false)))
        assertEquals(NetworkReturn.UNCHANGED, offline.returns(NetworkAccess.Blocked))
        assertEquals(NetworkReturn.UNCHANGED, NetworkNotice(NetworkAccess.Blocked).returns(NetworkAccess.Offline))
        assertEquals(NetworkReturn.UNCHANGED, online.returns(NetworkAccess.Online(restricted = true)))
        assertEquals(NetworkReturn.UNCHANGED, online.returns(NetworkAccess.Offline))
    }

    @Test
    fun `a watch that is not running tells nothing`() {
        assertEquals(NetworkAccess.Online(restricted = false), NetworkNotice.HIDDEN.shown)
    }
}
