package app.snapsync.gallery

import platform.Foundation.NSDate
import platform.Foundation.NSISO8601DateFormatter

/**
 * The process's ISO-8601 formatter, built **once**: constructing an `NSISO8601DateFormatter` is not cheap (measured on
 * an SE2, 2026-09: per-call construction was most of a walk's CPU), and it is documented thread-safe, so one instance
 * serves every caller. Nothing outside this object can reach a setter.
 *
 * [internetDateTime] is a **default-constructed** formatter — `formatOptions` =
 * `NSISO8601DateFormatWithInternetDateTime`, the GMT time zone — which `Iso8601Test` pins.
 */
object Iso8601 {

    /** The default formatter (`…T…Z`, second precision, GMT) — what `NSISO8601DateFormatter()` builds. */
    val internetDateTime: NSISO8601DateFormatter = NSISO8601DateFormatter()

    /** Parse the default (second-precision) shape only; `null` for anything else. */
    fun parse(iso: String): NSDate? = internetDateTime.dateFromString(iso)
}
