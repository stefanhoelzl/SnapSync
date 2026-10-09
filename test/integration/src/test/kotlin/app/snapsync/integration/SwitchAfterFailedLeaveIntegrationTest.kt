package app.snapsync.integration

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * **A join is a switch whenever the device is still joined**: a leave whose membership file the platform would not
 * delete leaves the device joined to the old event, and the next join then switches — it stops the old event's
 * uploads and tells the backend this device left it — instead of entering the new event beside a membership it never
 * ended.
 */
class SwitchAfterFailedLeaveIntegrationTest {

    @Test
    fun a_join_after_a_leave_that_could_not_clear_switches_out_of_the_old_event() = rigTest {
        val next = registerEvent(name = "Anna's Wedding")
        val current = createAndJoin(name = "Summer Trip")
        device("membership/undeletable", "on" to "true")

        // A headless join: the gate leaves the current event first — which cannot clear — then joins.
        openLink(inviteLink(next, autoJoin = true))

        assertEquals(next, awaitState { it.ready.eventId == next }.ready.eventId)
        eventually<Boolean>(read = { departed(current) }) { it }
    }

    private suspend fun Rig.departed(event: String): Boolean =
        deviceJson("backend/departed", "event" to event).getValue("departed").jsonPrimitive.boolean
}
