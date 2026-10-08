package app.snapsync.model

import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What a `/device/state` document (the control channel's [UiState], `docs/testing.md`) may leave out, type by type
 * through the tree: a field with a default may be absent and reads as that default, every other field is required.
 * [UiStateSerializationTest] pins that every layer round-trips; this pins the shape each one is read with.
 */
class UiStateFieldContractTest {

    private val at = LocalDateTime(2026, 7, 6, 14, 0)

    private val membership = EventConfig(
        eventId = "11111111-1111-4111-8111-111111111111",
        name = "Anna's Birthday",
        minPhotoDate = captureCutoff("2026-07-06T14:32:11Z"),
        endsAt = eventEnd("2026-07-13T14:32:11Z"),
        maxPhotoDate = captureCeiling("2026-07-13T14:32:11Z"),
        deletesAt = deletesAt("2026-08-05T14:32:11Z"),
    )

    private val details = EventDetails(
        name = "Picnic",
        startsAt = eventStart("2026-07-06T00:00:00Z"),
        endsAt = eventEnd("2026-07-07T00:00:00Z"),
        deletesAt = deletesAt("2026-08-05T00:00:00Z"),
    )

    private val form = RangeForm(
        shareOn = false,
        receiveOn = false,
        saveToAlbum = false,
        albumKind = AlbumKind.FOLDER,
        preset = RangeChoice.CUSTOM,
        customFrom = at,
        customUntil = at,
    )

    private val range = ResolvedRange(
        windowStart = at,
        windowEnd = at,
        from = at,
        until = at,
        chosenFrom = captureCutoff("2026-07-06T14:00:00Z"),
        chosenUntil = captureCeiling("2026-07-06T14:00:00Z"),
        direction = Direction.UploadOnly,
        commitEnabled = true,
        nowAvailable = true,
        today = at.date,
        shareCount = ShareCount.Ready(3),
    )

    private val detailed = JoinPhase.Detailed(
        details,
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
            Layer.UpdateRequired("1.3", StoreLink("https://apps.apple.com/app/id1", StoreKind.APP_STORE)),
            optional = setOf("minimumVersion", "store"),
        )
        assertFieldContract(StoreLink.serializer(), StoreLink("https://play.google.com", StoreKind.GOOGLE_PLAY))
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
                form = form,
                notice = ScreenMessage.INVALID_LINK,
                asksAccessOnJoin = true,
                network = NetworkNotice.BLOCKED,
            ),
            optional = setOf("form", "notice", "asksAccessOnJoin", "network"),
        )
        assertFieldContract(
            Layer.Joined.serializer(),
            Layer.Joined(
                membership = membership,
                inviteUrl = "https://snapsync.app/e/1",
                health = SyncHealth.InSync,
                pendingSwitch = PendingSwitch("other", JoinPhase.Loading),
                canChoosePhotos = true,
                timing = EventTiming.Ended,
                counts = SyncCounts(DirectionCount.Off, DirectionCount.Progress(1, 2)),
                renameState = RenameState.Failed(ScreenMessage.RENAME_FAILED),
                surface = JoinedSurface.Reconfigure(form, range, saveFailed = true, askingToStopSharing = true),
                notice = ScreenMessage.RENAME_NAME_REFUSED,
                closed = true,
                waiting = MemberCounts(active = 3, settled = 1),
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
            JoinedSurface.Reconfigure(form, range, saveFailed = true, askingToStopSharing = true),
            optional = setOf("saveFailed", "askingToStopSharing"),
        )
        assertFieldContract(SyncCounts.serializer(), SyncCounts(DirectionCount.Progress(1, 2), DirectionCount.Off))
        assertFieldContract(DirectionCount.Progress.serializer(), DirectionCount.Progress(1, 2))
        assertFieldContract(MemberCounts.serializer(), MemberCounts(active = 3, settled = 1))
        assertFieldContract(EventTiming.Upcoming.serializer(), EventTiming.Upcoming(TimeLeft.Days(2)))
        assertFieldContract(EventTiming.Running.serializer(), EventTiming.Running(TimeLeft.Hours(5)))
        assertFieldContract(TimeLeft.Days.serializer(), TimeLeft.Days(2))
        assertFieldContract(TimeLeft.Hours.serializer(), TimeLeft.Hours(5))
        assertFieldContract(TimeLeft.Minutes.serializer(), TimeLeft.Minutes(7))
    }

    @Test
    fun `the join phase and the range require what was loaded and resolved`() {
        assertFieldContract(EventDetails.serializer(), details)
        assertFieldContract(JoinPhase.Detailed.serializer(), detailed, optional = setOf("refusal"))
        assertFieldContract(JoinStage.Unloaded.serializer(), JoinStage.Unloaded(JoinPhase.LoadFailed))
        assertFieldContract(JoinStage.Loaded.serializer(), JoinStage.Loaded(detailed, range))
        assertFieldContract(
            RangeForm.serializer(),
            form,
            optional = setOf("shareOn", "receiveOn", "saveToAlbum", "albumKind", "preset", "customFrom", "customUntil"),
        )
        assertFieldContract(ResolvedRange.serializer(), range, optional = setOf("shareCount"))
        assertFieldContract(ShareCount.Ready.serializer(), ShareCount.Ready(3))
    }

    @Test
    fun `a range resolved before its count arrives is counting`() {
        val resolved = ResolvedRange(
            windowStart = at,
            windowEnd = at,
            from = at,
            until = at,
            chosenFrom = captureCutoff("2026-07-06T14:00:00Z"),
            chosenUntil = captureCeiling("2026-07-06T14:00:00Z"),
            direction = Direction.Both,
            commitEnabled = true,
            nowAvailable = false,
            today = at.date,
        )
        assertEquals(ShareCount.Counting, resolved.shareCount)
    }

    @Test
    fun `a health case requires what it reports`() {
        assertFieldContract(SyncHealth.NeedsAccess.serializer(), SyncHealth.NeedsAccess(GalleryAccess.DENIED))
        assertFieldContract(SyncHealth.NoNetwork.serializer(), SyncHealth.NoNetwork(NetworkNotice.OFFLINE))
        assertFieldContract(
            SyncHealth.Unattested.serializer(),
            SyncHealth.Unattested(DeviceRefusal.APP_NOT_GENUINE),
            optional = setOf("refusal"),
        )
        assertFieldContract(
            SyncHealth.Syncing.serializer(),
            SyncHealth.Syncing(Arrow.PULSING, Arrow.STATIC, waitingForWifi = true),
            optional = setOf("waitingForWifi"),
        )
    }
}
