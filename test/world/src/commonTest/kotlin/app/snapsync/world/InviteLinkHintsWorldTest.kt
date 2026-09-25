package app.snapsync.world

import app.snapsync.model.EventLinkPayload
import app.snapsync.model.InviteLinkHints
import app.snapsync.model.encodeEventUrl
import app.snapsync.model.Layer
import app.snapsync.model.UiState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Capability `join-event`, "Joining happens only on confirmation": no invite link, however it is crafted, joins,
 * switches or starts sharing without the member confirming on the join screen.
 *
 * Driven through the status host the SHARED host composition assembles (`snapSyncHost`, the one the iOS root
 * calls) over a world composed with the production root's answer — [InviteLinkHints.Ignored], the world's
 * default. The positive control composes [InviteLinkHints.Honoured], which only a rig build's root supplies, so
 * the negative cases cannot pass by the link simply failing to decode or load.
 */
class InviteLinkHintsWorldTest {

    /** Every hint the decoder accepts, set to its most damaging value. */
    private fun craftedLink(eventId: String) = encodeEventUrl(
        EventLinkPayload(
            eventId, autoJoin = true, minPhotoDate = "2001-01-01T00:00:00Z",
            maxPhotoDate = "2099-01-01T00:00:00Z", direction = "upload", saveToAlbum = true,
        ),
    )

    private suspend fun World.awaitLayer(predicate: (Layer) -> Boolean): UiState =
        withTimeout(TIMEOUT_MS) { statusHost.container.stateFlow.first { predicate(it.layer) } }

    @Test
    fun a_production_composed_host_shows_the_join_screen_for_an_autoJoin_link_and_joins_nothing() = worldTest {
        val w = World(this)
        w.store.registerEvent(INVITED_EVENT, "Anna's Wedding")

        w.statusHost.onOpenUrl(craftedLink(INVITED_EVENT)).join()

        // The ordinary confirmation — the member decides.
        val joining = w.awaitLayer { it is Layer.JoiningEvent }.layer as Layer.JoiningEvent
        assertEquals(INVITED_EVENT, joining.eventId)
        // Nothing joined: no membership on the device, none enrolled on the backend.
        assertNull(w.configSource.config.value)
        assertNull(w.store.manifestOf(INVITED_EVENT, w.ownDeviceId), "no enrollment without a tap")
    }

    @Test
    fun a_production_composed_host_never_lets_an_autoJoin_link_leave_the_current_event() = worldTest {
        val w = World(this)
        w.provision(JOINED_EVENT)
        w.store.registerEvent(INVITED_EVENT, "Anna's Wedding")

        w.statusHost.onOpenUrl(craftedLink(INVITED_EVENT)).join()

        // The switch confirmation over the still-standing membership, exactly as for an unhinted invite.
        val joined = w.awaitLayer { (it as? Layer.Joined)?.pendingSwitch != null }.layer as Layer.Joined
        assertEquals(INVITED_EVENT, joined.pendingSwitch?.eventId)
        assertEquals(JOINED_EVENT, w.configSource.config.value?.eventId, "the current event was not left")
        assertNull(w.store.manifestOf(INVITED_EVENT, w.ownDeviceId), "no enrollment without a tap")
    }

    @Test
    fun a_rig_composed_host_auto_confirms_the_same_link() = worldTest {
        // Attesting, as the control channel's JVM host composes over the mini-edge.
        val w = World(this, attests = true, inviteLinkHints = InviteLinkHints.Honoured)
        w.store.registerEvent(INVITED_EVENT, "Anna's Wedding")

        w.statusHost.onOpenUrl(craftedLink(INVITED_EVENT)).join()

        val config = withTimeout(TIMEOUT_MS) { w.configSource.config.first { it?.eventId == INVITED_EVENT } }
        assertNotNull(config)
        assertNotNull(w.store.manifestOf(INVITED_EVENT, w.ownDeviceId), "enrolled without a tap — rig only")
    }

    private companion object {
        const val INVITED_EVENT = "11111111-1111-4111-8111-111111111111"
        const val JOINED_EVENT = "22222222-2222-4222-8222-222222222222"
        const val TIMEOUT_MS = 10_000L
    }
}
