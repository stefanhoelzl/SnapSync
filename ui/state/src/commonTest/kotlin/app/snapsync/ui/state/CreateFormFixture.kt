package app.snapsync.ui.state

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime

/** A create form as a fresh draft shows it: no name, the start now, the end time blank. */
internal val FRESH_CREATE_FORM = CreateForm(
    name = "",
    draft = 0,
    picker = PickerView(
        from = LocalDateTime(2026, 7, 6, 14, 0),
        endDay = LocalDate(2026, 7, 6),
        untilHour = null,
        untilMinute = null,
        endPending = true,
        today = LocalDate(2026, 7, 6),
        firstDay = null,
        lastDay = LocalDate(2026, 8, 5),
        fromTimes = TimeSpan(LocalTime(0, 0), LocalTime(23, 59)),
        untilTimes = TimeSpan(LocalTime(14, 1), LocalTime(23, 59)),
        complete = false,
    ),
    nextStep = CreateStep.Name,
    canCreate = false,
    maxDays = 30,
)
