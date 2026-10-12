package app.snapsync.ui.state

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlin.test.Test

/**
 * What a `/device/state` document (the control channel's [UiState], `docs/testing.md`) may leave out, type by type
 * through the tree: a field with a default may be absent and reads as that default, every other field is required.
 * [UiStateSerializationTest] pins that every layer round-trips; this pins the shape each one is read with.
 */
class UiStateFieldContractTest {

    private val at = LocalDateTime(2026, 7, 6, 14, 0)

    private val membership = JoinedEvent(
        eventId = "11111111-1111-4111-8111-111111111111",
        name = "Anna's Birthday",
        startsAt = at,
        endsAt = at,
        sharesFrom = at,
        sharesUntil = at,
        shares = true,
        receives = false,
        savesToAlbum = true,
    )

    private val range = RangeView(
        shareOn = false,
        receiveOn = false,
        saveToAlbum = false,
        albumStyle = AlbumStyle.FOLDER,
        preset = RangeChoice.CUSTOM,
        from = at,
        until = at,
        windowStart = at,
        windowEnd = at,
        nowAvailable = true,
        today = at.date,
        commitEnabled = true,
        shareCount = ShareCount.Ready(3),
    )

    private val detailed = JoinPhase.Detailed(
        "Picnic",
        JoinPhase.Detailed.Step.DeviceRefused,
        ScreenMessage.DEVICE_MODIFIED,
        members = 3,
    )

    @Test
    fun `the state's every part but its layer may be absent`() {
        assertFieldContract(
            UiState.serializer(),
            UiState(
                layer = Layer.CreatingEvent,
                overlays = Overlays(menuOpen = true),
                reportDestination = ReportTarget.THIS_DEVICE,
                build = BuildLine("1.2", "2140"),
                mobileData = MobileDataState(on = false, notSaved = true),
            ),
            optional = setOf("overlays", "reportDestination", "build", "mobileData"),
        )
        assertFieldContract(BuildLine.serializer(), BuildLine("1.2", "2140"))
        assertFieldContract(
            MobileDataState.serializer(),
            MobileDataState(on = false, notSaved = true),
            optional = setOf("on", "notSaved"),
        )
        assertFieldContract(
            Overlays.serializer(),
            Overlays(
                confirmingLeave = true,
                renaming = true,
                showingQr = true,
                reportingBug = true,
                reportSeed = ScreenMessage.DEVICE_UNVERIFIABLE,
                menuOpen = true,
                reportNotice = ReportNotice.SAVED,
            ),
            optional = setOf(
                "confirmingLeave",
                "renaming",
                "showingQr",
                "reportingBug",
                "reportSeed",
                "menuOpen",
                "reportNotice",
            ),
        )
    }

    @Test
    fun `a layer requires only what identifies it`() {
        assertFieldContract(
            Layer.UpdateRequired.serializer(),
            Layer.UpdateRequired("1.3", StoreName.APP_STORE),
            optional = setOf("minimumVersion", "store"),
        )
        assertFieldContract(
            Layer.CreateEvent.serializer(),
            Layer.CreateEvent(FRESH_CREATE_FORM, ScreenMessage.CREATE_FAILED, NetworkNotice.OFFLINE),
            optional = setOf("error", "network"),
        )
        assertFieldContract(
            CreateForm.serializer(),
            FRESH_CREATE_FORM.copy(keepOpen = KeepOpenSheet(listOf(KeepOpenChoice(7, LocalDate(2026, 7, 13))))),
            optional = setOf("keepOpen"),
        )
        assertFieldContract(
            KeepOpenSheet.serializer(),
            KeepOpenSheet(listOf(KeepOpenChoice(7, LocalDate(2026, 7, 13)))),
        )
        assertFieldContract(KeepOpenChoice.serializer(), KeepOpenChoice(7, LocalDate(2026, 7, 13)))
        assertFieldContract(
            PickerView.serializer(),
            FRESH_CREATE_FORM.picker.copy(
                untilHour = 18,
                untilMinute = 0,
                fromTimes = null,
                firstDay = LocalDate(2026, 7, 1),
            ),
        )
        assertFieldContract(TimeSpan.serializer(), TimeSpan(LocalTime(0, 0), LocalTime(23, 59)))
        assertFieldContract(CreateStep.Complete.serializer(), CreateStep.Complete(CoarseDuration.Weeks(2)))
        assertFieldContract(
            Layer.JoiningEvent.serializer(),
            Layer.JoiningEvent(
                eventId = "event",
                stage = JoinStage.Loaded(detailed, range),
                notice = ScreenMessage.INVALID_LINK,
                asksAccessOnJoin = true,
                network = NetworkNotice.BLOCKED,
            ),
            optional = setOf("notice", "asksAccessOnJoin", "network"),
        )
        assertFieldContract(
            Layer.Joined.serializer(),
            Layer.Joined(
                membership = membership,
                inviteUrl = "https://snapsync.app/e/1",
                health = SyncHealth.InSync,
                today = at.date,
                pendingSwitch = PendingSwitch("other", JoinPhase.Loading),
                canChoosePhotos = true,
                timing = EventTiming.Ended,
                counts = SyncCounts(DirectionCount.Off, DirectionCount.Progress(1, 2)),
                renameState = RenameState.Failed(ScreenMessage.RENAME_FAILED),
                surface = JoinedSurface.Reconfigure(range, saveFailed = true, askingToStopSharing = true),
                notice = ScreenMessage.RENAME_NAME_REFUSED,
                deleted = true,
                members = 4,
                sharingNote = SharingNote.WaitingFor(2),
                joinableUntil = at,
            ),
            optional = setOf(
                "pendingSwitch", "canChoosePhotos", "timing", "counts", "renameState", "surface", "notice", "deleted",
                "members", "sharingNote", "joinableUntil",
            ),
        )
    }

