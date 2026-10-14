package app.snapsync.ui

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
import app.snapsync.ui.resources.load_failed_body
import app.snapsync.ui.resources.load_failed_body_updating
import app.snapsync.ui.resources.load_failed_title
import app.snapsync.ui.resources.message_event_over
import app.snapsync.ui.resources.message_rename_trouble
import app.snapsync.ui.resources.message_rename_unreachable
import app.snapsync.ui.resources.message_rename_updating
import app.snapsync.ui.resources.message_service_trouble
import app.snapsync.ui.resources.message_service_unreachable
import app.snapsync.ui.resources.message_service_updating
import app.snapsync.ui.resources.ok
import app.snapsync.ui.resources.report_body_developer
import app.snapsync.ui.resources.report_body_device
import app.snapsync.ui.resources.report_send
import app.snapsync.ui.resources.retry
import app.snapsync.ui.resources.save
import app.snapsync.ui.resources.share_detail_count
import app.snapsync.ui.resources.share_detail_counting
import app.snapsync.ui.resources.share_outside_after
import app.snapsync.ui.resources.share_outside_before
import app.snapsync.ui.resources.share_outside_both
import app.snapsync.ui.resources.store_app_store
import app.snapsync.ui.resources.store_google_play
import app.snapsync.ui.resources.switch_body
import app.snapsync.ui.resources.switch_confirm
import app.snapsync.ui.resources.timing_ended
import app.snapsync.ui.resources.timing_ends_in
import app.snapsync.ui.resources.timing_starts_in
import app.snapsync.ui.state.CoarseDuration
import app.snapsync.ui.state.CreateStep
import app.snapsync.ui.state.DirectionCount
import app.snapsync.ui.state.EventTiming
import app.snapsync.ui.state.JoinPhase
import app.snapsync.ui.state.JoinStage
import app.snapsync.ui.state.JoinedSurface
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.OutsideReach
import app.snapsync.ui.state.PendingSwitch
import app.snapsync.ui.state.ReportTarget
import app.snapsync.ui.state.ScreenMessage
import app.snapsync.ui.state.ServiceNotice
import app.snapsync.ui.state.ShareCount
import app.snapsync.ui.state.StoreName
import app.snapsync.ui.state.SyncHealth
import app.snapsync.ui.state.TimeLeft
import app.snapsync.ui.state.UiState
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The words the screens CHOOSE, checked where they are chosen: in plain mapping functions, with no Compose. Each
 * [Phrase] is compared as its [said] form — the resource, the count where it has one, and the values it carries — so a
 * test states the sentence the screen will say, not how it renders.
 */
class ScreenWordsTest {

    // ---- a failing service ---------------------------------------------------------------------------------------

    @Test
    fun `a failing service is told in its own words, the rename dialog saying what failed`() {
        assertEquals(Res.string.message_service_unreachable, ScreenMessage.SERVICE_UNREACHABLE.resource())
        assertEquals(Res.string.message_service_trouble, ScreenMessage.SERVICE_TROUBLE.resource())
        assertEquals(Res.string.message_service_updating, ScreenMessage.SERVICE_UPDATING.resource())
        assertEquals(Res.string.message_event_over, ScreenMessage.EVENT_OVER.resource())
        assertEquals(Res.string.message_rename_unreachable, ScreenMessage.SERVICE_UNREACHABLE.renameResource())
        assertEquals(Res.string.message_rename_trouble, ScreenMessage.SERVICE_TROUBLE.renameResource())
        assertEquals(Res.string.message_rename_updating, ScreenMessage.SERVICE_UPDATING.renameResource())
        assertEquals(
            Res.string.message_event_over,
            ScreenMessage.EVENT_OVER.renameResource(),
            "anything else as anywhere",
        )
        assertEquals(
            listOf(
                Res.string.message_service_unreachable,
                Res.string.message_service_trouble,
                Res.string.message_service_updating,
            ),
            ServiceNotice.entries.map { it.resource() },
        )
    }

