package app.snapsync.dev

import app.snapsync.control.Verifies
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A production build's development controls answer the shipped values: no uploader pinned, and every new event plain
 * until encrypted creation is re-enabled.
 */
class InertDevControlsTest {

    @Test
    @Verifies(
        spec = "invite-link",
        requirement = "Every invite form opens in every future version",
        scenario = "A new event's invite opens in every app in use",
    )
    fun `answers the shipped values`() {
        // The shipped build's part: a new event is plain, so its invite is the fragment form every app in use opens. That
        // a plain event's invite is that form, and opens the join screen, is `InviteFormsIntegrationTest`'s.
        assertNull(InertDevControls.uploaderPin(), "no uploader is pinned on a production build")
        assertEquals(
            true,
            InertDevControls.createsPlainEvents(),
            "a shipped build creates plain events, whose invite every app opens",
        )
    }
}
