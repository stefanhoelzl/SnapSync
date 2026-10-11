package app.snapsync.gallery

import app.snapsync.ports.AssetFilter
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDate
import platform.Foundation.NSPredicate
import platform.Foundation.dateWithTimeIntervalSince1970
import kotlin.time.Instant

/**
 * Translate an [AssetFilter] into a `PHFetchOptions` predicate over `creationDate`, or `null` for no narrowing.
 *
 * The lower bound is the narrowing a walk cannot do without: without it the walk enumerates the whole library and the
 * extension is watchdog-killed before anything decides over the result. Each bound is **widened by a day**
 * ([PREDICATE_WIDEN_SECONDS]): the filter is a hint the answer may over-return by, and what comes back is also the
 * walk's presence set, so a bound that rounded the wrong way at its edge would delete the rows of a photo still in
 * the library. Over-returning costs a few extra assets the caller drops; under-returning loses a photo nothing adds
 * back.
 *
 * **Three device-verified constraints on PhotoKit's predicate parser**, for whoever adds a key here (SE2, iOS 26.5.2;
 * measured facts, not preferences — re-verify on a device before adding one):
 *
 * 1. A subtype exclusion MUST be written `NOT ((mediaSubtypes & N) != 0)`. The natural `(mediaSubtypes & N) == 0`
 *    form returns **zero rows** — silently, without raising. (The *singular* `mediaSubtype` key likewise returns zero
 *    rows without raising, so a one-character typo empties the library.)
 * 2. Predicate **arithmetic** raises an uncatchable `NSException` and aborts the process, so
 *    `pixelWidth * pixelHeight` is impossible.
 * 3. `hasAdjustments` is not a supported key and likewise aborts the process.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun predicateFor(filter: AssetFilter): NSPredicate? {
    val clauses = mutableListOf<String>()
    val args = mutableListOf<Any>()
    filter.createdFrom?.let {
        clauses += "creationDate >= %@"
        args += it.toNSDate(-PREDICATE_WIDEN_SECONDS)
    }
    filter.createdUntil?.let {
        clauses += "creationDate <= %@"
        args += it.toNSDate(PREDICATE_WIDEN_SECONDS)
    }
    if (clauses.isEmpty()) return null
    return NSPredicate.predicateWithFormat(clauses.joinToString(" AND "), argumentArray = args)
}

/** [this] instant moved by [seconds], as the `NSDate` a predicate compares against. */
internal fun Instant.toNSDate(seconds: Double = 0.0): NSDate =
    NSDate.dateWithTimeIntervalSince1970(epochSeconds.toDouble() + nanosecondsOfSecond / NANOS_PER_SECOND + seconds)

private const val NANOS_PER_SECOND = 1_000_000_000.0

/** One day of slack on each date bound — see [predicateFor]. */
internal const val PREDICATE_WIDEN_SECONDS = 24.0 * 60.0 * 60.0
