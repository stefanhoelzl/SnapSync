package app.snapsync.contracts

import app.snapsync.wire.api.AppTooOld
import app.snapsync.wire.api.ChangeEventReply
import app.snapsync.wire.api.CreateEventReply
import app.snapsync.wire.api.EventNotFound
import app.snapsync.wire.api.GetEventReply
import app.snapsync.wire.api.Invalid
import app.snapsync.wire.api.Limits
import app.snapsync.wire.api.Reply
import app.snapsync.wire.api.Routes
import app.snapsync.wire.api.Unauthorized
import app.snapsync.wire.models.EventChange
import app.snapsync.wire.models.EventCreate
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * The event registry's operations — create and read here, change in [changeClauses] — and the version gate every
 * operation sits behind. Part of [BackendContract]'s clause list, a split for size only.
 */
internal fun ClauseList<BackendState, EdgeSubject<BackendClient>>.eventClauses() {
    clause(
        "CREATE_A_VALID_EVENT_IS_CREATED",
        BackendState.SERVING,
        covers = cells { backend(Routes.CreateEvent.ID, CreateEventReply.Created::class) },
    ) { s ->
        val created = assertAnswer<CreateEventReply.Created>(s.create("Anna's Birthday")).event
        assertTrue(UUID.matches(created.eventId), "the backend mints a UUID event id: ${created.eventId}")
        assertEquals("Anna's Birthday", created.name, "the backend echoes the name it stored")
        assertTrue(WHOLE_SECOND.matches(created.createdAt), "an instant is whole-second UTC: ${created.createdAt}")
    }

    clause(
        "CREATE_AN_ABSENT_END_IS_ACCEPTED",
        BackendState.SERVING,
        covers = cells { backend(Routes.CreateEvent.ID, CreateEventReply.Created::class) },
    ) { s ->
        val created = assertAnswer<CreateEventReply.Created>(
            s.create("No end", endsAt = null),
            "the backend supplies the end",
        )
        assertSameInstant("2030-01-31T00:00:00Z", created.event.endsAt, "an absent end is the start + 30 days")
    }

    // The name cap is ONE value (`x-snapsync-export` in `api/openapi.yaml`): the screen bounds the field to it, so a
    // name the screen accepts is never refused, and one past it always is.
    clause(
        "CREATE_A_NAME_AT_THE_CAP_IS_CREATED_AND_ONE_PAST_IT_REFUSED",
        BackendState.SERVING,
        covers = cells {
            backend(Routes.CreateEvent.ID, CreateEventReply.Created::class)
            backend(Routes.CreateEvent.ID, CreateEventReply.InvalidName::class)
        },
    ) { s ->
        val atCap = "n".repeat(Limits.EVENT_NAME_MAX_LENGTH)
        assertEquals(atCap, assertAnswer<CreateEventReply.Created>(s.create(atCap)).event.name, "the cap is admitted")
        assertAnswer<CreateEventReply.InvalidName>(s.create(atCap + "n"), "one past the cap is refused as the name")
    }

    clause(
        "CREATE_WITH_A_KEY_ID_IS_AN_ENCRYPTED_EVENT_AND_ONLY_THEN",
        BackendState.SERVING,
        covers = cells {
            backend(Routes.CreateEvent.ID, CreateEventReply.Created::class)
            backend(Routes.CreateEvent.ID, Invalid::class)
            backend(Routes.GetEvent.ID, GetEventReply.Found::class)
        },
    ) { s ->
        // The encrypted file format (`docs/architecture.md`): the backend keeps the key's id, never the key.
        val keyId = "de30249b854310c8"
        val encrypted = assertAnswer<CreateEventReply.Created>(s.create("Secret", keyId = keyId)).event
        assertEquals(keyId, s.details(encrypted.eventId).keyId, "an encrypted event names its key id")
        val plain = assertAnswer<CreateEventReply.Created>(s.create("Open")).event
        assertEquals(null, s.details(plain.eventId).keyId, "a plain event names none")
        assertAnswer<Invalid>(
            s.create("Bad", keyId = "DE30249B854310C8"),
            "a malformed key id is refused, never created as a plain event",
        )
    }

    clause(
        "CREATE_A_BLANK_NAME_IS_REFUSED_AS_THE_NAME",
        BackendState.SERVING,
        covers = cells { backend(Routes.CreateEvent.ID, CreateEventReply.InvalidName::class) },
    ) { s ->
        assertAnswer<CreateEventReply.InvalidName>(s.create("   "))
    }

    clause(
        "CREATE_AN_END_BEFORE_THE_START_IS_REFUSED_AS_THE_WINDOW",
        BackendState.SERVING,
        covers = cells { backend(Routes.CreateEvent.ID, CreateEventReply.InvalidWindow::class) },
    ) { s ->
        assertAnswer<CreateEventReply.InvalidWindow>(s.create("Backwards", SEEDED_ENDS_AT, SEEDED_STARTS_AT))
    }

    clause(
        "CREATE_A_WINDOW_LONGER_THAN_30_DAYS_IS_REFUSED_AS_THE_WINDOW",
        BackendState.SERVING,
        covers = cells { backend(Routes.CreateEvent.ID, CreateEventReply.InvalidWindow::class) },
    ) { s ->
        assertAnswer<CreateEventReply.InvalidWindow>(s.create("Too long", endsAt = "2030-02-15T00:00:00Z"))
    }

    // An event created after its end stays open until the date its host chose — at most
    // the window after the later of its end and now; the backend reads its own clock, so the clause reads the wall's.
    clause(
        "CREATE_A_BACK_DATED_EVENT_IS_OPEN_UNTIL_THE_CHOSEN_DATE",
        BackendState.SERVING,
        covers = cells { backend(Routes.CreateEvent.ID, CreateEventReply.Created::class) },
    ) { s ->
        val openUntil = wholeSecondsFromNow(7.days)
        val created = assertAnswer<CreateEventReply.Created>(
            s.create("Last weekend", SEEDED_ENDED_STARTS_AT, SEEDED_ENDED_ENDS_AT, openUntil = openUntil),
        ).event
        assertSameInstant(openUntil, created.openUntil, "the date the host chose")
        assertSameInstant(
            (Instant.parse(openUntil) + 30.days).toString(),
            created.deletesAt,
            "deletesAt is openUntil + 30 days",
        )
    }

    clause(
        "CREATE_AN_OPEN_UNTIL_PAST_THE_WINDOW_IS_REFUSED_AS_THE_WINDOW",
        BackendState.SERVING,
        covers = cells { backend(Routes.CreateEvent.ID, CreateEventReply.InvalidWindow::class) },
    ) { s ->
        assertAnswer<CreateEventReply.InvalidWindow>(
            s.create(
                "Too long open",
                SEEDED_ENDED_STARTS_AT,
                SEEDED_ENDED_ENDS_AT,
                openUntil = wholeSecondsFromNow(40.days),
            ),
        )
        assertAnswer<CreateEventReply.InvalidWindow>(
            s.create("Open before its end", openUntil = SEEDED_STARTS_AT),
            "an event is open at least until its end",
        )
    }

    clause(
        "GET_AN_EXISTING_EVENT_IS_SERVED_WITH_ITS_WINDOW",
        BackendState.EVENT_EXISTS,
        covers = cells { backend(Routes.GetEvent.ID, GetEventReply.Found::class) },
    ) { s ->
        val meta = s.details(s.seeded.eventId)
        assertEquals(s.seeded.event!!.name, meta.name)
        assertSameInstant(SEEDED_STARTS_AT, meta.startsAt, "the start the creator chose")
        assertSameInstant(SEEDED_ENDS_AT, meta.endsAt, "the end the creator chose")
        assertSameInstant(SEEDED_ENDS_AT, meta.openUntil, "an event created before its end is open until its end")
        // The lifetime is 30 days from max(createdAt, openUntil); the seeded end is in the future.
        assertSameInstant("2030-02-07T00:00:00Z", meta.deletesAt, "deletesAt is openUntil + 30 days")
        listOf(meta.createdAt, meta.startsAt, meta.endsAt, meta.openUntil, meta.deletesAt).forEach {
            assertTrue(WHOLE_SECOND.matches(it), "an instant is whole-second UTC: $it")
        }
    }

    clause(
        "GET_AN_UNKNOWN_EVENT_IS_NOT_FOUND",
        BackendState.NO_SUCH_EVENT,
        covers = cells { backend(Routes.GetEvent.ID, EventNotFound::class) },
    ) { s ->
        assertAnswer<EventNotFound>(
            s.port.send(Routes.GetEvent.request(s.seeded.eventId)),
            "absent is a sealed deletion, which the app reads as gone",
        )
    }

    clause(
        "GET_NEEDS_NO_CREDENTIAL",
        BackendState.EVENT_EXISTS,
        covers = cells { backend(Routes.GetEvent.ID, GetEventReply.Found::class) },
    ) { s ->
        assertAnswer<GetEventReply.Found>(
            s.port.send(Routes.GetEvent.request(s.seeded.eventId)),
            "the event read is authorized by the id alone",
        )
    }

    // Public, but a token it is SENT is verified, so its 401 is a verdict on that token (decision record
    // `changes/separate-event-page-from-device-api`, D7).
    clause(
        "GET_A_SENT_TOKEN_THE_BACKEND_NEVER_ISSUED_IS_REFUSED",
        BackendState.FOREIGN_TOKEN,
        covers = cells { backend(Routes.GetEvent.ID, Unauthorized::class) },
    ) { s ->
        assertAnswer<Unauthorized>(s.port.send(Routes.GetEvent.request(s.seeded.eventId)), "a sent token is checked")
    }

    clause(
        "A_REFUSED_BUILD_IS_TOLD_THE_MINIMUM",
        BackendState.VERSION_REFUSED,
        covers = cells { backend(Routes.GetEvent.ID, AppTooOld::class) },
    ) { s ->
        val refused = assertAnswer<AppTooOld>(
            s.port.send(Routes.GetEvent.request(s.seeded.eventId)),
            "a refused build is served nothing",
        )
        val minimum = assertNotNull(refused.appTooOldError.minAppVersion, "the backend names the minimum")
        assertTrue(Regex("""\d+\.\d+""").matches(minimum), "the minimum is an X.Y marketing version: $minimum")
    }
}

