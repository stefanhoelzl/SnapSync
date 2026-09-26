package app.snapsync.contracts

import app.snapsync.model.CreateEventRequest
import app.snapsync.model.Reply
import app.snapsync.model.minAppVersionFromRefusal
import app.snapsync.ports.Backend
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The event registry's routes — create, read, rename — and the version gate every route sits behind. Part of
 * [BackendContract]'s clause list, a split for size only.
 */
internal fun ClauseList<BackendState, EdgeSubject<Backend>>.eventClauses() {

    clause("CREATE_A_VALID_EVENT_IS_CREATED", BackendState.SERVING) { s ->
        val created = assertOk(s.port.createEvent(s.token, CreateEventRequest("Anna's Birthday", SEEDED_STARTS_AT, SEEDED_ENDS_AT)))
        assertTrue(UUID.matches(created.eventId), "the backend mints a UUID event id: ${created.eventId}")
        assertEquals("Anna's Birthday", created.name, "the backend echoes the name it stored")
    }

    clause("CREATE_AN_ABSENT_END_IS_ACCEPTED", BackendState.SERVING) { s ->
        assertOk(s.port.createEvent(s.token, CreateEventRequest("No end", SEEDED_STARTS_AT, null)), "the backend supplies the end")
    }

    clause("CREATE_A_BLANK_NAME_IS_REFUSED_AS_THE_NAME", BackendState.SERVING) { s ->
        val refused = assertRefused(BAD_REQUEST, s.port.createEvent(s.token, CreateEventRequest("   ", SEEDED_STARTS_AT, SEEDED_ENDS_AT)))
        assertFalse(WINDOW_FIELDS.any { it in refused.body }, "a refused name names no date field: ${refused.body}")
    }

    clause("CREATE_AN_END_BEFORE_THE_START_IS_REFUSED_AS_THE_WINDOW", BackendState.SERVING) { s ->
        val refused = assertRefused(BAD_REQUEST, s.port.createEvent(s.token, CreateEventRequest("Backwards", SEEDED_ENDS_AT, SEEDED_STARTS_AT)))
        assertTrue(WINDOW_FIELDS.any { it in refused.body }, "a refused window names the date it refused: ${refused.body}")
    }

    clause("CREATE_A_WINDOW_LONGER_THAN_30_DAYS_IS_REFUSED_AS_THE_WINDOW", BackendState.SERVING) { s ->
        val refused = assertRefused(
            BAD_REQUEST,
            s.port.createEvent(s.token, CreateEventRequest("Too long", SEEDED_STARTS_AT, "2030-02-15T00:00:00Z")),
        )
        assertTrue(WINDOW_FIELDS.any { it in refused.body }, "a refused window names the date it refused: ${refused.body}")
    }

    clause("GET_AN_EXISTING_EVENT_IS_SERVED_WITH_ITS_WINDOW", BackendState.EVENT_EXISTS) { s ->
        val meta = assertOk(s.port.getEvent(s.seeded.eventId))
        assertEquals(s.seeded.event!!.name, meta.name)
        assertSameInstant(SEEDED_STARTS_AT, meta.startsAt, "the start the creator chose")
        assertSameInstant(SEEDED_ENDS_AT, meta.endsAt, "the end the creator chose")
        // The lifetime is 30 days from max(createdAt, startsAt); the seeded start is in the future.
        assertSameInstant("2030-01-31T00:00:00Z", meta.deletesAt, "deletesAt is startsAt + 30 days")
    }

    clause("GET_AN_UNKNOWN_EVENT_IS_NOT_FOUND", BackendState.NO_SUCH_EVENT) { s ->
        assertRefused(NOT_FOUND, s.port.getEvent(s.seeded.eventId), "absent is 404, which the app reads as gone")
    }

    clause("GET_NEEDS_NO_CREDENTIAL", BackendState.FOREIGN_TOKEN) { s ->
        assertOk(s.port.getEvent(s.seeded.eventId), "the event read is authorized by the id alone")
    }

    clause("A_REFUSED_BUILD_IS_TOLD_THE_MINIMUM", BackendState.VERSION_REFUSED) { s ->
        val refused = assertRefused(UPGRADE_REQUIRED, s.port.getEvent(s.seeded.eventId), "a refused build is served nothing")
        val minimum = assertNotNull(minAppVersionFromRefusal(refused.body), "the backend names the minimum: ${refused.body}")
        assertTrue(Regex("""\d+\.\d+""").matches(minimum), "the minimum is an X.Y marketing version: $minimum")
    }

    clause("RENAME_ANSWERS_THE_STORED_NAME", BackendState.EVENT_EXISTS) { s ->
        assertEquals("Renamed", assertOk(s.port.renameEvent(s.token, s.seeded.eventId, "Renamed")).name)
    }

    clause("RENAME_A_BLANK_NAME_IS_REFUSED", BackendState.EVENT_EXISTS) { s ->
        assertRefused(BAD_REQUEST, s.port.renameEvent(s.token, s.seeded.eventId, "   "))
    }

    clause("RENAME_AN_UNKNOWN_EVENT_IS_REFUSED", BackendState.NO_SUCH_EVENT) { s ->
        assertIs<Reply.Refused>(s.port.renameEvent(s.token, s.seeded.eventId, "Nobody"))
    }
}

internal const val BAD_REQUEST = 400
internal const val UNAUTHORIZED = 401
internal const val NOT_FOUND = 404
internal const val CONFLICT = 409
internal const val UPGRADE_REQUIRED = 426

private val UUID = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")

/** The fields a date-range refusal names. */
private val WINDOW_FIELDS = listOf("startsAt", "endsAt")

internal fun <T> assertOk(reply: Reply<T>, message: String? = null): T =
    assertIs<Reply.Ok<T>>(reply, listOfNotNull(message, "answered $reply").joinToString(" — ")).value

internal fun assertRefused(status: Int, reply: Reply<*>, message: String? = null): Reply.Refused {
    val refused = assertIs<Reply.Refused>(reply, listOfNotNull(message, "answered $reply").joinToString(" — "))
    assertEquals(status, refused.status, listOfNotNull(message, "body: ${refused.body}").joinToString(" — "))
    return refused
}

private fun assertSameInstant(expected: String, actual: String?, message: String) {
    assertEquals(Instant.parse(expected), actual?.let(Instant::parse), "$message: $actual")
}
