package app.snapsync.ui.components

import androidx.compose.runtime.Composable
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import app.snapsync.ui.components.resources.Res
import app.snapsync.ui.components.resources.date_span
import app.snapsync.ui.components.resources.date_time_span
import org.jetbrains.compose.resources.stringResource

/**
 * A capture-date **range** as one readable label — the participation section's single statement of what
 * will be shared.
 *
 * In the design system rather than beside the reduction because it reads no clock and no zone: it formats
 * two wall-clock values through [LocalDateFormats], exactly as [appDateLabel] does, and how a date READS is
 * the design system's business (`docs/architecture.md`). The reduction decides what the dates ARE.
 *
 * The shape adapts to what the range actually is, so the common cases read as a person would say them: a
 * single day with a time span, whole days as a day span, and anything else spelled out at both ends.
 */
@Composable
fun appRangeLabel(from: LocalDateTime, until: LocalDateTime): String {
    val dates = LocalDateFormats.current
    val sameDay = from.date == until.date
    val wholeDays = from.time == LocalTime(0, 0) && until.time == LocalTime(0, 0)
    return when {
        sameDay -> stringResource(
            Res.string.date_time_span,
            dates.format(from, "MMMd"),
            dates.format(from, "jm"),
            dates.format(until, "jm"),
        )
        wholeDays -> stringResource(
            Res.string.date_span,
            dates.format(from, if (from.year == until.year) "MMMd" else "yMMMd"),
            dates.format(until, "yMMMd"),
        )
        else -> stringResource(Res.string.date_span, dates.format(from, "MMMdjm"), dates.format(until, "MMMdjm"))
    }
}
