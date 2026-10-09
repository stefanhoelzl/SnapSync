package app.snapsync.presentation

import app.snapsync.model.AlbumKind
import app.snapsync.model.AlbumStyle
import app.snapsync.model.CreateForm
import app.snapsync.model.CreateStep
import app.snapsync.model.DeviceRefusal
import app.snapsync.model.EVENT_WINDOW_MAX_SECONDS
import app.snapsync.model.EventConfig
import app.snapsync.model.EventEnd
import app.snapsync.model.EventStart
import app.snapsync.model.JoinedEvent
import app.snapsync.model.Layer
import app.snapsync.model.RangeView
import app.snapsync.model.RefusalCause
import app.snapsync.model.StoreKind
import app.snapsync.model.StoreLink
import app.snapsync.model.StoreName
import app.snapsync.model.UiState
import app.snapsync.presentation.range.EventRange
import app.snapsync.presentation.range.RangeBounds
import app.snapsync.presentation.range.view
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.time.Duration.Companion.seconds

/**
 * The status container's state (capability `sync-status`): what the screen shows ([ui], display values only) and the
 * domain values behind it that the taps act on ([shown]) — both produced by ONE reduction pass, so a tap acts on
 * exactly what was displayed, and the screen never sees a domain value.
 */
data class ScreenModel(val ui: UiState, val shown: Shown)

/**
 * The domain values behind what the screen shows, which a tap acts on but no screen renders.
 *
 * [store] is the build's store page, while the update notice shows (capability `app-update-required`): the screen only
 * names the store, and "open the store" opens this. [range] is the range the showing participation surface resolves —
 * what its calendar opens on — and [countedRange] the same while the count row shows, whose photos it counts
 * (capability `join-event`); each `null` when no surface shows it.
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
        )
    }
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