/** The event's one change — its name — and what a closed event refuses of it. */
internal fun ClauseList<BackendState, EdgeSubject<BackendClient>>.changeClauses() {
    clause(
        "CHANGE_ANSWERS_THE_STORED_NAME",
        BackendState.EVENT_EXISTS,
        covers = cells { backend(Routes.ChangeEvent.ID, ChangeEventReply.Changed::class) },
    ) { s ->
        val changed = assertAnswer<ChangeEventReply.Changed>(s.rename(" Renamed "))
        assertEquals("Renamed", changed.event.name, "the backend answers the name it stored, trimmed")
        assertEquals(s.seeded.eventId, changed.event.eventId)
    }

    clause(
        "CHANGE_A_BLANK_NAME_IS_REFUSED",
        BackendState.EVENT_EXISTS,
        covers = cells { backend(Routes.ChangeEvent.ID, Invalid::class) },
    ) { s ->
        assertAnswer<Invalid>(s.rename("   "))
    }

    clause(
        "CHANGE_A_NAME_PAST_THE_CAP_IS_REFUSED",
        BackendState.EVENT_EXISTS,
        covers = cells {
            backend(Routes.ChangeEvent.ID, ChangeEventReply.Changed::class)
            backend(Routes.ChangeEvent.ID, Invalid::class)
        },
    ) { s ->
        val atCap = "n".repeat(Limits.EVENT_NAME_MAX_LENGTH)
        assertAnswer<ChangeEventReply.Changed>(s.rename(atCap), "the cap is admitted")
        assertAnswer<Invalid>(s.rename(atCap + "n"), "one past the cap is refused")
    }

    clause(
        "CHANGE_AN_UNKNOWN_EVENT_IS_NOT_FOUND",
        BackendState.NO_SUCH_EVENT,
        covers = cells { backend(Routes.ChangeEvent.ID, EventNotFound::class) },
    ) { s ->
        assertAnswer<EventNotFound>(s.rename("Nobody"))
    }

    clause(
        "CHANGE_AN_EVENT_EVERYONE_SHARED_IS_RENAMED",
        BackendState.ENDED_MEMBER,
        covers = cells {
            backend(Routes.ChangeEvent.ID, ChangeEventReply.Changed::class)
            backend(Routes.GetEvent.ID, GetEventReply.Found::class)
        },
    ) { s ->
        // Nothing closes an event any more: its name changes until its photos are deleted.
        settle(s)
        assertAnswer<ChangeEventReply.Changed>(s.rename("Renamed"))
        assertEquals("Renamed", s.details(s.seeded.eventId).name)
    }
}

