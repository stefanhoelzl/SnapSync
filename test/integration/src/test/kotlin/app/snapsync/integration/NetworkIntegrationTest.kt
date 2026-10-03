package app.snapsync.integration

import app.snapsync.model.GalleryAccess
import app.snapsync.model.JoinPhase
import app.snapsync.model.Layer
import app.snapsync.model.NetworkNotice
import app.snapsync.model.SyncHealth
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.seconds

/**
 * **The app says when it cannot reach the network** (capabilities `sync-status`, `create-event`, `join-event`; decision
 * record `changes/tell-when-offline`), over the real composed stack, driven through the control protocol.
 *
 * The operator plays both halves of a network going away: what the operating system reports to the app (the network
 * lever) and that the backend cannot be reached (the backend mock's offline lever) — on a phone they are one event.
 * Every wait for a notice is longer than the watch's grace, which is real time on this host.
 */
class NetworkIntegrationTest {

    private suspend fun Rig.network(access: String, backendReachable: Boolean = access == "online") {
        device("backend/offline", "on" to (!backendReachable).toString())
        device("network", "access" to access)
    }

    private suspend fun Rig.awaitCreateNotice(notice: NetworkNotice?): Layer.CreateEvent =
        awaitState(NOTICE) { (it.ui.layer as? Layer.CreateEvent)?.network == notice }.ui.layer as Layer.CreateEvent

    // ── the joined status line (capability `sync-status`) ────────────────────────────────────

    @Test
    fun a_missing_network_outranks_in_sync_and_clears_when_it_returns() = rigTest {
        createAndJoin()
        foreground()
        awaitInSync()
        network("blocked")
        assertEquals(SyncHealth.NoNetwork(NetworkNotice.BLOCKED), awaitHealth(NOTICE) { it is SyncHealth.NoNetwork })
        network("online")
        awaitInSync()
    }

    @Test
    fun missing_access_outranks_a_missing_network() = rigTest {
        createAndJoin()
        foreground()
        permission("denied")
        network("offline")
        delay(GRACE_PASSED)
        assertEquals(SyncHealth.NeedsAccess(GalleryAccess.DENIED), state().joined?.health)
    }

    @Test
    fun the_network_s_return_resumes_the_app_s_work_without_an_opening() = rigTest {
        createAndJoin()
        foreground()
        awaitInSync()
        // Only this device is offline: the fellow member below still reaches the backend.
        network("offline", backendReachable = true)
        awaitHealth(NOTICE) { it is SyncHealth.NoNetwork }

        // A fellow member shares while this device is offline; nothing here opens the app again.
        foreignDevice("DEV-F", "FA")
        network("online")

        // The download is planned by the work the network's return runs — the screen shows it arriving.
        assertIs<SyncHealth.Syncing>(awaitHealth { it is SyncHealth.Syncing })
        eventually(read = { client.logs() }) { logs: String -> "onNetworkReturned" in logs }
    }

    @Test
    fun a_return_while_backgrounded_resumes_nothing() = rigTest {
        createAndJoin()
        foreground()
        network("offline")
        awaitHealth(NOTICE) { it is SyncHealth.NoNetwork }
        os("app", "onBackground")
        network("online")
        delay(GRACE_PASSED)
        assertFalse("onNetworkReturned" in client.logs(), "a backgrounded app resumed work on the network's return")
    }

    // ── the create screen (capability `create-event`) ────────────────────────────────────────

    @Test
    fun the_create_screen_names_the_cause_and_clears_when_the_network_returns() = rigTest {
        foreground()
        network("offline")
        awaitCreateNotice(NetworkNotice.OFFLINE)
        network("blocked")
        awaitCreateNotice(NetworkNotice.BLOCKED)
        network("online")
        awaitCreateNotice(null)
    }

    // ── the join screen (capability `join-event`) ────────────────────────────────────────────

    @Test
    fun an_invite_opened_offline_loads_by_itself_once_the_network_returns() = rigTest {
        val event = registerEvent()
        foreground()
        network("offline")
        awaitCreateNotice(NetworkNotice.OFFLINE)

        openLink(inviteLink(event))
        val waiting = awaitState { (it.ui.layer as? Layer.JoiningEvent)?.phase == JoinPhase.LoadFailed }.ui.layer as Layer.JoiningEvent
        assertEquals(NetworkNotice.OFFLINE, waiting.network)

        network("online")
        val loaded = awaitState { (it.ui.layer as? Layer.JoiningEvent)?.phase is JoinPhase.Detailed }.ui.layer as Layer.JoiningEvent
        assertEquals(null, loaded.network)
    }

    private companion object {
        /** Longer than the watch's grace, with room for a loaded runner. */
        val NOTICE = 20.seconds

        /** A wait that a notice would have had time to appear in. */
        val GRACE_PASSED = 7.seconds
    }
}
