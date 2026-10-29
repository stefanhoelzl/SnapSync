package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.feature.status.NetworkWatch
import app.snapsync.levers.NetworkState
import app.snapsync.levers.PhotoGrant
import app.snapsync.levers.WorldEvent
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.NetworkNotice
import app.snapsync.ui.state.SyncHealth
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

/**
 * **The app says when it cannot reach the network** (decision record `changes/tell-when-offline`), over the real
 * composed stack, driven through the control protocol.
 *
 * The operator plays both halves of a network going away: what the operating system reports to the app (the `network`
 * service) and that the backend cannot be reached (the backend mock's offline lever) — on a phone they are one event.
 * The watch's grace is the phone's own, on mocked time: a notice is due once the clock has moved past it.
 */
class NetworkIntegrationTest {

    // ── the joined status line ───────────────────────────────────────────────────────────────────────────

    @Test
    @Verifies(spec = "sync-status", requirement = "The app says when it cannot reach the network")
    @Verifies(
        spec = "sync-status",
        requirement = "The joined screen shows one status line in a fixed priority",
        scenario = "A missing network outranks everything else",
    )
    @Verifies(
        spec = "sync-status",
        requirement = "The app says when it cannot reach the network",
        scenario = "Mobile data switched off for SnapSync, away from Wi-Fi",
    )
    fun a_missing_network_outranks_in_sync_and_clears_when_it_returns() = behaviour {
        given {
            joinedEvent()
            inFront()
        }

        // Slow to warn: a missing network is told only once it has lasted the grace — not a moment before.
        whenever { network(NetworkState.BLOCKED) }
        then { neverScreen<Layer.Joined> { it.health is SyncHealth.NoNetwork } }
        whenever { advance(NetworkWatch.DEFAULT_GRACE - 1.milliseconds) }
        then { neverScreen<Layer.Joined> { it.health is SyncHealth.NoNetwork } }
        whenever { advance(1.milliseconds) }
        then { screen<Layer.Joined> { it.health == SyncHealth.NoNetwork(NetworkNotice.BLOCKED) } }

        // Instant to clear.
        whenever { network(NetworkState.ONLINE) }
        then { screen<Layer.Joined> { it.health == SyncHealth.InSync } }
    }

    @Test
    @Verifies(
        spec = "sync-status",
        requirement = "The joined screen shows one status line in a fixed priority",
        scenario = "Missing access outranks a missing network",
    )
    fun missing_access_outranks_a_missing_network() = behaviour {
        given {
            joinedEvent()
            inFront()
            step("access refused", act = { library.permission(PhotoGrant.DENIED) }, until = {
                screen<Layer.Joined> { it.health == SyncHealth.NeedsAccess(canAsk = false) }
            })
        }

        whenever {
            network(NetworkState.OFFLINE)
            advance(NetworkWatch.DEFAULT_GRACE)
        }
        then { neverScreen<Layer.Joined> { it.health != SyncHealth.NeedsAccess(canAsk = false) } }
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
            step("offline", act = { network(NetworkState.OFFLINE, backendReachable = true) }, until = {})
            step("told so", act = { advance(NetworkWatch.DEFAULT_GRACE) }, until = {
                screen<Layer.Joined> { it.health is SyncHealth.NoNetwork }
            })
        }

        // Nothing opens the app again: the network's return is what resumes its work, and the work reaches the backend.
        whenever { network(NetworkState.ONLINE) }
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
            step("offline", act = { network(NetworkState.OFFLINE) }, until = {})
            step("told so", act = { advance(NetworkWatch.DEFAULT_GRACE) }, until = {
                screen<Layer.Joined> { it.health is SyncHealth.NoNetwork }
            })
            step("in the background", act = { os.background() }, until = { settled { true } })
        }

        // Not now, and not later: the app reaches nothing, and leaves no timer that would.
        whenever { network(NetworkState.ONLINE) }
        then {
            never<WorldEvent.BackendReceived>()
            settled { it.nextTimer == null }
        }
    }

    // ── the create screen ────────────────────────────────────────────────────────────────────────────────

    @Test
    @Verifies(spec = "create-event", requirement = "Without a network, Create waits")
    fun the_create_screen_names_the_cause_and_clears_when_the_network_returns() = behaviour {
        given { inFront() }

        whenever {
            network(NetworkState.OFFLINE)
            advance(NetworkWatch.DEFAULT_GRACE)
        }
        then { screen<Layer.CreateEvent> { it.network == NetworkNotice.OFFLINE } }

        whenever { network(NetworkState.BLOCKED) }
        then { screen<Layer.CreateEvent> { it.network == NetworkNotice.BLOCKED } }

        whenever { network(NetworkState.ONLINE) }
        then { screen<Layer.CreateEvent> { it.network == null } }
    }

    // ── the join screen ──────────────────────────────────────────────────────────────────────────────────

    @Test
    @Verifies(
        spec = "join-event",
        requirement = "Without a network, the join screen waits for one",
        scenario = "An invite opened offline loads once the network returns",
    )
    fun an_invite_opened_offline_loads_by_itself_once_the_network_returns() = behaviour {
        lateinit var invite: String
        given {
            invite = inviteFor(registeredEvent())
            inFront()
            step("offline, and told so", act = {
                network(NetworkState.OFFLINE)
                advance(NetworkWatch.DEFAULT_GRACE)
            }, until = { screen<Layer.CreateEvent> { it.network == NetworkNotice.OFFLINE } })
        }

        whenever { openLink(invite) }
        then { screen<Layer.JoiningEvent> { it.phase == JoinPhase.LoadFailed && it.network == NetworkNotice.OFFLINE } }

        whenever { network(NetworkState.ONLINE) }
        then { screen<Layer.JoiningEvent> { it.phase is JoinPhase.Detailed && it.network == null } }
    }
}
