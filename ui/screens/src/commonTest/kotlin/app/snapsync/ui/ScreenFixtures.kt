package app.snapsync.ui

import app.snapsync.ui.state.AlbumStyle
import app.snapsync.ui.state.CreateForm
import app.snapsync.ui.state.CreateStep
import app.snapsync.ui.state.JoinedEvent
import app.snapsync.ui.state.Layer
import app.snapsync.ui.state.NetworkNotice
import app.snapsync.ui.state.PickerView
import app.snapsync.ui.state.RangeChoice
import app.snapsync.ui.state.RangeView
import app.snapsync.ui.state.ScreenMessage
import app.snapsync.ui.state.ShareCount
import app.snapsync.ui.state.TimeSpan
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/**
 * What a member chose on a participation surface — a screen test's input to [over], which states the range the
 * reduction would show for it. Stated, never re-derived: `RangeResolutionTest` owns whether the rules are right, and a
 * screen test asserts what is drawn from the answer.
 */
internal data class Choices(
    val shareOn: Boolean = true,
    val receiveOn: Boolean = true,
    val saveToAlbum: Boolean = true,
    val albumStyle: AlbumStyle = AlbumStyle.COLLECTION,
    val preset: RangeChoice = RangeChoice.WHOLE_EVENT,
    val customFrom: LocalDateTime? = null,
    val customUntil: LocalDateTime? = null,
) {
    /** These choices over the window `[start, end]`, at [now]. */
    fun over(
        start: LocalDateTime,
        end: LocalDateTime,
        now: LocalDateTime,
        count: ShareCount = ShareCount.Unavailable,
        today: LocalDate = now.date,
    ): RangeView = RangeView(
        shareOn = shareOn,
        receiveOn = receiveOn,
        saveToAlbum = saveToAlbum,
        albumStyle = albumStyle,
        preset = preset,
        from = when (preset) {
            RangeChoice.WHOLE_EVENT -> start
            RangeChoice.FROM_NOW -> now
            RangeChoice.CUSTOM -> customFrom ?: start
        },
        until = if (preset == RangeChoice.CUSTOM) customUntil ?: end else end,
        windowStart = start,
        windowEnd = end,
        nowAvailable = now in start..end,
        today = today,
        commitEnabled = shareOn || receiveOn,
        shareCount = count,
    )
}

/** A membership as the joined screen states it; its own range is the event's unless a test says otherwise. */
internal fun joinedEvent(
    name: String,
    startsAt: LocalDateTime,
    endsAt: LocalDateTime,
    eventId: String = "11111111-1111-4111-8111-111111111111",
    sharesFrom: LocalDateTime = startsAt,
    sharesUntil: LocalDateTime = endsAt,
    shares: Boolean = true,
    receives: Boolean = true,
    savesToAlbum: Boolean = false,
) = JoinedEvent(eventId, name, startsAt, endsAt, sharesFrom, sharesUntil, shares, receives, savesToAlbum)

/** A create form as a fresh draft shows it at 12:00 on Monday 6 July 2026: no name, the end time blank. */
internal val FRESH_CREATE_FORM = CreateForm(
    name = "",
    draft = 0,
    picker = PickerView(
        from = LocalDateTime(2026, 7, 6, 12, 0),
        endDay = LocalDate(2026, 7, 6),
        untilHour = null,
        untilMinute = null,
        endPending = true,
        today = LocalDate(2026, 7, 6),
        firstDay = null,
        lastDay = LocalDate(2026, 8, 5),
        fromTimes = TimeSpan(LocalTime(0, 0), LocalTime(23, 59)),
        untilTimes = TimeSpan(LocalTime(12, 1), LocalTime(23, 59)),
        complete = false,
    ),
    nextStep = CreateStep.Name,
    canCreate = false,
    maxDays = 30,
)

/** The create layer over a fresh draft — what a test about everything but the form shows. */
internal fun createLayer(error: ScreenMessage? = null, network: NetworkNotice? = null) =
    Layer.CreateEvent(FRESH_CREATE_FORM, error, network)

/** The range calendar as the app opens it over a participation surface: on [from]..[until], complete, held to the window. */
internal fun calendarOver(
    from: LocalDateTime,
    until: LocalDateTime,
    windowStart: LocalDateTime,
    windowEnd: LocalDateTime,
) =
    PickerView(
        from = from,
        endDay = until.date,
        untilHour = until.hour,
        untilMinute = until.minute,
        endPending = false,
        today = LocalDate(2026, 7, 6),
        firstDay = windowStart.date,
        lastDay = windowEnd.date,
        fromTimes = TimeSpan(LocalTime(0, 0), LocalTime(23, 59)),
        untilTimes = TimeSpan(LocalTime(0, 0), LocalTime(23, 59)),
        complete = true,
    )
