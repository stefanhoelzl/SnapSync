package app.snapsync.feature.membership

import app.snapsync.model.JoinLoad
import app.snapsync.model.deletesAt
import app.snapsync.model.eventEnd
import app.snapsync.model.eventStart
import app.snapsync.ports.EventDetails
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The `EventDetails` → [JoinLoad] mapping. Three arms, and the one that matters is the pair it must keep
 * apart: **`NotFound` (definitively gone) and `Failed` (could not tell) are different answers**, and that
 * difference is the only thing separating a real deletion from a transient fault.
 *
 * Collapsing them is not a cosmetic loss. `MembershipRefresh` performs the one destructive consequence in
 * the system on a CONFIRMED absence — it tears the membership down and returns the device to the unjoined
 * resting state — so a mapping that answered `NotFound` for a network blip would destroy a healthy
 * membership on a bad signal, and the member would find themselves silently ejected from the event.
 *
 * This mapping lives in `feature/membership` rather than the app shell precisely because it IS a decision
 * (`module-architecture`, "Shells are wiring only"), which is what makes it testable at all — but until
 * now nothing tested it, in a mapping reached from three composition roots.
 */
class JoinDetailsTest {

    @Test
    fun `Found carries the name and all three dates through unchanged`() {
        val details = EventDetails.Found(
            name = "Anna's Birthday",
            startsAt = eventStart("2026-07-14T18:00:00Z"),
            endsAt = eventEnd("2026-07-21T18:00:00Z"),
            deletesAt = deletesAt("2026-08-13T18:00:00Z"),
        )

        assertEquals(
            JoinLoad.Found(
                "Anna's Birthday",
                eventStart("2026-07-14T18:00:00Z"),
                eventEnd("2026-07-21T18:00:00Z"),
                deletesAt("2026-08-13T18:00:00Z"),
            ),
            details.toJoinLoad(),
        )
    }

    @Test
    fun `a definitive absence and a failed read stay different answers`() {
        // The teardown fires on the first and must not fire on the second.
        assertEquals(JoinLoad.NotFound, EventDetails.NotFound.toJoinLoad())
        assertEquals(JoinLoad.Failed, EventDetails.Failed.toJoinLoad())
    }
}
