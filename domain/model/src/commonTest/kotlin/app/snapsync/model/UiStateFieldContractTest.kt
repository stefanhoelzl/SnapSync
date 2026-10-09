package app.snapsync.model

import kotlinx.datetime.LocalDateTime
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
    )

    @Test
    fun `the state's every part but its layer may be absent`() {
        assertFieldContract(
            UiState.serializer(),
            UiState(
                layer = Layer.CreatingEvent,
                overlays = Overlays(menuOpen = true),
                reportDestination = ReportDestination.THIS_DEVICE,
                build = BuildLabel("1.2", "2140"),
                mobileData = MobileDataState(on = false, notSaved = true),
            ),
            optional = setOf("overlays", "reportDestination", "build", "mobileData"),
        )
        assertFieldContract(BuildLabel.serializer(), BuildLabel("1.2", "2140"))
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
                reportNotice = ReportOutcome.SAVED,
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
            Layer.CreateEvent(
                ScreenMessage.CREATE_FAILED,
                CreateDraftSession(activation = 2, epoch = 1),
                NetworkNotice.OFFLINE,
            ),
            optional = setOf("error", "draft", "network"),
        )
        assertFieldContract(
            CreateDraftSession.serializer(),
            CreateDraftSession(activation = 2, epoch = 1),
            optional = setOf("activation", "epoch"),
        )
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
                closed = true,
                waiting = Waiting(waitingFor = 2, active = 3),
            ),
            optional = setOf(
                "pendingSwitch", "canChoosePhotos", "timing", "counts", "renameState", "surface", "notice", "closed",
                "waiting",
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
        assertFieldContract(Waiting.serializer(), Waiting(waitingFor = 2, active = 3))
        assertFieldContract(JoinedEvent.serializer(), membership)
        assertFieldContract(EventTiming.Upcoming.serializer(), EventTiming.Upcoming(TimeLeft.Days(2)))
        assertFieldContract(EventTiming.Running.serializer(), EventTiming.Running(TimeLeft.Hours(5)))
        assertFieldContract(TimeLeft.Days.serializer(), TimeLeft.Days(2))
        assertFieldContract(TimeLeft.Hours.serializer(), TimeLeft.Hours(5))
        assertFieldContract(TimeLeft.Minutes.serializer(), TimeLeft.Minutes(7))
    }

    @Test
    fun `the join phase and the range require what was loaded and resolved`() {
        assertFieldContract(JoinPhase.Detailed.serializer(), detailed, optional = setOf("refusal"))
        assertFieldContract(JoinStage.Unloaded.serializer(), JoinStage.Unloaded(JoinPhase.LoadFailed))
        assertFieldContract(JoinStage.Loaded.serializer(), JoinStage.Loaded(detailed, range))
        assertFieldContract(RangeView.serializer(), range)
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
}
