package app.snapsync.dev

import app.snapsync.model.InviteLinkHints
import app.snapsync.ports.DevHandlers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A production build's development controls answer the shipped values, whatever is registered on them: no uploader
 * pinned, and invite-link hints ignored — so no crafted link can join without the member confirming (capability
 * `join-event`, "Joining happens only on confirmation").
 */
class InertDevControlsTest {

    @Test
    fun `answers the shipped values and never delivers`() {
        var resets = 0
        InertDevControls.listen(DevHandlers(onReset = { resets++ }))
        assertNull(InertDevControls.uploaderPin(), "no uploader is pinned on a production build")
        assertEquals(InviteLinkHints.Ignored, InertDevControls.inviteLinkHints(), "no link authorizes its own join")
        assertEquals(0, resets, "registering runs nothing, and nothing ever delivers")
    }
}