    @Test
    fun `the joined layer's parts require what they show`() {
        assertFieldContract(PendingSwitch.serializer(), PendingSwitch("other", detailed))
        assertFieldContract(RenameState.Failed.serializer(), RenameState.Failed(ScreenMessage.RENAME_FAILED))
        assertFieldContract(
            JoinedSurface.Reconfigure.serializer(),
            JoinedSurface.Reconfigure(range, saveFailed = true, askingToStopSharing = true),
            optional = setOf("saveFailed", "askingToStopSharing"),
        )
        assertFieldContract(SyncCounts.serializer(), SyncCounts(DirectionCount.Progress(1, 2), DirectionCount.Off))
        assertFieldContract(DirectionCount.Progress.serializer(), DirectionCount.Progress(1, 2))
        assertFieldContract(SharingNote.WaitingFor.serializer(), SharingNote.WaitingFor(2))
        assertFieldContract(JoinedEvent.serializer(), membership)
        assertFieldContract(EventTiming.Upcoming.serializer(), EventTiming.Upcoming(TimeLeft.Days(2)))
        assertFieldContract(EventTiming.Running.serializer(), EventTiming.Running(TimeLeft.Hours(5)))
        assertFieldContract(TimeLeft.Days.serializer(), TimeLeft.Days(2))
        assertFieldContract(TimeLeft.Hours.serializer(), TimeLeft.Hours(5))
        assertFieldContract(TimeLeft.Minutes.serializer(), TimeLeft.Minutes(7))
    }

    @Test
    fun `the join phase and the range require what was loaded and resolved`() {
        assertFieldContract(JoinPhase.Detailed.serializer(), detailed, optional = setOf("refusal", "members"))
        assertFieldContract(JoinStage.Unloaded.serializer(), JoinStage.Unloaded(JoinPhase.LoadFailed))
        assertFieldContract(JoinStage.Loaded.serializer(), JoinStage.Loaded(detailed, range))
        assertFieldContract(
            RangeView.serializer(),
            range.copy(picker = FRESH_CREATE_FORM.picker),
            optional = setOf("picker"),
        )
        assertFieldContract(ShareCount.Ready.serializer(), ShareCount.Ready(3))
    }

    @Test
    fun `a health case requires what it reports`() {
        assertFieldContract(SyncHealth.NeedsAccess.serializer(), SyncHealth.NeedsAccess(canAsk = false))
        assertFieldContract(SyncHealth.NoNetwork.serializer(), SyncHealth.NoNetwork(NetworkNotice.OFFLINE))
        assertFieldContract(
            SyncHealth.Unattested.serializer(),
            SyncHealth.Unattested(RefusalCause.APP_NOT_GENUINE),
            optional = setOf("refusal"),
        )
        assertFieldContract(
            SyncHealth.Syncing.serializer(),
            SyncHealth.Syncing(Arrow.PULSING, Arrow.STATIC, waitingForWifi = true),
            optional = setOf("waitingForWifi"),
        )
    }

    @Test
    fun `every duration a form states requires its count`() {
        assertFieldContract(CoarseDuration.Weeks.serializer(), CoarseDuration.Weeks(2))
        assertFieldContract(CoarseDuration.Days.serializer(), CoarseDuration.Days(3))
        assertFieldContract(CoarseDuration.Hours.serializer(), CoarseDuration.Hours(4))
        assertFieldContract(CoarseDuration.Minutes.serializer(), CoarseDuration.Minutes(5))
    }
}

/** What a calendar's own values say (capability `create-event`): its end, once set, and the times a span admits. */
class PickerViewTest {

    @Test
    fun `the end is set only once both its hour and its minute are`() {
        val blank = FRESH_CREATE_FORM.picker
        kotlin.test.assertNull(blank.until)
        kotlin.test.assertNull(blank.copy(untilHour = 13).until)
        kotlin.test.assertNull(blank.copy(untilMinute = 30).until)
        kotlin.test.assertEquals(
            kotlinx.datetime.LocalDateTime(2026, 7, 6, 13, 30),
            blank.copy(untilHour = 13, untilMinute = 30).until,
        )
    }

    @Test
    fun `a span admits the times from its first to its last both included`() {
        val span = TimeSpan(LocalTime(9, 0), LocalTime(17, 30))
        kotlin.test.assertEquals(
            listOf(false, true, true, true, false),
            listOf(
                LocalTime(8, 59),
                LocalTime(9, 0),
                LocalTime(12, 0),
                LocalTime(17, 30),
                LocalTime(17, 31),
            ).map { it in span },
        )
    }
}
