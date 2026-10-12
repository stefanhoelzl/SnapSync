package app.snapsync.presentation

import app.snapsync.model.AlbumKind
import app.snapsync.model.BuildLabel
import app.snapsync.model.CaptureDate
import app.snapsync.model.DeviceRefusal
import app.snapsync.model.EVENT_WINDOW_MAX_SECONDS
import app.snapsync.model.EventConfig
import app.snapsync.model.EventEnd
import app.snapsync.model.EventStart
import app.snapsync.model.ReportDestination
import app.snapsync.model.ReportOutcome
import app.snapsync.model.StoreKind
import app.snapsync.model.StoreLink
import app.snapsync.presentation.range.EventRange
import app.snapsync.presentation.range.RangeBounds
import app.snapsync.presentation.range.view
import app.snapsync.ui.state.AlbumStyle
import app.snapsync.ui.state.BuildLine
import app.snapsync.ui.state.CreateForm
import app.snapsync.ui.state.CreateStep
import app.snapsync.ui.state.JoinedEvent
import app.snapsync.ui.state.KeepOpenChoice
import app.snapsync.ui.state.KeepOpenSheet
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.RangeView
import app.snapsync.ui.state.RefusalCause
import app.snapsync.ui.state.ReportNotice
import app.snapsync.ui.state.ReportTarget
import app.snapsync.ui.state.StoreName
import app.snapsync.ui.state.UiState
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.time.Duration.Companion.seconds

/**
 * The status container's state: what the screen shows ([ui], display values only) and the
 * domain values behind it that the taps act on ([shown]) — both produced by ONE reduction pass, so a tap acts on
 * exactly what was displayed, and the screen never sees a domain value.
 */
data class ScreenModel(val ui: UiState, val shown: Shown)

/**
 * The domain values behind what the screen shows, which a tap acts on but no screen renders.
 *
 * [store] is the build's store page, while the update notice shows: the screen only
 * names the store, and "open the store" opens this. [range] is the range the showing participation surface resolves —
 * what its calendar opens on — and [countedRange] the same while the count row shows, whose photos it counts;
 * each `null` when no surface shows it.
 */
data class Shown(
    val store: StoreLink? = null,
    val range: ResolvedRange? = null,
    val countedRange: ResolvedRange? = null,
)

/** One reduction's layer, and the range its surface resolved — `null` when it shows none. */
internal class Reduced(val layer: Layer, val range: ResolvedRange? = null)

/**
 * The conversions from the domain into what the screen shows: every moment into the device's zone, through the one
 * [CutoffFormatter], and a form against an event window through the container's one resolution.
 */
internal class Display(
    private val dates: CutoffFormatter,
    val resolve: (RangeForm, EventStart, EventEnd) -> ResolvedRange,
) {
    /** The joined membership as the joined screen states it. */
    fun event(config: EventConfig): JoinedEvent = JoinedEvent(
        eventId = config.eventId,
        name = config.name,
        startsAt = dates.toLocal(config.startsAt),
        endsAt = dates.toLocal(config.endsAt),
        sharesFrom = dates.toLocal(config.minPhotoDate),
        sharesUntil = dates.toLocal(config.maxPhotoDate),
        shares = config.direction.includesUpload,
        receives = config.direction.includesDownload,
        savesToAlbum = config.saveToAlbum,
    )

    /** The device's date. */
    fun today(): LocalDate = dates.nowLocal().date

    /**
     * Until when guests can still join [config]'s event, for an event created after its end (its open-until date lies
     * past its end) while that date is still ahead of [now] and the event's photos are not deleted; `null` otherwise.
     */
    fun joinableUntil(config: EventConfig, now: CaptureDate): LocalDateTime? = config.openUntil
        ?.takeIf { !config.deleted && it.at > config.endsAt.at && it.at > now }
        ?.let(dates::toLocal)

    /** What a created event's range may be: anywhere in time, at most the event window long, its end time the host's. */
    val createBounds: RangeBounds = RangeBounds.lastingAtMost(dates::latestEnd)

    /**
     * The end a draft may be created with, or `null` while Create is not offered: only a complete draft has one. The
     * window checks restate what the calendar already cannot produce (an inverted or over-long range), so a regression
     * there is refused rather than submitted.
     */
    fun creatableEnd(draft: CreateDraft): LocalDateTime? {
        val until = draft.range.until?.takeIf { draft.name.isNotBlank() } ?: return null
        return until.takeIf { draft.range.from < it && dates.fitsEventWindow(draft.range.from, it) }
    }

    /** The create form as the screen shows it. */
    fun create(draft: CreateDraft): CreateForm {
        val until = draft.range.until
        return CreateForm(
            name = draft.name,
            draft = draft.session.epoch,
            picker = draft.range.view(createBounds, today()),
            // The next missing step, in the order the form asks for it — or, once there is none, how long it lasts.
            nextStep = when {
                draft.name.isBlank() -> CreateStep.Name
                until == null -> CreateStep.EndTime
                else -> CreateStep.Complete(dates.coarseDuration(draft.range.from, until))
            },
            canCreate = creatableEnd(draft) != null,
            maxDays = EVENT_WINDOW_MAX_SECONDS.seconds.inWholeDays.toInt(),
            keepOpen = if (draft.askingHowLong) keepOpen() else null,
        )
    }

    /** The back-dated sheet: each choice beside the day it keeps the event open until, from now. */
    private fun keepOpen(): KeepOpenSheet =
        KeepOpenSheet(KEEP_OPEN_DAYS.map { days -> KeepOpenChoice(days, dates.toLocal(dates.openFor(days)).date) })
}

