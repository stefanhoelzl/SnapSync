package app.snapsync.feature.membership

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The provision-time switch rule (`switchDecision`, capabilities `event-link` / `join-event`) —
 * drained from the Provision flow's guard at the migration finale: only provisioning a *different*
 * event while joined is a switch (and fires the best-effort backend leave of the previous one); a
 * provision while unjoined is a join; a re-provision of the joined event is neither.
 */
class SwitchDecisionTest {

    @Test
    fun `provisioning a different event while joined leaves the previous one`() {
        assertEquals(SwitchDecision.LeavePrevious("OLD"), switchDecision("OLD", "NEW"))
    }

    @Test
    fun `re-provisioning the same event is not a switch`() {
        assertEquals(SwitchDecision.Stay, switchDecision("SAME", "SAME"))
    }

    @Test
    fun `a first join is a join not a switch and not a stay`() {
        // A join loads the share set; a stay must not (it would reset a live membership's ledger).
        assertEquals(SwitchDecision.Join, switchDecision(null, "NEW"))
    }
}
