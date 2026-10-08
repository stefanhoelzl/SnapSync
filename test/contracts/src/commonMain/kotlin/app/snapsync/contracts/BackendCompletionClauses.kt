package app.snapsync.contracts

import app.snapsync.model.DeviceManifest
import app.snapsync.model.MemberCounts
import app.snapsync.model.Reply
import app.snapsync.model.withFinal
import app.snapsync.ports.Backend
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Early completion (capability `event-lifetime`; decision record `changes/early-event-completion`): a member's
 * settled share, the close it makes, and what a closed event refuses. Part of [BackendContract]'s clause list, a split
 * for size only. The backend's sweep — completing an event — has no route, so no clause here reaches it.
 */
internal fun ClauseList<BackendState, EdgeSubject<Backend>>.completionClauses() {
    clause(
        "DETAILS_AN_OPEN_EVENT_IS_NEITHER_CLOSED_NOR_COMPLETED",
        BackendState.MEMBER,
        covers = cells { on<Backend>().answers(Backend::getEvent).withGenericLeaf(Reply.Ok::class) },
    ) { s ->
        val meta = assertOk(s.port.getEvent(null, s.seeded.eventId))
        assertNull(meta.closedAt)
        assertNull(meta.completedAt)
        assertEquals(MemberCounts(active = 1, settled = 0), meta.members)
    }

    clause(
        "MANIFEST_THE_LAST_MEMBER_SETTLING_CLOSES_THE_EVENT",
        BackendState.ENDED_MEMBER,
        covers = cells {
            on<Backend> {
                answers(Backend::getEvent).withGenericLeaf(Reply.Ok::class)
                answers(Backend::publishManifest).withGenericLeaf(Reply.Ok::class)
            }
        },
    ) { s ->
        settle(s)
        val meta = assertOk(s.port.getEvent(null, s.seeded.eventId))
        assertNotNull(meta.closedAt, "every active member has settled after the end")
        assertNull(meta.completedAt)
        assertEquals(MemberCounts(active = 1, settled = 1), meta.members)
    }

    clause(
        "LEAVE_THE_LAST_UNSETTLED_MEMBER_LEAVING_CLOSES_THE_EVENT",
        BackendState.ENDED_BESIDE_A_SETTLED_MEMBER,
        covers = cells {
            on<Backend> {
                answers(Backend::getEvent).withGenericLeaf(Reply.Ok::class)
                answers(Backend::leaveEvent).withGenericLeaf(Reply.Ok::class)
            }
        },
    ) { s ->
        assertNull(
            assertOk(s.port.getEvent(null, s.seeded.eventId)).closedAt,
            "the event waits for the unsettled member",
        )
        assertOk(s.port.leaveEvent(s.token, s.seeded.eventId, s.seeded.deviceId, received = false))
        val meta = assertOk(s.port.getEvent(null, s.seeded.eventId))
        assertNotNull(meta.closedAt, "every member still in the event has settled")
        assertEquals(MemberCounts(active = 1, settled = 1), meta.members)
    }

    clause(
        "LEAVE_FROM_A_CLOSED_EVENT_KEEPS_ITS_CLOSE",
        BackendState.ENDED_BESIDE_A_SETTLED_MEMBER,
        covers = cells {
            on<Backend> {
                answers(Backend::getEvent).withGenericLeaf(Reply.Ok::class)
                answers(Backend::leaveEvent).withGenericLeaf(Reply.Ok::class)
                answers(Backend::publishManifest).withGenericLeaf(Reply.Ok::class)
            }
        },
    ) { s ->
        settle(s)
        // The device settled last, so the event closed: its leave afterwards changes nothing about the close.
        val closed = assertOk(s.port.getEvent(null, s.seeded.eventId)).closedAt
        assertNotNull(closed)
        assertOk(s.port.leaveEvent(s.token, s.seeded.eventId, s.seeded.deviceId, received = true))
        assertEquals(closed, assertOk(s.port.getEvent(null, s.seeded.eventId)).closedAt)
    }

    clause(
        "JOIN_A_CLOSED_EVENT_IS_GONE_EVEN_FOR_A_RETURNING_DEVICE",
        BackendState.ENDED_MEMBER,
        covers = cells {
            on<Backend> {
                answers(Backend::joinEvent).with(Reply.Refused::class)
                answers(Backend::leaveEvent).withGenericLeaf(Reply.Ok::class)
                answers(Backend::publishManifest).withGenericLeaf(Reply.Ok::class)
            }
        },
    ) { s ->
        settle(s)
        assertOk(s.port.leaveEvent(s.token, s.seeded.eventId, s.seeded.deviceId, received = false))
        assertRefused(GONE, s.port.joinEvent(s.token, s.seeded.eventId, s.seeded.deviceId), "closed, never full")
    }

    clause(
        "RENAME_A_CLOSED_EVENT_IS_GONE",
        BackendState.ENDED_MEMBER,
        covers = cells {
            on<Backend> {
                answers(Backend::getEvent).withGenericLeaf(Reply.Ok::class)
                answers(Backend::publishManifest).withGenericLeaf(Reply.Ok::class)
                answers(Backend::renameEvent).with(Reply.Refused::class)
            }
        },
    ) { s ->
        settle(s)
        assertRefused(GONE, s.port.renameEvent(s.token, s.seeded.eventId, "Renamed"))
        assertEquals(s.seeded.event?.name, assertOk(s.port.getEvent(null, s.seeded.eventId)).name)
    }

    clause(
        "MANIFEST_A_CHANGED_SET_TO_A_CLOSED_EVENT_IS_REFUSED_AS_CLOSED",
        BackendState.ENDED_MEMBER,
        covers = cells {
            on<Backend> {
                answers(Backend::publishManifest).withGenericLeaf(Reply.Ok::class)
                answers(Backend::publishManifest).with(Reply.Refused::class)
            }
        },
    ) { s ->
        settle(s)
        val refused = assertIs<Reply.Refused>(
            s.port.publishManifest(
                s.token,
                s.seeded.eventId,
                s.seeded.deviceId,
                manifest(s.seeded.deviceId).withFinal(true),
            ),
        )
        assertEquals(CONFLICT, refused.status)
        assertTrue("\"closed\"" in refused.body, "the refusal names the close: ${refused.body}")
        // The set it already declared is answered, and changes nothing.
        assertOk(
            s.port.publishManifest(
                s.token,
                s.seeded.eventId,
                s.seeded.deviceId,
                DeviceManifest(s.seeded.deviceId, emptyList(), version = 0).withFinal(true),
            ),
        )
    }
}

/** The seeded member declares an EMPTY share settled — the one member, so it closes the ended event. */
private suspend fun settle(s: EdgeSubject<Backend>) {
    assertOk(
        s.port.publishManifest(
            s.token,
            s.seeded.eventId,
            s.seeded.deviceId,
            DeviceManifest(s.seeded.deviceId, emptyList(), version = 0).withFinal(true),
        ),
    )
}

internal const val GONE = 410
