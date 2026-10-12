package app.snapsync.contracts

import app.snapsync.wire.api.Closed
import app.snapsync.wire.api.EventNotFound
import app.snapsync.wire.api.GetEventReply
import app.snapsync.wire.api.JoinEventReply
import app.snapsync.wire.api.LeaveEventReply
import app.snapsync.wire.api.PublishManifestReply
import app.snapsync.wire.api.ReportReceivedReply
import app.snapsync.wire.api.Routes
import app.snapsync.wire.models.ClosedErrorError
import app.snapsync.wire.models.EventChange
import app.snapsync.wire.models.MemberCounts
import app.snapsync.wire.models.Received
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * An event's end of life (decision record `changes/back-dated-events`): a member's settled
 * share, what it received, the `closedAt` builds that predate open-until dates read, and what an event that ended for
 * good refuses. Part of [BackendContract]'s clause list, a split for size only. The backend's sweep — completing an
 * event — has no route, so a wire fixture plays the answers only such an event gives.
 */
internal fun ClauseList<BackendState, EdgeSubject<BackendClient>>.completionClauses() {
    clause(
        "DETAILS_AN_EVENT_STILL_SHARING_HAS_NO_CLOSED_AT_AND_IS_NOT_COMPLETED",
        BackendState.MEMBER,
        covers = cells { backend(Routes.GetEvent.ID, GetEventReply.Found::class) },
    ) { s ->
        val meta = s.details(s.seeded.eventId)
        assertNull(meta.closedAt)
        assertNull(meta.completedAt)
        assertEquals(MemberCounts(active = 1, `final` = 0, waiting = 1), meta.members)
    }

    clause(
        "MANIFEST_THE_LAST_MEMBER_SETTLING_SETS_CLOSED_AT_FOR_OLD_BUILDS",
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
        assertEquals(MemberCounts(active = 1, `final` = 1, waiting = 0), meta.members)
    }

    clause(
        "LEAVE_THE_LAST_UNSETTLED_MEMBER_LEAVING_SETS_CLOSED_AT_FOR_OLD_BUILDS",
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
        assertEquals(MemberCounts(active = 1, `final` = 1, waiting = 0), meta.members)
    }

    clause(
        "LEAVE_AFTER_EVERYONE_SHARED_KEEPS_CLOSED_AT",
        BackendState.ENDED_BESIDE_A_SETTLED_MEMBER,
        covers = cells {
            backend(Routes.GetEvent.ID, GetEventReply.Found::class)
            backend(Routes.LeaveEvent.ID, LeaveEventReply.Left::class)
            backend(Routes.PublishManifest.ID, PublishManifestReply.Published::class)
        },
    ) { s ->
        settle(s)
        // The device settled last, so everyone has shared and the compatibility `closedAt` is set: its leave afterwards
        // changes nothing about it.
        val closed = assertNotNull(s.details(s.seeded.eventId).closedAt)
        assertAnswer<LeaveEventReply.Left>(s.leave(received = true))
        assertEquals(closed, s.details(s.seeded.eventId).closedAt)
    }

    clause(
        "JOIN_AN_EVENT_EVERYONE_SHARED_STILL_ADMITS_A_RETURNING_DEVICE",
        BackendState.ENDED_MEMBER,
        covers = cells {
            backend(Routes.JoinEvent.ID, JoinEventReply.Joined::class)
            backend(Routes.LeaveEvent.ID, LeaveEventReply.Left::class)
            backend(Routes.PublishManifest.ID, PublishManifestReply.Published::class)
        },
    ) { s ->
        // Nothing closes an event any more: the one member settled after the end and
        // left, and it may still come back.
        settle(s)
        assertAnswer<LeaveEventReply.Left>(s.leave(received = false))
        assertAnswer<JoinEventReply.Joined>(s.join(), "an event is open until its photos are deleted")
    }

    clause(
        "MANIFEST_A_CHANGED_SET_AFTER_EVERYONE_SHARED_IS_PUBLISHED",
        BackendState.ENDED_MEMBER,
        covers = cells {
            backend(Routes.GetEvent.ID, GetEventReply.Found::class)
            backend(Routes.PublishManifest.ID, PublishManifestReply.Published::class)
        },
    ) { s ->
        settle(s)
        // What a member shares is never fixed: a photo it declares after settling is shared — and, until it lands,
        // makes the member one the event waits for again.
        assertAnswer<PublishManifestReply.Published>(
            s.publish(manifest(s.seeded.deviceId).copy(version = 1, `final` = true)),
        )
        val meta = s.details(s.seeded.eventId)
        assertNull(meta.closedAt, "a declared photo that has not arrived is still being shared")
        assertEquals(MemberCounts(active = 1, `final` = 1, waiting = 1), meta.members)
    }

    clause(
        "RECEIVED_A_MEMBER_RECORDS_THE_HIGHEST_POSITION",
        BackendState.MEMBER,
        covers = cells { backend(Routes.ReportReceived.ID, ReportReceivedReply.Recorded::class) },
    ) { s ->
        assertAnswer<ReportReceivedReply.Recorded>(s.received(5))
        assertAnswer<ReportReceivedReply.Recorded>(s.received(3), "a lower position is answered and changes nothing")
    }

    clause(
        "RECEIVED_FROM_A_DEVICE_NOT_IN_THE_EVENT_IS_NOT_A_MEMBER",
        BackendState.EVENT_EXISTS,
        covers = cells { backend(Routes.ReportReceived.ID, ReportReceivedReply.NotAMember::class) },
    ) { s ->
        assertAnswer<ReportReceivedReply.NotAMember>(s.received(0))
    }

    clause(
        "RECEIVED_FOR_AN_UNKNOWN_EVENT_IS_NOT_FOUND",
        BackendState.NO_SUCH_EVENT,
        covers = cells { backend(Routes.ReportReceived.ID, EventNotFound::class) },
    ) { s ->
        assertAnswer<EventNotFound>(s.received(0))
    }

    // An event completed by the sweep — or closed before events stopped closing — refuses every write as closed. The
    // sweep has no route and nothing closes any more, so a wire fixture plays the backend's answer.
    clause(
        "ENDED_FOR_GOOD_EVERY_WRITE_IS_CLOSED",
        BackendState.ENDED_FOR_GOOD,
        covers = cells {
            backend(Routes.JoinEvent.ID, Closed::class)
            backend(Routes.ChangeEvent.ID, Closed::class)
            backend(Routes.PublishManifest.ID, Closed::class)
        },
    ) { s ->
        assertAnswer<Closed>(s.join(), "closed, never full")
        assertAnswer<Closed>(s.port.send(Routes.ChangeEvent.request(s.seeded.eventId, EventChange("Renamed"))))
        assertAnswer<Closed>(s.publish(manifest(s.seeded.deviceId)))
    }

    clause(
        "CLOSED_BEFORE_OPEN_UNTIL_A_CHANGED_SET_IS_REFUSED_AS_FIXED",
        BackendState.CLOSED_BEFORE_OPEN_UNTIL,
        covers = cells { backend(Routes.PublishManifest.ID, PublishManifestReply.SetFixed::class) },
    ) { s ->
        val refused = assertAnswer<PublishManifestReply.SetFixed>(s.publish(manifest(s.seeded.deviceId)))
        assertEquals(ClosedErrorError.CLOSED, refused.closedError.error, "the refusal names the close")
    }
}

/** This state's device saying it holds every photo of the others up to union [position]. */
internal suspend fun EdgeSubject<BackendClient>.received(position: Long): ReportReceivedReply =
    port.send(Routes.ReportReceived.request(seeded.eventId, seeded.deviceId, Received(position)))

/** The seeded member declares an EMPTY share settled — the one member, so everyone has shared. */
internal suspend fun settle(s: EdgeSubject<BackendClient>) {
    assertAnswer<PublishManifestReply.Published>(s.publish(manifestOf(s.seeded.deviceId, emptyList(), final = true)))
}
