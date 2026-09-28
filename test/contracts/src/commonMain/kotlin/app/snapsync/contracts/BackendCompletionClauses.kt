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

    clause("DETAILS_AN_OPEN_EVENT_IS_NEITHER_CLOSED_NOR_COMPLETED", BackendState.MEMBER) { s ->
        val meta = assertOk(s.port.getEvent(s.seeded.eventId))
        assertNull(meta.closedAt)
        assertNull(meta.completedAt)
        assertEquals(MemberCounts(active = 1, settled = 0), meta.members)
    }

    clause("MANIFEST_THE_LAST_MEMBER_SETTLING_CLOSES_THE_EVENT", BackendState.ENDED_MEMBER) { s ->
        settle(s)
        val meta = assertOk(s.port.getEvent(s.seeded.eventId))
        assertNotNull(meta.closedAt, "every active member has settled after the end")
        assertNull(meta.completedAt)
        assertEquals(MemberCounts(active = 1, settled = 1), meta.members)
    }

    clause("JOIN_A_CLOSED_EVENT_IS_GONE_EVEN_FOR_A_RETURNING_DEVICE", BackendState.ENDED_MEMBER) { s ->
        settle(s)
        assertOk(s.port.leaveEvent(s.token, s.seeded.eventId, s.seeded.deviceId))
        assertRefused(GONE, s.port.joinEvent(s.token, s.seeded.eventId, s.seeded.deviceId), "closed, never full")
    }

    clause("RENAME_A_CLOSED_EVENT_IS_GONE", BackendState.ENDED_MEMBER) { s ->
        settle(s)
        assertRefused(GONE, s.port.renameEvent(s.token, s.seeded.eventId, "Renamed"))
        assertEquals(s.seeded.event?.name, assertOk(s.port.getEvent(s.seeded.eventId)).name)
    }

    clause("MANIFEST_A_CHANGED_SET_TO_A_CLOSED_EVENT_IS_REFUSED_AS_CLOSED", BackendState.ENDED_MEMBER) { s ->
        settle(s)
        val refused = assertIs<Reply.Refused>(
            s.port.publishManifest(s.token, s.seeded.eventId, s.seeded.deviceId, manifest(s.seeded.deviceId).withFinal(true)),
        )
        assertEquals(CONFLICT, refused.status)
        assertTrue("\"closed\"" in refused.body, "the refusal names the close: ${refused.body}")
        // The set it already declared is answered, and changes nothing.
        assertOk(
            s.port.publishManifest(s.token, s.seeded.eventId, s.seeded.deviceId, DeviceManifest(s.seeded.deviceId, emptyList()).withFinal(true)),
        )
    }
}

/** The seeded member declares an EMPTY share settled — the one member, so it closes the ended event. */
private suspend fun settle(s: EdgeSubject<Backend>) {
    assertOk(s.port.publishManifest(s.token, s.seeded.eventId, s.seeded.deviceId, DeviceManifest(s.seeded.deviceId, emptyList()).withFinal(true)))
}

internal const val GONE = 410