/** A range calendar open over a participation surface: the range it shows, held to the surface's window. */
internal data class Picking(val range: EventRange, val windowStart: LocalDateTime, val windowEnd: LocalDateTime) {
    val bounds: RangeBounds get() = RangeBounds.within(windowStart, windowEnd)
}

/** A form and what it resolved to — with the calendar, while it is [picking] — as the participation surface shows them. */
internal fun RangeForm.view(range: ResolvedRange, picking: Picking? = null): RangeView = RangeView(
    shareOn = shareOn,
    receiveOn = receiveOn,
    saveToAlbum = saveToAlbum,
    albumStyle = albumKind.shown,
    preset = preset,
    from = range.from,
    until = range.until,
    windowStart = range.windowStart,
    windowEnd = range.windowEnd,
    nowAvailable = range.nowAvailable,
    today = range.today,
    commitEnabled = range.commitEnabled,
    shareCount = range.shareCount,
    picker = picking?.let { it.range.view(it.bounds, range.today) },
)

internal val AlbumKind.shown: AlbumStyle
    get() = when (this) {
        AlbumKind.COLLECTION -> AlbumStyle.COLLECTION
        AlbumKind.FOLDER -> AlbumStyle.FOLDER
    }

internal val StoreKind.shown: StoreName
    get() = when (this) {
        StoreKind.APP_STORE -> StoreName.APP_STORE
        StoreKind.GOOGLE_PLAY -> StoreName.GOOGLE_PLAY
    }

internal val DeviceRefusal.cause: RefusalCause
    get() = when (this) {
        DeviceRefusal.DEVICE_MODIFIED -> RefusalCause.DEVICE_MODIFIED
        DeviceRefusal.DEVICE_UNVERIFIABLE -> RefusalCause.DEVICE_UNVERIFIABLE
        DeviceRefusal.APP_NOT_GENUINE -> RefusalCause.APP_NOT_GENUINE
    }

/**
 * The container's display half, as a state of its own: read synchronously ([value]), and collected as distinct values —
 * so a reader of the screen sees exactly what the container last reduced, with no stage of its own between. [source] is
 * read at each use rather than once, because the container's state flow is the container's to replace (Orbit's test
 * harness does).
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
internal class Displayed(private val source: () -> StateFlow<ScreenModel>) : StateFlow<UiState> {
    override val value: UiState get() = source().value.ui

    override val replayCache: List<UiState> get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<UiState>): Nothing {
        var last: UiState? = null
        source().collect { model ->
            if (model.ui != last) {
                last = model.ui
                collector.emit(model.ui)
            }
        }
    }
}

internal val ReportDestination.shown: ReportTarget
    get() = when (this) {
        ReportDestination.DEVELOPER -> ReportTarget.DEVELOPER
        ReportDestination.THIS_DEVICE -> ReportTarget.THIS_DEVICE
    }

internal val ReportOutcome.shown: ReportNotice
    get() = when (this) {
        ReportOutcome.SENT -> ReportNotice.SENT
        ReportOutcome.SAVED -> ReportNotice.SAVED
        ReportOutcome.NOT_SENT -> ReportNotice.NOT_SENT
    }

internal val BuildLabel.shown: BuildLine get() = BuildLine(version, buildNumber)
