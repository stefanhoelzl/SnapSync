package app.snapsync.model

/**
 * The device-log line stamp for [epochMillis]: `yyyy-MM-dd HH:mm:ss.SSS +0000`, in UTC (capability
 * `diagnostic-logging`, "Log timestamps carry millisecond resolution").
 *
 * This is the EXACT text the iOS file writer used to build from `NSDate.description` (a fixed
 * `yyyy-MM-dd HH:mm:ss +0000`, UTC, seconds only) with the milliseconds spliced in ahead of the zone —
 * produced here arithmetically instead, because allocating an `NSDate` and formatting its description was
 * a measurable part of a per-line cost paid on every log line of every background wake. The date is the
 * proleptic-Gregorian civil date of the day number (Howard Hinnant's `civil_from_days`), which is what
 * Foundation's description prints for every date a device log can carry.
 *
 * Pure and platform-free so its identity is pinned by a JVM-run test against known instants (leap years,
 * the non-leap century, day/month/year rollover); the one check only a Foundation host can make — that
 * this text equals `NSDate.description` with the milliseconds spliced in — is `FileLogWriterTest` on iOS.
 *
 * ONE instant in, so the seconds and the milliseconds cannot come from two clock reads that straddle a
 * second boundary. Years are printed with at least four digits, as Foundation does for 1000–9999; nothing
 * outside that range reaches a device log.
 */
fun utcLogStamp(epochMillis: Long): String {
    val seconds = epochMillis.floorDiv(MILLIS_PER_SECOND)
    val millis = epochMillis.mod(MILLIS_PER_SECOND).toInt()
    val days = seconds.floorDiv(SECONDS_PER_DAY)
    val secondOfDay = seconds.mod(SECONDS_PER_DAY).toInt()

    // civil_from_days: shift the epoch to 0000-03-01 so the leap day is the LAST day of a (March-based) year.
    val z = days + DAYS_FROM_CIVIL_EPOCH_TO_UNIX_EPOCH
    val era = z.floorDiv(DAYS_PER_ERA)
    val dayOfEra = (z - era * DAYS_PER_ERA).toInt() // [0, 146096]
    val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365 // [0, 399]
    val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100) // [0, 365]
    val marchMonth = (5 * dayOfYear + 2) / 153 // [0, 11], March = 0
    val day = dayOfYear - (153 * marchMonth + 2) / 5 + 1
    val month = if (marchMonth < 10) marchMonth + 3 else marchMonth - 9
    val year = yearOfEra + era * YEARS_PER_ERA + if (month <= 2) 1 else 0

    return buildString(STAMP_LENGTH) {
        append(year.toString().padStart(4, '0'))
        append('-').pad2(month)
        append('-').pad2(day)
        append(' ').pad2(secondOfDay / 3600)
        append(':').pad2(secondOfDay / 60 % 60)
        append(':').pad2(secondOfDay % 60)
        append('.')
        if (millis < 100) append('0')
        if (millis < 10) append('0')
        append(millis)
        append(" +0000")
    }
}

private fun StringBuilder.pad2(value: Int): StringBuilder {
    if (value < 10) append('0')
    return append(value)
}

private const val MILLIS_PER_SECOND = 1000L
private const val SECONDS_PER_DAY = 86_400L
private const val DAYS_FROM_CIVIL_EPOCH_TO_UNIX_EPOCH = 719_468L
private const val DAYS_PER_ERA = 146_097L
private const val YEARS_PER_ERA = 400L
private const val STAMP_LENGTH = 29
