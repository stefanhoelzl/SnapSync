package app.snapsync.feature.membership

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The provision-time switch rule (`switchDecision`, capabilities `event-link` / `join-event`) —
 * drained from the Provision flow's guard at the migration finale: only provisioning a *different*
 * event while joined is a switch (and fires the best-effort backend leave of the previous one).
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
    fun `a first join is not a switch`() {
        assertEquals(SwitchDecision.Stay, switchDecision(null, "NEW"))
    }
}