    @Test
    fun `a failed load says which way the service failed, and an update that it loads by itself`() {
        assertEquals(Res.string.load_failed_body, loadFailedBody(null))
        assertEquals(Res.string.message_service_trouble, loadFailedBody(ServiceNotice.TROUBLE))
        assertEquals(Res.string.load_failed_body_updating, loadFailedBody(ServiceNotice.UPDATING))
    }

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
    fun `the range row says the count once there is one — an unavailable count is no line`() {
        // The preset is no longer named here: the selected chip above says it (decision record
        // `flexible-share-range`, D3).
        assertNull(shareDetail(ShareCount.Unavailable))
        assertEquals(listOf(Res.string.share_detail_counting), shareDetail(ShareCount.Counting)?.said())
        assertEquals(listOf(Res.plurals.share_detail_count, 7, 7), shareDetail(ShareCount.Ready(7))?.said())
    }

    @Test
    fun `the outside note names the side the range reaches past`() {
        assertEquals(Res.string.share_outside_before, outsideNote(OutsideReach.BEFORE))
        assertEquals(Res.string.share_outside_after, outsideNote(OutsideReach.AFTER))
        assertEquals(Res.string.share_outside_both, outsideNote(OutsideReach.BOTH))
    }

    // ---- the create screen ----------------------------------------------------------------------------------------

    @Test
    fun `the line above Create names the next missing step — then the event's duration`() {
        assertEquals(listOf(Res.string.create_step_name), nextStepLine(CreateStep.Name).said())
        assertEquals(listOf(Res.string.create_step_end_time), nextStepLine(CreateStep.EndTime).said())
        assertEquals(
            listOf(Res.string.create_lasts, listOf(Res.plurals.duration_days, 3, 3)),
            nextStepLine(CreateStep.Complete(CoarseDuration.Days(3))).said(),
        )
    }

    @Test
    fun `the update notice's button names the store the build came through`() {
        assertEquals(Res.string.store_app_store, storeButtonLabel(StoreName.APP_STORE))
        assertEquals(Res.string.store_google_play, storeButtonLabel(StoreName.GOOGLE_PLAY))
    }

    // ---- the bug-report sheet -------------------------------------------------------------------------------------

    @Test
    fun `a report sent to the developer says Send — one kept on the phone says Save and where it stays`() {
        reportCopy(ReportTarget.DEVELOPER).let {
            assertEquals(Res.string.report_body_developer, it.body)
            assertEquals(Res.string.report_send, it.confirm)
        }
        reportCopy(ReportTarget.THIS_DEVICE).let {
            assertEquals(Res.string.report_body_device, it.body)
            assertEquals(Res.string.save, it.confirm)
        }
    }

    // ---- the switch dialog ----------------------------------------------------------------------------------------

    @Test
    fun `only a loaded switch asks to switch — naming the event it would join`() {
        val prompt = switchPrompt(JoinPhase.Detailed("Anna's Birthday", JoinPhase.Detailed.Step.Ready))!!
        assertEquals(listOf(Res.string.switch_body, Res.string.switch_confirm), listOf(prompt.body, prompt.confirm))
        assertEquals(SwitchAct.SWITCH, prompt.act)
        assertEquals("Anna's Birthday", prompt.switchingTo)
        // A commit cannot fail while the previous event is still configured; a commit in flight shows nothing.
        assertNull(switchPrompt(JoinPhase.Detailed("Anna's Birthday", JoinPhase.Detailed.Step.Committing)))
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
        val joined = Layer.Joined(
            membership = membership,
            inviteUrl = "https://x/join#k",
            health = SyncHealth.InSync,
            today = LocalDate(2026, 7, 6),
        )
        val labels = listOf(
            Layer.JoiningEvent("E", JoinStage.Unloaded(JoinPhase.Loading)),
            joined,
            joined.copy(pendingSwitch = PendingSwitch("F", JoinPhase.LoadFailed)),
            joined.copy(surface = JoinedSurface.Reconfigure(range)),
            createLayer(),
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

    private val membership = joinedEvent(
        name = "Anna's Birthday",
        startsAt = LocalDateTime(2026, 7, 6, 0, 0),
        endsAt = LocalDateTime(2026, 7, 13, 0, 0),
    )

    private val range = Choices().over(
        LocalDateTime(2026, 7, 6, 0, 0),
        LocalDateTime(2026, 7, 13, 0, 0),
        now = LocalDateTime(2026, 7, 6, 0, 0),
    )
}
