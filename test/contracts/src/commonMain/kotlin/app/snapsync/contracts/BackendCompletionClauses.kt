package app.snapsync.contracts

import app.snapsync.wire.api.Closed
import app.snapsync.wire.api.GetEventReply
import app.snapsync.wire.api.LeaveEventReply
import app.snapsync.wire.api.PublishManifestReply
import app.snapsync.wire.api.Routes
import app.snapsync.wire.models.ClosedErrorError
import app.snapsync.wire.models.MemberCounts
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Early completion (decision record `changes/early-event-completion`): a member's
 * settled share, the close it makes, and what a closed event refuses. Part of [BackendContract]'s clause list, a split
 * for size only. The backend's sweep — completing an event — has no route, so no clause here reaches it.
 */
internal fun ClauseList<BackendState, EdgeSubject<BackendClient>>.completionClauses() {
    clause(
        "DETAILS_AN_OPEN_EVENT_IS_NEITHER_CLOSED_NOR_COMPLETED",
        BackendState.MEMBER,
        covers = cells { backend(Routes.GetEvent.ID, GetEventReply.Found::class) },
    ) { s ->
        val meta = s.details(s.seeded.eventId)
        assertNull(meta.closedAt)
        assertNull(meta.completedAt)
        assertEquals(MemberCounts(active = 1, `final` = 0), meta.members)
    }

    clause(
        "MANIFEST_THE_LAST_MEMBER_SETTLING_CLOSES_THE_EVENT",
        BackendState.ENDED_MEMBER,
        covers = cells {
            backend(Routes.GetEvent.ID, GetEventReply.Found::class)
            backend(Routes.PublishManifest.ID, PublishManifestReply.Published::class)
        },
    ) { s ->
        settle(s)
        val meta = s.details(s.seeded.eventId)
        assertNotNull(meta.closedAt, "every active member has settled after the end")
        assertNull(meta.completedAt)
        assertEquals(MemberCounts(active = 1, `final` = 1), meta.members)
    }

    clause(
        "LEAVE_THE_LAST_UNSETTLED_MEMBER_LEAVING_CLOSES_THE_EVENT",
        BackendState.ENDED_BESIDE_A_SETTLED_MEMBER,
        covers = cells {
            backend(Routes.GetEvent.ID, GetEventReply.Found::class)
            backend(Routes.LeaveEvent.ID, LeaveEventReply.Left::class)
        },
    ) { s ->
        assertNull(s.details(s.seeded.eventId).closedAt, "the event waits for the unsettled member")
        assertAnswer<LeaveEventReply.Left>(s.leave(received = false))
        val meta = s.details(s.seeded.eventId)
        assertNotNull(meta.closedAt, "every member still in the event has settled")
        assertEquals(MemberCounts(active = 1, `final` = 1), meta.members)
    }

    clause(
        "LEAVE_FROM_A_CLOSED_EVENT_KEEPS_ITS_CLOSE",
        BackendState.ENDED_BESIDE_A_SETTLED_MEMBER,
        covers = cells {
            backend(Routes.GetEvent.ID, GetEventReply.Found::class)
            backend(Routes.LeaveEvent.ID, LeaveEventReply.Left::class)
            backend(Routes.PublishManifest.ID, PublishManifestReply.Published::class)
        },
    ) { s ->
        settle(s)
        // The device settled last, so the event closed: its leave afterwards changes nothing about the close.
        val closed = assertNotNull(s.details(s.seeded.eventId).closedAt)
        assertAnswer<LeaveEventReply.Left>(s.leave(received = true))
        assertEquals(closed, s.details(s.seeded.eventId).closedAt)
    }

    clause(
        "JOIN_A_CLOSED_EVENT_IS_CLOSED_EVEN_FOR_A_RETURNING_DEVICE",
        BackendState.ENDED_MEMBER,
        covers = cells {
            backend(Routes.JoinEvent.ID, Closed::class)
            backend(Routes.LeaveEvent.ID, LeaveEventReply.Left::class)
            backend(Routes.PublishManifest.ID, PublishManifestReply.Published::class)
        },
    ) { s ->
        settle(s)
        assertAnswer<LeaveEventReply.Left>(s.leave(received = false))
        assertAnswer<Closed>(s.join(), "closed, never full")
    }

    clause(
        "MANIFEST_A_CHANGED_SET_TO_A_CLOSED_EVENT_IS_REFUSED_AS_FIXED",
        BackendState.ENDED_MEMBER,
        covers = cells {
            backend(Routes.PublishManifest.ID, PublishManifestReply.Published::class)
            backend(Routes.PublishManifest.ID, PublishManifestReply.SetFixed::class)
        },
    ) { s ->
        settle(s)
        val refused = assertAnswer<PublishManifestReply.SetFixed>(
            s.publish(manifest(s.seeded.deviceId).copy(`final` = true)),
        )
        assertEquals(ClosedErrorError.CLOSED, refused.closedError.error, "the refusal names the close")
        // The set it already declared is answered, and changes nothing.
        assertAnswer<PublishManifestReply.Published>(
            s.publish(manifestOf(s.seeded.deviceId, emptyList(), final = true)),
        )
    }
}

/** The seeded member declares an EMPTY share settled — the one member, so it closes the ended event. */
internal suspend fun settle(s: EdgeSubject<BackendClient>) {
    assertAnswer<PublishManifestReply.Published>(s.publish(manifestOf(s.seeded.deviceId, emptyList(), final = true)))
}
