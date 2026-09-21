package app.snapsync.feature.membership

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

/** Entering a new membership (capabilities `join-event`, `upload-state-reconciliation`): the order is the rule. */
class MembershipEntryTest {

    private fun entry(order: MutableList<String>) = MembershipEntry(
        stopUploads = { order += "stop" },
        notifyLeave = { order += "leave:$it" },
        loadShareSet = { order += "load" },
    )

    @Test
    fun `a switch stops the previous uploads then leaves then loads the new share set`() = runTest {
        val order = mutableListOf<String>()
        entry(order).enter("OLD")
        assertEquals(listOf("stop", "leave:OLD", "load"), order)
    }

    @Test
    fun `a first join has nothing to stop or leave and only loads`() = runTest {
        val order = mutableListOf<String>()
        entry(order).enter(null)
        assertEquals(listOf("load"), order)
    }
}
