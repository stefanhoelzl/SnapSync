package app.snapsync.ui

import app.snapsync.model.DirectionCount
import app.snapsync.model.EventConfig
import app.snapsync.model.EventDetails
import app.snapsync.model.EventTiming
import app.snapsync.model.JoinPhase
import app.snapsync.model.JoinStage
import app.snapsync.model.JoinedSurface
import app.snapsync.model.Layer
import app.snapsync.model.PendingSwitch
import app.snapsync.model.RangeChoice
import app.snapsync.model.RangeForm
import app.snapsync.model.ReportDestination
import app.snapsync.model.ResolvedRange
import app.snapsync.model.ShareCount
import app.snapsync.model.StoreKind
import app.snapsync.model.SyncHealth
import app.snapsync.model.TimeLeft
import app.snapsync.model.UiState
import app.snapsync.model.captureCeiling
import app.snapsync.model.captureCutoff
import app.snapsync.model.deletesAt
import app.snapsync.model.eventEnd
import app.snapsync.model.eventStart
import app.snapsync.model.joinPhase
import app.snapsync.presentation.CoarseDuration
import app.snapsync.presentation.CutoffFormatter
import app.snapsync.ui.components.EventRange
import app.snapsync.ui.resources.Res
import app.snapsync.ui.resources.counts_not_sharing
import app.snapsync.ui.resources.counts_shared
import app.snapsync.ui.resources.counts_shared_progress
import app.snapsync.ui.resources.create_lasts
import app.snapsync.ui.resources.create_step_end_time
import app.snapsync.ui.resources.create_step_name
import app.snapsync.ui.resources.duration_days
import app.snapsync.ui.resources.duration_hours
import app.snapsync.ui.resources.duration_minutes
import app.snapsync.ui.resources.duration_minutes_short
import app.snapsync.ui.resources.duration_under_a_minute
import app.snapsync.ui.resources.duration_weeks
import app.snapsync.ui.resources.event_closed_body
import app.snapsync.ui.resources.event_closed_title
import app.snapsync.ui.resources.event_not_found_title
import app.snapsync.ui.resources.link_incomplete_body
import app.snapsync.ui.resources.link_incomplete_title
import app.snapsync.ui.resources.load_failed_title
import app.snapsync.ui.resources.ok
import app.snapsync.ui.resources.range_custom
import app.snapsync.ui.resources.range_from_now
import app.snapsync.ui.resources.range_whole_event
import app.snapsync.ui.resources.report_body_developer
import app.snapsync.ui.resources.report_body_device
import app.snapsync.ui.resources.report_send
import app.snapsync.ui.resources.retry
import app.snapsync.ui.resources.save
import app.snapsync.ui.resources.share_detail_count
import app.snapsync.ui.resources.share_detail_counting
import app.snapsync.ui.resources.store_app_store
import app.snapsync.ui.resources.store_google_play
import app.snapsync.ui.resources.switch_body
import app.snapsync.ui.resources.switch_confirm
import app.snapsync.ui.resources.timing_ended
import app.snapsync.ui.resources.timing_ends_in
import app.snapsync.ui.resources.timing_starts_in
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * The words the screens CHOOSE, checked where they are chosen: in plain mapping functions, with no Compose. Each
 * [Phrase] is compared as its [said] form — the resource, the count where it has one, and the values it carries — so a
 * test states the sentence the screen will say, not how it renders.
 */
class ScreenWordsTest {

    // ---- durations and time left ----------------------------------------------------------------------------------

    @Test
    fun `a create duration counts in the coarsest unit it names and under a minute says so`() {
        assertEquals(listOf(Res.plurals.duration_weeks, 2, 2), CoarseDuration.Weeks(2).phrase().said())
        assertEquals(listOf(Res.plurals.duration_days, 5, 5), CoarseDuration.Days(5).phrase().said())
        assertEquals(listOf(Res.plurals.duration_hours, 3, 3), CoarseDuration.Hours(3).phrase().said())
        assertEquals(listOf(Res.plurals.duration_minutes, 40, 40), CoarseDuration.Minutes(40).phrase().said())
        assertEquals(listOf(Res.string.duration_under_a_minute), CoarseDuration.UnderAMinute.phrase().said())
    }

    @Test
    fun `what is left of an event is said in days or hours or short minutes or under a minute`() {
        assertEquals(listOf(Res.plurals.duration_days, 2, 2), TimeLeft.Days(2).phrase().said())
        assertEquals(listOf(Res.plurals.duration_hours, 5, 5), TimeLeft.Hours(5).phrase().said())
        assertEquals(listOf(Res.plurals.duration_minutes_short, 12, 12), TimeLeft.Minutes(12).phrase().said())
        assertEquals(listOf(Res.string.duration_under_a_minute), TimeLeft.UnderAMinute.phrase().said())
    }

