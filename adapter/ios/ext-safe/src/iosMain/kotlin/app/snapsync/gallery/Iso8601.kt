package app.snapsync.gallery

import platform.Foundation.NSDate
import platform.Foundation.NSISO8601DateFormatWithFractionalSeconds
import platform.Foundation.NSISO8601DateFormatWithInternetDateTime
import platform.Foundation.NSISO8601DateFormatter

/**
 * The process's ISO-8601 formatters, built **once** and shared by every PhotoKit-facing adapter.
 *
 * **Why shared.** Constructing an `NSISO8601DateFormatter` is not cheap, and the walk used to construct one
 * per asset to stamp its `creationDate`: measured on an SE2 (2026-09), that construction was 65–77 % of the
 * candidate loop, and reusing one instance made the loop 3.4× faster. It matters most in a background wake,
 * where the kernel's `darwinbg` clamp multiplies every CPU-bound millisecond (the same 754-candidate walk took
 * 107 ms in the foreground and 981 ms clamped).
 *
 * **Why sharing is safe.** `NSISO8601DateFormatter` is documented thread-safe, so one instance may serve the
 * walk, the resolve, the importer and the album lookup concurrently. Neither instance is ever mutated after
 * it is built here — nothing outside this object can reach a setter.
 *
 * **Why the output is byte-identical.** [internetDateTime] is a **default-constructed** formatter, exactly what
 * every call site constructed before: `formatOptions` = `NSISO8601DateFormatWithInternetDateTime` and the GMT
 * time zone, both Foundation's defaults. [withFractionalSeconds] carries the one non-default option set any
 * call site used (`InternetDateTime | FractionalSeconds`). `Iso8601Test` pins both against freshly built
 * formatters, so a drift fails the simulator suite rather than reaching a capture date on the wire.
 */
object Iso8601 {

    /** The default formatter (`…T…Z`, second precision, GMT) — what `NSISO8601DateFormatter()` builds. */
    val internetDateTime: NSISO8601DateFormatter = NSISO8601DateFormatter()

    /** The same shape, also accepting/emitting a `.sss` fraction. Used only to parse. */
    val withFractionalSeconds: NSISO8601DateFormatter = NSISO8601DateFormatter().apply {
        formatOptions = NSISO8601DateFormatWithInternetDateTime or NSISO8601DateFormatWithFractionalSeconds
    }

    /** [date] as the default ISO-8601 string — the capture-date stamp every candidate and resource carries. */
    fun format(date: NSDate): String = internetDateTime.stringFromDate(date)

    /** Parse the default (second-precision) shape only; `null` for anything else, as before. */
    fun parse(iso: String): NSDate? = internetDateTime.dateFromString(iso)

    /**
     * Parse either shape: second precision first, then with a fraction — for bounds an older build may have
     * persisted with the backend's raw milliseconds.
     */
    fun parseTolerant(iso: String): NSDate? =
        internetDateTime.dateFromString(iso) ?: withFractionalSeconds.dateFromString(iso)
}
