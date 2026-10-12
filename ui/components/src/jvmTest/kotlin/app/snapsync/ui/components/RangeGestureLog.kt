package app.snapsync.ui.components

import app.snapsync.ui.state.DaySpan
import app.snapsync.ui.state.PickerView
import app.snapsync.ui.state.TimeSpan
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/** Every time of day. */
internal val ALL_DAY = TimeSpan(LocalTime(0, 0), LocalTime(23, 59))

/**
 * A range calendar as the app would show it: the range so far, and what may be picked. A component test states it —
 * whether the rules would produce it is the app's own tests' question.
 */
@Suppress("LongParameterList")
internal fun picker(
    from: LocalDateTime,
    endDay: LocalDate = from.date,
    untilHour: Int? = null,
    untilMinute: Int? = null,
    endPending: Boolean = true,
    firstDay: LocalDate? = null,
    lastDay: LocalDate? = null,
    fromTimes: TimeSpan? = ALL_DAY,
    untilTimes: TimeSpan? = ALL_DAY,
    complete: Boolean = false,
    today: LocalDate = NOT_IN_MARCH,
    firstEndDay: LocalDate? = null,
    markedDays: DaySpan? = null,
) = PickerView(
    from = from,
    endDay = endDay,
    untilHour = untilHour,
    untilMinute = untilMinute,
    endPending = endPending,
    today = today,
    firstDay = firstDay,
    lastDay = lastDay,
    firstEndDay = firstEndDay,
    markedDays = markedDays,
    fromTimes = fromTimes,
    untilTimes = untilTimes,
    complete = complete,
)

/** The gestures a range calendar reported, in order, each as `<kind> <values>`. */
internal class GestureLog {
    val events = mutableListOf<String>()

    val gestures = RangeGestures(
        onDay = { events += "day $it" },
        onDragStart = { events += "start $it" },
        onDragEnd = { events += "end $it" },
        onSweep = { anchor, day -> events += "sweep $anchor $day" },
        onFromHour = { events += "fromHour $it" },
        onFromMinute = { events += "fromMinute $it" },
        onUntilHour = { events += "untilHour $it" },
        onUntilMinute = { events += "untilMinute $it" },
        onUntilMinuteDrag = { events += "untilMinuteDrag" },
    )

    /** The last gesture of [kind], or `null` when none was reported. */
    fun last(kind: String): String? = events.lastOrNull { it == kind || it.startsWith("$kind ") }

    /** Every gesture of [kind]. */
    fun all(kind: String): List<String> = events.filter { it == kind || it.startsWith("$kind ") }
}