private val UUID = Regex("""[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}""")

/** The description's `Timestamp`: whole-second UTC, the one shape it admits. */
private val WHOLE_SECOND = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z""")

/** [reply], which must be a [T] — the outcome a clause pins. */
internal inline fun <reified T : Reply> assertAnswer(reply: Reply, message: String? = null): T =
    assertIs<T>(reply, listOfNotNull(message, "answered $reply").joinToString(" — "))

/** Create an event as the app does, over the seeded window unless told otherwise. */
private suspend fun EdgeSubject<BackendClient>.create(
    name: String,
    startsAt: String = SEEDED_STARTS_AT,
    endsAt: String? = SEEDED_ENDS_AT,
    keyId: String? = null,
    openUntil: String? = null,
): CreateEventReply =
    port.send(Routes.CreateEvent.request(EventCreate(name, startsAt, endsAt, openUntil = openUntil, keyId = keyId)))

/** The wall clock plus [after], whole-second UTC — the description's `Timestamp`. */
private fun wholeSecondsFromNow(after: Duration): String =
    Instant.fromEpochSeconds((Clock.System.now() + after).epochSeconds).toString()

/** Change this state's event's name. */
private suspend fun EdgeSubject<BackendClient>.rename(name: String): ChangeEventReply =
    port.send(Routes.ChangeEvent.request(seeded.eventId, EventChange(name)))

/** The details of [eventId], which must be found. */
internal suspend fun EdgeSubject<BackendClient>.details(eventId: String) =
    assertAnswer<GetEventReply.Found>(port.send(Routes.GetEvent.request(eventId))).eventDetails

private fun assertSameInstant(expected: String, actual: String?, message: String) {
    assertEquals(Instant.parse(expected), actual?.let(Instant::parse), "$message: $actual")
}
