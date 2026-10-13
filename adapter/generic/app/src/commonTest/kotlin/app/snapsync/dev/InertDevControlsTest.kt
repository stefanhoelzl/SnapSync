@file:OptIn(CompositionOnly::class, InboundImplementation::class)

package app.snapsync.dev

import app.snapsync.inbound.Background
import app.snapsync.inbound.Completion
import app.snapsync.inbound.CompositionOnly
import app.snapsync.inbound.InboundImplementation
import app.snapsync.model.InviteLinkHints
import app.snapsync.ports.DevHandlers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A production build's development controls answer the shipped values, whatever is registered on them: no uploader
 * pinned, invite-link hints ignored, and every new event plain until encrypted creation is re-enabled — so no crafted
 * link can join without the member confirming (capability `join-event`, "Joining happens only on confirmation").
 */
class InertDevControlsTest {

    @Test
    fun `answers the shipped values and never delivers`() {
        var resets = 0
        InertDevControls.inbound(
            DevHandlers(
                onReset = object : Background<Unit> {
                    override fun handle(arg: Unit, completion: Completion) {
                        resets++
                    }
                },
            ),
        )
        assertNull(InertDevControls.uploaderPin(), "no uploader is pinned on a production build")
        assertEquals(InviteLinkHints.Ignored, InertDevControls.inviteLinkHints(), "no link authorizes its own join")
        assertEquals(
            true,
            InertDevControls.createsPlainEvents(),
            "a shipped build creates plain events, whose invite every app opens",
        )
        assertNull(InertDevControls.networkGraceMillis(), "a shipped build waits the watch's own grace")
        assertEquals(0, resets, "registering runs nothing, and nothing ever delivers")
    }
}
