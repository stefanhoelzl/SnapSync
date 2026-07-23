package app.snapsync.feature.membership

import app.snapsync.model.CaptureCeiling
import app.snapsync.model.CaptureDate
import app.snapsync.model.EventEnd
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.deletesAt
import app.snapsync.model.eventEnd
import app.snapsync.model.eventStart
import app.snapsync.model.EventConfig
import app.snapsync.model.JoinLoad
import app.snapsync.ports.ConfigSource
import app.snapsync.ports.ConfigStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// Config seam + store as one fake: save writes the cell, exactly as the real Keychain adapter behaves.
private class FakeConfig(initial: EventConfig?) : ConfigSource, ConfigStore {
    private val flow = MutableStateFlow(initial)
    override val config: StateFlow<EventConfig?> = flow
    var saved: EventConfig? = null
    override suspend fun save(config: EventConfig) {
        saved = config
        flow.value = config
    }
    override suspend fun clear() {
        flow.value = null
    }
}

private val STARTS = eventStart("2026-07-06T14:32:11Z")
private val CUTOFF = captureCutoff("2026-07-06T14:32:11Z")
private val ENDS = eventEnd("2026-07-13T14:32:11Z")
private val CEILING = captureCeiling("2026-07-13T14:32:11Z")
private val DELETES = deletesAt("2026-08-05T14:32:11Z") // the event's retention deadline
private val BEFORE_DEADLINE = CaptureDate("2026-07-20T00:00:00Z")
private val AFTER_DEADLINE = CaptureDate("2026-08-06T00:00:00Z")

class MembershipRefreshTest {

    // A NON-legacy membership: it already carries the event window, so a details refresh only touches the
    // name (backfill is a no-op). This keeps the name-refresh assertions isolated from the backfill.
    private val joined = EventConfig(
        eventId = "E",
        name = "",
        minPhotoDate = CUTOFF,
        startsAt = STARTS,
        endsAt = ENDS,
        maxPhotoDate = CEILING,
        deletesAt = DELETES,
    )

    private fun found(name: String, endsAt: EventEnd = ENDS) =
        JoinLoad.Found(name, STARTS, endsAt, DELETES)

    /**
     * The rule under test, with "now" pinned. Defaults BEFORE the deadline (the disbelieving side).
     *
     * The teardown is the REAL [LeaveEvent] over the same fake config, so an ABSENT verdict is asserted by
     * its actual effect — the membership gone — rather than by a spy that could drift from it.
     */
    private fun TestScope.refresh(config: FakeConfig, now: CaptureDate = BEFORE_DEADLINE) =
        MembershipRefresh(
            configSource = config,
            store = config,
            now = { now },
            leaveEvent = LeaveEvent(
                config = config,
                configSource = config,
                stopUploads = {},
                notifyLeave = {},
                scope = this,
            ),
        )

    @Test
    fun `stores a changed name as the whole config with the cutoff preserved`() = runTest {
        val config = FakeConfig(joined)
        refresh(config).refresh("E", found("Anna's Birthday"))
        // The WHOLE config is saved with only `name` replaced — the cutoff (and every other
        // membership field) rides along untouched (capability `photo-selection-policy`).
        assertEquals(joined.copy(name = "Anna's Birthday"), config.saved)
    }

    @Test
    fun `an unchanged name saves nothing`() = runTest {
        val config = FakeConfig(joined.copy(name = "Anna's Birthday"))
        refresh(config).refresh("E", found("Anna's Birthday"))
        assertNull(config.saved)
    }

    @Test
    fun `a fetch resolving for a different event saves nothing`() = runTest {
        // A stale fetch landing after a switch must not resurrect the departed membership's name.
        val config = FakeConfig(joined)
        refresh(config).refresh("OTHER", found("Someone Else's Party"))
        assertNull(config.saved)
    }

    @Test
    fun `no membership saves nothing`() = runTest {
        val config = FakeConfig(null)
        refresh(config).refresh("E", found("Anna's Birthday"))
        assertNull(config.saved)
    }

    @Test
    fun `an inconclusive fetch stores nothing`() = runTest {
        // "Could not tell" — offline, transport, non-404 status, unparseable body — all arrive as Failed
        // and must change nothing at all.
        val config = FakeConfig(joined)
        assertEquals(RefreshOutcome.INCONCLUSIVE, refresh(config).refresh("E", JoinLoad.Failed))
        assertNull(config.saved)
    }

    @Test
    fun `a legacy config missing the window is backfilled from the fetched details`() = runTest {
        // capability `event-rejoin-reconciliation`: endsAt/maxPhotoDate/deletesAt all absent (joined
        // before the window and the deadline existed) → filled from the fetched details, in the SAME save
        // as any name refresh.
        val legacy = EventConfig(eventId = "E", name = "Anna's Birthday", minPhotoDate = CUTOFF, startsAt = STARTS, maxPhotoDate = CEILING)
        val config = FakeConfig(legacy)
        refresh(config).refresh("E", found("Anna's Birthday"))
        assertEquals(legacy.copy(endsAt = ENDS, maxPhotoDate = CEILING, deletesAt = DELETES), config.saved)
    }