    @Test
    fun `the timing names the start ahead or the end ahead or that it ended — and nothing without a stored end`() {
        assertEquals(
            listOf(Res.string.timing_starts_in, listOf(Res.plurals.duration_days, 2, 2)),
            EventTiming.Upcoming(TimeLeft.Days(2)).phrase()?.said(),
        )
        assertEquals(
            listOf(Res.string.timing_ends_in, listOf(Res.string.duration_under_a_minute)),
            EventTiming.Running(TimeLeft.UnderAMinute).phrase()?.said(),
        )
        assertNull(EventTiming.Running(null).phrase())
        assertEquals(listOf(Res.string.timing_ended), EventTiming.Ended.phrase()?.said())
    }

    // ---- the counts line ------------------------------------------------------------------------------------------

    @Test
    fun `a direction says what an off direction says — its total once complete — and done of total while not`() {
        fun DirectionCount.shared() =
            label(Res.string.counts_shared, Res.string.counts_shared_progress, Res.string.counts_not_sharing).said()
        assertEquals(listOf(Res.string.counts_not_sharing), DirectionCount.Off.shared())
        assertEquals(listOf(Res.string.counts_shared, 15), DirectionCount.Progress(done = 15, total = 15).shared())
        assertEquals(
            listOf(Res.string.counts_shared_progress, 12, 15),
            DirectionCount.Progress(done = 12, total = 15).shared(),
        )
    }

    // ---- the range row --------------------------------------------------------------------------------------------

    @Test
    fun `the range row names the preset and the count once there is one — an unavailable count is left out`() {
        val whole = listOf(Res.string.range_whole_event)
        assertEquals(whole, shareDetail(RangeChoice.WHOLE_EVENT, ShareCount.Unavailable).said())
        assertEquals(
            listOf(Res.string.share_detail_counting, listOf(Res.string.range_from_now)),
            shareDetail(RangeChoice.FROM_NOW, ShareCount.Counting).said(),
        )
        assertEquals(
            listOf(Res.plurals.share_detail_count, 7, listOf(Res.string.range_custom), 7),
            shareDetail(RangeChoice.CUSTOM, ShareCount.Ready(7)).said(),
        )
    }

    // ---- the create screen ----------------------------------------------------------------------------------------

    @Test
    fun `the line above Create names the next missing step — then the event's duration`() {
        val from = LocalDateTime(2026, 7, 6, 10, 0)
        assertEquals(listOf(Res.string.create_step_name), nextStepLine(CreateStep.Name, from, cutoff).said())
        assertEquals(listOf(Res.string.create_step_end_time), nextStepLine(CreateStep.EndTime, from, cutoff).said())
        assertEquals(
            listOf(Res.string.create_lasts, listOf(Res.plurals.duration_days, 3, 3)),
            nextStepLine(CreateStep.Complete(LocalDateTime(2026, 7, 9, 10, 0)), from, cutoff).said(),
        )
    }

    @Test
    fun `Create is offered only for a named draft whose end follows its start within the event window`() {
        val from = LocalDateTime(2026, 7, 6, 12, 0)
        fun draft(name: String, endDay: LocalDate, until: LocalTime) =
            CreateDraft(name, EventRange(from, endDay, until, endPending = false))
        assertEquals(
            LocalDateTime(2026, 7, 6, 13, 0),
            creatableEnd(draft("Party", from.date, LocalTime(13, 0)), cutoff),
        )
        assertNull(creatableEnd(draft(" ", from.date, LocalTime(13, 0)), cutoff), "no name")
        assertNull(creatableEnd(CreateDraft("Party", EventRange(from)), cutoff), "no end")
        // What the picker cannot produce is still refused rather than submitted: an end before the start, or past the
        // event window (capability `create-event`).
        assertNull(creatableEnd(draft("Party", from.date, LocalTime(11, 0)), cutoff), "inverted")
        assertNull(creatableEnd(draft("Party", LocalDate(2026, 9, 6), LocalTime(13, 0)), cutoff), "too long")
    }

    @Test
    fun `the update notice's button names the store the build came through`() {
        assertEquals(Res.string.store_app_store, storeButtonLabel(StoreKind.APP_STORE))
        assertEquals(Res.string.store_google_play, storeButtonLabel(StoreKind.GOOGLE_PLAY))
    }

    // ---- the bug-report sheet -------------------------------------------------------------------------------------

    @Test
    fun `a report sent to the developer says Send — one kept on the phone says Save and where it stays`() {
        reportCopy(ReportDestination.DEVELOPER).let {
            assertEquals(Res.string.report_body_developer, it.body)
            assertEquals(Res.string.report_send, it.confirm)
        }
        reportCopy(ReportDestination.THIS_DEVICE).let {
            assertEquals(Res.string.report_body_device, it.body)
            assertEquals(Res.string.save, it.confirm)
        }
    }

    // ---- the switch dialog ----------------------------------------------------------------------------------------

