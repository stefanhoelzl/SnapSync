package app.snapsync.presentation

import app.snapsync.model.CaptureDate
import app.snapsync.model.DeviceRefusal
import app.snapsync.model.EventEnd
import app.snapsync.model.EventStart
import app.snapsync.model.LINK_ORIGIN
import app.snapsync.model.NetworkAccess
import app.snapsync.model.iso
import app.snapsync.ui.state.AppLink
import app.snapsync.ui.state.EventTiming
import app.snapsync.ui.state.NetworkNotice
import app.snapsync.ui.state.ScreenMessage
import app.snapsync.ui.state.TimeLeft
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

// The domain's facts in the words the screen states them: each a pure mapping from a
// domain value into the screen's vocabulary, so the screen never sees the domain.

/** What the member is told about [this] network access, or `null` when there is nothing to tell. */
internal val NetworkAccess.notice: NetworkNotice?
    get() = when (this) {
        is NetworkAccess.Online -> null
        NetworkAccess.Offline -> NetworkNotice.OFFLINE
        NetworkAccess.Blocked -> NetworkNotice.BLOCKED
    }

/** What the screen says for [this] refusal. */
val DeviceRefusal.message: ScreenMessage
    get() = when (this) {
        DeviceRefusal.DEVICE_MODIFIED -> ScreenMessage.DEVICE_MODIFIED
        DeviceRefusal.DEVICE_UNVERIFIABLE -> ScreenMessage.DEVICE_UNVERIFIABLE
        DeviceRefusal.APP_NOT_GENUINE -> ScreenMessage.APP_NOT_GENUINE
    }

/** The page [this] link opens: a page of the invite link's own site. */
val AppLink.url: String
    get() = when (this) {
        AppLink.WEBSITE -> LINK_ORIGIN
        AppLink.PRIVACY_POLICY -> "${LINK_ORIGIN}/#privacy"
    }

/** [span] floored to its largest whole unit. */
internal fun timeLeftOf(span: Duration): TimeLeft = when {
    span >= 1.days -> TimeLeft.Days(span.inWholeDays.toInt())
    span >= 1.hours -> TimeLeft.Hours(span.inWholeHours.toInt())
    span >= 1.minutes -> TimeLeft.Minutes(span.inWholeMinutes.toInt())
    else -> TimeLeft.UnderAMinute
}

/**
 * The timing of an event bounded by [startsAt] and [endsAt] at [now]. The start is inclusive (the event has
 * begun AT its start) and so is the end: an event is [EventTiming.Ended] only once `now` is past [endsAt],
 * the same comparison the ended marker always made.
 */
fun eventTiming(startsAt: EventStart, endsAt: EventEnd, now: CaptureDate): EventTiming {
    val at = Instant.parse(now.iso)
    return when {
        startsAt.at > now -> EventTiming.Upcoming(timeLeftOf(Instant.parse(startsAt.at.iso) - at))
        endsAt.at < now -> EventTiming.Ended
        else -> EventTiming.Running(timeLeftOf(Instant.parse(endsAt.at.iso) - at))
    }
}
