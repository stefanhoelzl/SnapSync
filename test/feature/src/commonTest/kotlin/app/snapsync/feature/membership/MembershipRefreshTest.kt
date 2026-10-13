package app.snapsync.feature.membership

import app.snapsync.domain.event.DeletesAt
import app.snapsync.domain.event.EventEnd
import app.snapsync.domain.event.EventStart
import app.snapsync.domain.event.OpenUntil
import app.snapsync.domain.sharing.CaptureCeiling
import app.snapsync.domain.sharing.CaptureCutoff
import app.snapsync.domain.time.Timestamp
import app.snapsync.feature.support.ConfigWrites
import app.snapsync.mock.fixedClock
import app.snapsync.model.EventCompletionState
import app.snapsync.model.EventConfig
import app.snapsync.model.EventLookup
import app.snapsync.model.JoinLoad
import app.snapsync.model.MemberCounts
import app.snapsync.model.TimestampText
import app.snapsync.services.backend.EventDirectory
import app.snapsync.services.config.ConfigService
import app.snapsync.testids.testEventId
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * The membership over the REAL config service: the file seeded with [initial], and the service built once, at the
 * first refresh, over a clock pinned at that refresh's "now". [saved] is what the last save persisted.
 */
private class Membership(private val initial: EventConfig?) {
    private val writes = ConfigWrites()
    private var built: ConfigService? = null

    fun service(now: Timestamp): ConfigService =
        built ?: writes.service(initial, fixedClock(Instant.parse(TimestampText.format(now)))).also { built = it }

    val saved: EventConfig? get() = writes.saved
    val config: StateFlow<EventConfig?> get() = checkNotNull(built) { "no refresh ran" }.config
}

private val STARTS = EventStart(TimestampText.parse("2026-07-06T14:32:11Z"))
private val CUTOFF = CaptureCutoff(TimestampText.parse("2026-07-06T14:32:11Z"))
private val ENDS = EventEnd(TimestampText.parse("2026-07-13T14:32:11Z"))
private val CEILING = CaptureCeiling(TimestampText.parse("2026-07-13T14:32:11Z"))
private val DELETES = DeletesAt(TimestampText.parse("2026-08-05T14:32:11Z")) // the event's retention deadline
private val BEFORE_DEADLINE = TimestampText.parse("2026-07-20T00:00:00Z")
private val AFTER_DEADLINE = TimestampText.parse("2026-08-06T00:00:00Z")

class MembershipRefreshTest {

    // A NON-legacy membership: it already carries the event window, so a details refresh only touches the
    // name (backfill is a no-op). This keeps the name-convergence assertions isolated from the backfill.
    // The name is a STALE one, never empty: a membership always carries a name (capability `join-event`),
    // so convergence is what this fixture exercises.
    private val joined = EventConfig(
        eventId = testEventId("E"),
        name = "Anna's Bithday",
        minPhotoDate = CUTOFF,
        startsAt = STARTS,
        endsAt = ENDS,
        maxPhotoDate = CEILING,
        deletesAt = DELETES,
    )

    private fun found(name: String, endsAt: EventEnd = ENDS) =
        JoinLoad.Found(name, STARTS, endsAt, DELETES)

    /** How many times a confirmed deletion stopped the event's transfers. */
    private var stopped = 0

    /** The rule under test, with "now" pinned. Defaults BEFORE the deadline (the disbelieving side). */
    private fun refresh(config: Membership, now: Timestamp = BEFORE_DEADLINE) =
        MembershipRefresh(configSource = config.service(now), stopTransfers = { stopped++ })

    @Test
    fun `stores a changed name as the whole config with the cutoff preserved`() = runTest {
        val config = Membership(joined)
        refresh(config).refresh(testEventId("E"), found("Anna's Birthday"))
        // The WHOLE config is saved with only `name` replaced — the cutoff (and every other
        // membership field) rides along untouched (capability `photo-sharing`).
        assertEquals(joined.copy(name = "Anna's Birthday"), config.saved)
    }

