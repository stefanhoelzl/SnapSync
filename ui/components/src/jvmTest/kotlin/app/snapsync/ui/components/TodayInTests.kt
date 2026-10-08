package app.snapsync.ui.components

import kotlinx.datetime.LocalDate

/** A "today" outside March 2026, the month the calendar tests show, so no day of theirs is marked as today. */
internal val NOT_IN_MARCH = LocalDate(2026, 1, 1)
