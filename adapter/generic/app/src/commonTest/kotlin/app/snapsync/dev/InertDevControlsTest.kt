package app.snapsync.dev

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * A production build's development controls answer the shipped values: no uploader pinned, and every new event plain
 * until encrypted creation is re-enabled.
 */
class InertDevControlsTest {

    @Test
    fun `answers the shipped values`() {
        assertNull(InertDevControls.uploaderPin(), "no uploader is pinned on a production build")
        assertEquals(
            true,
            InertDevControls.createsPlainEvents(),
            "a shipped build creates plain events, whose invite every app opens",
        )
    }
}
