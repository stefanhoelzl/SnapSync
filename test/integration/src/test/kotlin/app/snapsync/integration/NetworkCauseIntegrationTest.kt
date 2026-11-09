package app.snapsync.integration

import app.snapsync.control.Verifies
import app.snapsync.control.behaviour.behaviour
import app.snapsync.levers.NetworkState
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.NetworkNotice
import app.snapsync.ui.state.SyncHealth
import kotlin.test.Test
import app.snapsync.domain.network.NetworkNotice.Companion.GRACE as NETWORK_GRACE

/**
 * **A missing network's cause, told as it changes** (decision record `changes/tell-when-offline`, D2, D4): once a
 * notice shows, a change of why the network is missing is told at once — the cause changed, not the presence — and is
 * no return of the network.
 */
class NetworkCauseIntegrationTest {

    @Test
    @Verifies(spec = "sync-status", requirement = "The app says when it cannot reach the network")
    fun a_blocked_network_that_goes_offline_is_told_offline_at_once() = behaviour {
        given {
            joinedEvent()
            inFront()
            step("a blocked network told", act = {
                network(NetworkState.BLOCKED)
                advance(NETWORK_GRACE)
            }, until = { screen<Layer.Joined> { it.health == SyncHealth.NoNetwork(NetworkNotice.BLOCKED) } })
        }

        whenever { network(NetworkState.OFFLINE) }
        then {
            screen<Layer.Joined> { it.health == SyncHealth.NoNetwork(NetworkNotice.OFFLINE) }
            neverScreen<Layer.Joined> { it.health !is SyncHealth.NoNetwork }
        }
    }
}
