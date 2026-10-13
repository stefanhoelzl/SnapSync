package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.feature.status.NetworkWatch
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.NetworkNotice
import app.snapsync.ui.state.SyncHealth
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * **The app says when it cannot reach the network** (decision record `changes/tell-when-offline`), over the real
 * composed stack, driven through the control protocol.
 *
 * The operator plays both halves of a network going away: what the operating system reports to the app (the network
 * lever) and that the backend cannot be reached (the backend mock's offline lever) — on a phone they are one event.
 * Every wait for a notice is longer than the watch's grace, which is real time on this host — shortened there to a
 * fraction of a second (`JvmRigHost`'s `NETWORK_GRACE_MILLIS`, through the build's development controls), so a wait
 * that a notice would have had time to appear in costs a second, not seven.
 */
class NetworkIntegrationTest {

    private suspend fun Rig.network(access: String, backendReachable: Boolean = access == "online") {
        backend.offline(!backendReachable)
        device("network", "access" to access)
    }

    private suspend fun Rig.awaitCreateNotice(notice: NetworkNotice?): Layer.CreateEvent =
        awaitState(NOTICE) { (it.ui.layer as? Layer.CreateEvent)?.network == notice }.ui.layer as Layer.CreateEvent

    // ── the joined status line ───────────────────────────────────────────────────────────────────────────

    @Test
    @Verifies(spec = "sync-status", requirement = "The app says when it cannot reach the network")
    @Verifies(
        spec = "sync-status",
        requirement = "One status line in a fixed priority",
        scenario = "A missing network outranks everything else",
    )
    fun a_missing_network_outranks_in_sync_and_clears_when_it_returns() = behaviour {
        given {
            joinedEvent()
            inFront()
        }

        // Slow to warn: a missing network is told only once it has lasted the grace — not a moment before.
        whenever { network("blocked") }
        then { neverScreen<Layer.Joined> { it.health is SyncHealth.NoNetwork } }
        whenever { advance(NetworkWatch.DEFAULT_GRACE - 1.milliseconds) }
        then { neverScreen<Layer.Joined> { it.health is SyncHealth.NoNetwork } }
        whenever { advance(1.milliseconds) }
        then { screen<Layer.Joined> { it.health == SyncHealth.NoNetwork(NetworkNotice.BLOCKED) } }

        // Instant to clear.
        whenever { network("online") }
        then { screen<Layer.Joined> { it.health == SyncHealth.InSync } }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "One status line in a fixed priority",
        scenario = "Missing access outranks a missing network",
    )
    fun missing_access_outranks_a_missing_network() = rigTest {
        createAndJoin()
        foreground()
        permission("denied")
        network("offline")
        delay(GRACE_PASSED)
        assertEquals(SyncHealth.NeedsAccess(canAsk = false), state().joined?.health)
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "The app says when it cannot reach the network",
        scenario = "The network returns while the app is open",
    )
    fun the_network_s_return_resumes_the_app_s_work_without_an_opening() = behaviour {
        given {
            joinedEvent()
            inFront()
            // Only this device is offline: the fellow member below still reaches the backend.
            step("offline", act = { network("offline", backendReachable = true) }, until = {})
            step("told so", act = { advance(NetworkWatch.DEFAULT_GRACE) }, until = {
                screen<Layer.Joined> { it.health is SyncHealth.NoNetwork }
            })
        }

        // Nothing opens the app again: the network's return is what resumes its work, and the work reaches the backend.
        whenever { network("online") }
        then { event<WorldEvent.BackendReceived>() }
    }

    // The requirement's own condition — the app resumes its work when the network returns *while it is open* — seen from
    // the other side: in the background, the return resumes nothing.
    @Test
    @Verifies(spec = "sync-status", requirement = "The app says when it cannot reach the network")
    fun a_return_while_backgrounded_resumes_nothing() = behaviour {
        given {
            joinedEvent()
            inFront()
            step("offline", act = { network("offline") }, until = {})
            step("told so", act = { advance(NetworkWatch.DEFAULT_GRACE) }, until = {
                screen<Layer.Joined> { it.health is SyncHealth.NoNetwork }
            })
            step("in the background", act = { os("app", "onBackground") }, until = { settled { true } })
        }

        // Not now, and not later: the app reaches nothing, and leaves no timer that would.
        whenever { network("online") }
        then {
            never<WorldEvent.BackendReceived>()
            settled { it.nextTimer == null }
        }
    }

    // ── the create screen ────────────────────────────────────────────────────────────────────────────────

    @Test
    @Verifies(spec = "create-event", requirement = "Without a network, Create waits")
    fun the_create_screen_names_the_cause_and_clears_when_the_network_returns() = rigTest {
        foreground()
        network("offline")
        awaitCreateNotice(NetworkNotice.OFFLINE)
        network("blocked")
        awaitCreateNotice(NetworkNotice.BLOCKED)
        network("online")
        awaitCreateNotice(null)
    }

    // ── the join screen ──────────────────────────────────────────────────────────────────────────────────

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "Without a network, the join screen waits for one",
        scenario = "An invite opened offline loads once the network returns",
    )
    fun an_invite_opened_offline_loads_by_itself_once_the_network_returns() = rigTest {
        val event = registerEvent()
        foreground()
        network("offline")
        awaitCreateNotice(NetworkNotice.OFFLINE)

        openLink(inviteLink(event))
        val waiting = awaitState {
            (it.ui.layer as? Layer.JoiningEvent)?.phase == JoinPhase.LoadFailed
        }.ui.layer as Layer.JoiningEvent
        assertEquals(NetworkNotice.OFFLINE, waiting.network)

        network("online")
        val loaded = awaitState {
            (it.ui.layer as? Layer.JoiningEvent)?.phase is JoinPhase.Detailed
        }.ui.layer as Layer.JoiningEvent
        assertEquals(null, loaded.network)
    }

    private companion object {
        /** Longer than the watch's grace, with room for a loaded runner. */
        val NOTICE = 20.seconds

        /** A wait that a notice would have had time to appear in: five times the host's grace. */
        val GRACE_PASSED = 1.seconds
    }
}