    @Test
    fun `the joined membership is kept current from the directory - and nothing is asked while not joined`() = runTest {
        val config = Membership(joined)
        var fetches = 0
        val directory = EventDirectory {
            fetches++
            EventLookup.Found("Anna's Birthday", STARTS, ENDS, DELETES)
        }
        val rule = refresh(config)
        rule.refreshJoined(null, directory)
        assertEquals(0, fetches, "not joined: nothing to ask")
        rule.refreshJoined(testEventId("E"), directory)
        assertEquals(joined.copy(name = "Anna's Birthday"), config.saved)
    }

    @Test
    fun `an unchanged name saves nothing`() = runTest {
        val config = Membership(joined.copy(name = "Anna's Birthday"))
        refresh(config).refresh(testEventId("E"), found("Anna's Birthday"))
        assertNull(config.saved)
    }

    @Test
    fun `a fetch resolving for a different event saves nothing`() = runTest {
        // A stale fetch landing after a switch must not resurrect the departed membership's name.
        val config = Membership(joined)
        refresh(config).refresh(testEventId("OTHER"), found("Someone Else's Party"))
        assertNull(config.saved)
    }

    @Test
    fun `no membership saves nothing`() = runTest {
        val config = Membership(null)
        refresh(config).refresh(testEventId("E"), found("Anna's Birthday"))
        assertNull(config.saved)
    }

    @Test
    fun `an inconclusive fetch stores nothing`() = runTest {
        // "Could not tell" — offline, transport, non-404 status, unparseable body — all arrive as Failed
        // and must change nothing at all.
        val config = Membership(joined)
        assertEquals(RefreshOutcome.INCONCLUSIVE, refresh(config).refresh(testEventId("E"), JoinLoad.Failed))
        assertNull(config.saved)
    }

    @Test
    fun `a wrong-link answer is inconclusive and stores nothing`() = runTest {
        // A refresh reads without a link, so a key verdict says nothing about whether the event is still there.
        val config = Membership(joined)
        assertEquals(RefreshOutcome.INCONCLUSIVE, refresh(config).refresh(testEventId("E"), JoinLoad.WrongLink))
        assertNull(config.saved)
        assertEquals(joined, config.config.value)
    }

    // ── The two-witness absence verdict (capability `manage-membership`) ───────────────────────────────────
    //
    // ABSENT marks the event deleted, and reaching it needs a definitive `NotFound` AND the membership's own
    // persisted deadline to have passed. One witness is OFFLINE, so no backend fault can manufacture both: a zone-wide
    // misconfiguration that 404s every event would otherwise show every member's event as over at once.

    @Test
    fun `NotFound past the deadline is ABSENT and marks the event deleted - keeping the membership`() = runTest {
        val config = Membership(joined)
        assertEquals(
            RefreshOutcome.ABSENT,
            refresh(config, now = AFTER_DEADLINE).refresh(testEventId("E"), JoinLoad.NotFound),
        )
        assertEquals(joined.copy(deleted = true), config.config.value, "the app never leaves an event on its own")
        assertEquals(1, stopped, "every transfer for the event stops")
    }

    @Test
    fun `NotFound BEFORE the deadline is disbelieved`() = runTest {
        // The backend says the event is gone but our own clock says it cannot be. This is the case that
        // contains a systemic 404 fault, so it must resolve to "could not tell", never to a teardown.
        val config = Membership(joined)
        assertEquals(
            RefreshOutcome.INCONCLUSIVE,
            refresh(config, now = BEFORE_DEADLINE).refresh(testEventId("E"), JoinLoad.NotFound),
        )
        assertEquals(joined, config.config.value) // membership completely intact
        assertEquals(0, stopped)
    }