    @Test
    fun `only a loaded switch asks to switch — naming the event it would join`() {
        val prompt = switchPrompt(joinPhase(JoinPhase.Detailed.Step.Ready, details))!!
        assertEquals(listOf(Res.string.switch_body, Res.string.switch_confirm), listOf(prompt.body, prompt.confirm))
        assertEquals(SwitchAct.SWITCH, prompt.act)
        assertEquals("Anna's Birthday", prompt.switchingTo)
        // A commit cannot fail while the previous event is still configured; a commit in flight shows nothing.
        assertNull(switchPrompt(joinPhase(JoinPhase.Detailed.Step.Committing, details)))
    }

    @Test
    fun `the walls only dismiss — each with the join screen's own words`() {
        for ((phase, title, body) in listOf(
            Triple(JoinPhase.NotFound, Res.string.event_not_found_title, null),
            Triple(JoinPhase.Closed, Res.string.event_closed_title, Res.string.event_closed_body),
            Triple(JoinPhase.WrongLink, Res.string.link_incomplete_title, Res.string.link_incomplete_body),
        )) {
            val prompt = switchPrompt(phase)!!
            assertEquals(title, prompt.title, "$phase")
            body?.let { assertEquals(it, prompt.body, "$phase") }
            assertEquals(Res.string.ok, prompt.confirm, "$phase")
            assertEquals(SwitchAct.DISMISS, prompt.act, "$phase")
            assertNull(prompt.switchingTo, "$phase")
        }
    }

    @Test
    fun `a failed load offers Retry and nothing shows while the details load`() {
        val prompt = switchPrompt(JoinPhase.LoadFailed)!!
        assertEquals(listOf(Res.string.load_failed_title, Res.string.retry), listOf(prompt.title, prompt.confirm))
        assertEquals(SwitchAct.RETRY, prompt.act)
        assertNull(switchPrompt(JoinPhase.Loading))
    }

    // ---- the report's screen label --------------------------------------------------------------------------------

    @Test
    fun `the report names the surface with the join phase where there is one and the screen-local surfaces`() {
        val joined = Layer.Joined(membership = membership, inviteUrl = "https://x/join#k", health = SyncHealth.InSync)
        val labels = listOf(
            Layer.JoiningEvent("E", JoinStage.Unloaded(JoinPhase.Loading)),
            joined,
            joined.copy(pendingSwitch = PendingSwitch("F", JoinPhase.LoadFailed)),
            joined.copy(surface = JoinedSurface.Reconfigure(RangeForm(), range)),
            Layer.CreateEvent(),
            Layer.CreatingEvent,
            Layer.UpdateRequired(),
        ).map { screenLabel(UiState(it)) }
        assertEquals(
            listOf(
                "JoiningEvent:Loading",
                "Joined",
                "Switch:LoadFailed",
                "Reconfigure",
                "CreateEvent",
                "CreatingEvent",
                "UpdateRequired",
            ),
            labels,
        )
    }

    // ---- fixtures -------------------------------------------------------------------------------------------------

    /** [this] as data: the resource, the count where it has one, and its values — a carried [Phrase] said the same way. */
    private fun Phrase.said(): List<Any> {
        val values = args.map { if (it is Phrase) it.said() else it }
        return when (this) {
            is Phrase.Of -> listOf(res) + values
            is Phrase.Counted -> listOf(res, count) + values
        }
    }

    private val cutoff = CutoffFormatter(now = { Instant.parse("2026-07-06T10:00:00Z") }, zone = TimeZone.UTC)

    private val details = EventDetails(
        "Anna's Birthday",
        eventStart("2026-07-06T00:00:00Z"),
        eventEnd("2026-07-13T00:00:00Z"),
        deletesAt("2026-08-05T00:00:00Z"),
    )

    private val membership = EventConfig(
        eventId = "11111111-1111-4111-8111-111111111111",
        name = "Anna's Birthday",
        minPhotoDate = captureCutoff("2026-07-06T00:00:00Z"),
        maxPhotoDate = captureCeiling("2026-07-13T00:00:00Z"),
        endsAt = eventEnd("2026-07-13T00:00:00Z"),
        deletesAt = deletesAt("2026-08-05T00:00:00Z"),
    )

    private val range = ResolvedRange(
        windowStart = LocalDateTime(2026, 7, 6, 0, 0),
        windowEnd = LocalDateTime(2026, 7, 13, 0, 0),
        from = LocalDateTime(2026, 7, 6, 0, 0),
        until = LocalDateTime(2026, 7, 13, 0, 0),
        chosenFrom = captureCutoff("2026-07-06T00:00:00Z"),
        chosenUntil = captureCeiling("2026-07-13T00:00:00Z"),
        direction = app.snapsync.model.Direction.Both,
        commitEnabled = true,
        nowAvailable = true,
        today = LocalDate(2026, 7, 6),
    )
}