    @Test
    fun `backfill and name refresh ride in one save`() = runTest {
        val legacy = EventConfig(eventId = "E", name = "", minPhotoDate = CUTOFF, startsAt = STARTS, maxPhotoDate = CEILING)
        val config = FakeConfig(legacy)
        refresh(config).refresh("E", found("Anna's Birthday"))
        assertEquals(
            legacy.copy(
                name = "Anna's Birthday",
                endsAt = ENDS,
                maxPhotoDate = CEILING,
                deletesAt = DELETES,
            ),
            config.saved,
        )
    }

    @Test
    fun `an already-set window is never overwritten by a backfill`() = runTest {
        // The member already chose a window; a later details fetch must not clobber their ceiling.
        val config = FakeConfig(joined.copy(name = "Anna's Birthday", maxPhotoDate = CaptureCeiling(STARTS.at)))
        refresh(config).refresh("E", found("Anna's Birthday", endsAt = ENDS))
        assertNull(config.saved) // name unchanged AND endsAt already present → nothing to write
    }

    @Test
    fun `fetchNeed is MISSING only for a nameless membership`() = runTest {
        val rule = refresh(FakeConfig(null))
        assertEquals(TitleNeed.MISSING, rule.fetchNeed(""))
        assertEquals(TitleNeed.PRESENT, rule.fetchNeed("Anna's Birthday"))
    }

    // ── The two-witness absence verdict (capability `leave-event`) ───────────────────────────────────
    //
    // ABSENT is the ONE destructive answer, and reaching it needs a definitive `NotFound` AND the
    // membership's own persisted deadline to have passed. One witness is OFFLINE, so no backend fault can
    // manufacture both: a zone-wide misconfiguration that 404s every event would otherwise destroy every
    // membership in the install base at once, unrecoverably — this config is the only record of the join
    // and the invite QR is derived from it.

    @Test
    fun `NotFound past the deadline is ABSENT and tears the membership down`() = runTest {
        val config = FakeConfig(joined)
        assertEquals(
            RefreshOutcome.ABSENT,
            refresh(config, now = AFTER_DEADLINE).refresh("E", JoinLoad.NotFound),
        )
        assertNull(config.config.value) // the real teardown ran: the device is back to unjoined
    }

    @Test
    fun `NotFound BEFORE the deadline is disbelieved`() = runTest {
        // The backend says the event is gone but our own clock says it cannot be. This is the case that
        // contains a systemic 404 fault, so it must resolve to "could not tell", never to a teardown.
        val config = FakeConfig(joined)
        assertEquals(
            RefreshOutcome.INCONCLUSIVE,
            refresh(config, now = BEFORE_DEADLINE).refresh("E", JoinLoad.NotFound),
        )
        assertEquals(joined, config.config.value) // membership completely intact
    }

    @Test
    fun `NotFound with no persisted deadline is disbelieved however late`() = runTest {
        // A membership stored before the field existed, or whose backfill has not landed. An absent
        // deadline reads as NEVER REACHED — the safe direction, mirroring the unbounded ceiling.
        val config = FakeConfig(joined.copy(deletesAt = null))
        assertEquals(
            RefreshOutcome.INCONCLUSIVE,
            refresh(config, now = AFTER_DEADLINE).refresh("E", JoinLoad.NotFound),
        )
    }

    @Test
    fun `an inconclusive fetch past the deadline is still inconclusive`() = runTest {
        // The deadline alone is not a witness: absence must be CONFIRMED, not merely plausible.
        val config = FakeConfig(joined)
        assertEquals(
            RefreshOutcome.INCONCLUSIVE,
            refresh(config, now = AFTER_DEADLINE).refresh("E", JoinLoad.Failed),
        )
    }

    @Test
    fun `a NotFound for a different event is never ABSENT`() = runTest {
        // A result landing after a switch describes someone else's membership; acting on it would tear
        // down the CURRENT one.
        val config = FakeConfig(joined)
        assertEquals(
            RefreshOutcome.INCONCLUSIVE,
            refresh(config, now = AFTER_DEADLINE).refresh("OTHER", JoinLoad.NotFound),
        )
    }

    @Test
    fun `no membership is never ABSENT`() = runTest {
        val config = FakeConfig(null)
        assertEquals(
            RefreshOutcome.INCONCLUSIVE,
            refresh(config, now = AFTER_DEADLINE).refresh("E", JoinLoad.NotFound),
        )
    }

    @Test
    fun `a legacy config missing the deadline is backfilled from the fetched details`() = runTest {
        val legacy = joined.copy(name = "Anna's Birthday", deletesAt = null)
        val config = FakeConfig(legacy)
        assertEquals(RefreshOutcome.REFRESHED, refresh(config).refresh("E", found("Anna's Birthday")))
        assertEquals(legacy.copy(deletesAt = DELETES), config.saved)
    }
}
