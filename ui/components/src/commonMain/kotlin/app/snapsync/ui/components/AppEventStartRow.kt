package app.snapsync.ui.components

import kotlinx.datetime.LocalDateTime


/**
 * The design system's human rendering of a wall-clock instant — `14 Jul 2026, 18:00`.
 *
 * Public because a screen sometimes needs to state a date in prose
 * (the Share section's "Shared from …" value), and a screen must never re-derive the app's date format.
 */
fun appDateTimeLabel(value: LocalDateTime): String = formatStart(value)

/**
 * The design system's human rendering of a DAY, with no time of day — `14 Jul 2026`.
 *
 * Public for the same reason as [appDateTimeLabel], and separate from it because some statements are
 * about a day rather than an instant: the join gate's retention line ("Shared photos are deleted on …",
 * capability `event-lifetime`) would read as false precision with a minute attached.
 */
fun appDateLabel(value: LocalDateTime): String =
    "${value.day} ${monthAbbrev(value.month.ordinal)} ${value.year}"

/** `14 Jul 2026, 18:00` — a human rendering of the start, in the device's local wall-clock terms. */
internal fun formatStart(value: LocalDateTime): String {
    fun p(n: Int) = n.toString().padStart(2, '0')
    return "${value.day} ${monthAbbrev(value.month.ordinal)} ${value.year}, " +
        "${p(value.hour)}:${p(value.minute)}"
}

/** `14 Jul, 18:00` — the shorter form the status line uses, where the year is noise. */
internal fun formatStartShort(value: LocalDateTime): String {
    fun p(n: Int) = n.toString().padStart(2, '0')
    return "${value.day} ${monthAbbrev(value.month.ordinal)}, ${p(value.hour)}:${p(value.minute)}"
}

private fun monthAbbrev(monthOrdinal: Int): String = listOf(
    "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
)[monthOrdinal]