    @Test
    fun `an inconclusive fetch past the deadline is still inconclusive`() = runTest {
        // The deadline alone is not a witness: absence must be CONFIRMED, not merely plausible.
        val config = Membership(joined)
        assertEquals(
            RefreshOutcome.INCONCLUSIVE,
            refresh(config, now = AFTER_DEADLINE).refresh(testEventId("E"), JoinLoad.Failed),
        )
    }

    @Test
    fun `a NotFound for a different event is never ABSENT`() = runTest {
        // A result landing after a switch describes someone else's membership; acting on it would tear
        // down the CURRENT one.
        val config = Membership(joined)
        assertEquals(
            RefreshOutcome.INCONCLUSIVE,
            refresh(config, now = AFTER_DEADLINE).refresh(testEventId("OTHER"), JoinLoad.NotFound),
        )
    }

    @Test
    fun `no membership is never ABSENT`() = runTest {
        val config = Membership(null)
        assertEquals(
            RefreshOutcome.INCONCLUSIVE,
            refresh(config, now = AFTER_DEADLINE).refresh(testEventId("E"), JoinLoad.NotFound),
        )
    }

    // ── The end of an event's life (capability `event-lifetime`) ───────────────────────────────────────

    @Test
    fun `a COMPLETED event is marked deleted before its deadline and kept`() = runTest {
        val config = Membership(joined)
        val completed = JoinLoad.Found(joined.name, STARTS, ENDS, DELETES, EventCompletionState(completed = true))
        assertEquals(RefreshOutcome.COMPLETED, refresh(config).refresh(testEventId("E"), completed))
        assertEquals(joined.copy(deleted = true), config.config.value)
        assertEquals(1, stopped)
    }

    @Test
    fun `a deletion already marked is marked once`() = runTest {
        val config = Membership(joined.copy(deleted = true))
        val completed = JoinLoad.Found(joined.name, STARTS, ENDS, DELETES, EventCompletionState(completed = true))
        assertEquals(RefreshOutcome.COMPLETED, refresh(config).refresh(testEventId("E"), completed))
        assertNull(config.saved)
        assertEquals(0, stopped)
    }

    @Test
    fun `member counts and the open-until date follow the server`() = runTest {
        val config = Membership(joined)
        val served = JoinLoad.Found(
            joined.name,
            STARTS,
            ENDS,
            DELETES,
            EventCompletionState(completed = false, members = MemberCounts(active = 4, settled = 2, waiting = 1)),
            openUntil = OpenUntil(TimestampText.parse("2026-07-20T00:00:00Z")),
        )
        assertEquals(RefreshOutcome.REFRESHED, refresh(config).refresh(testEventId("E"), served))
        assertEquals(
            joined.copy(
                members = MemberCounts(4, 2, 1),
                openUntil = OpenUntil(TimestampText.parse("2026-07-20T00:00:00Z")),
            ),
            config.saved,
        )
    }

    @Test
    fun `an answer without an open-until date keeps the one known`() = runTest {
        val known = joined.copy(openUntil = OpenUntil(TimestampText.parse("2026-07-20T00:00:00Z")))
        val config = Membership(known)
        refresh(config).refresh(testEventId("E"), found(joined.name))
        assertNull(config.saved)
        refresh(
            config,
        ).refresh(
            testEventId("E"),
            found(joined.name).copy(openUntil = OpenUntil(TimestampText.parse("2026-07-20T00:00:00Z"))),
        )
        assertNull(config.saved, "an unchanged one saves nothing either")
    }

    @Test
    fun `unchanged member counts save nothing`() = runTest {
        val counted = joined.copy(members = MemberCounts(3, 1))
        val config = Membership(counted)
        refresh(config).refresh(
            testEventId("E"),
            JoinLoad.Found(counted.name, STARTS, ENDS, DELETES, EventCompletionState(false, MemberCounts(3, 1))),
        )
        assertNull(config.saved)
    }
}
