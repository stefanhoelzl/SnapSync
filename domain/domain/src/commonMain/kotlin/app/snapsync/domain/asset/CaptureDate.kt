package app.snapsync.domain.asset

import app.snapsync.domain.time.Timestamp
import kotlin.jvm.JvmInline
import kotlin.time.Instant

/**
 * When an asset was captured, as its library records it — or that it records none. A rule over capture dates matches
 * both cases by name, so "no date" is decided where it matters instead of by a null check each rule must remember.
 */
sealed interface CaptureDate {
    /** Whether this date lies inside `[from, until]` — a plain window, such as the dates of an event's photos. */
    fun within(from: Timestamp, until: Timestamp): Boolean

    /** The library records the capture at [at]. */
    @JvmInline
    value class Dated(val at: Timestamp) : CaptureDate {
        override fun within(from: Timestamp, until: Timestamp): Boolean = at >= from && at <= until
    }

    /** The library records no capture date — which no capture range holds. */
    data object Undated : CaptureDate {
        override fun within(from: Timestamp, until: Timestamp): Boolean = false
    }

    companion object {
        /** The capture the platform records at [at], or none. */
        fun of(at: Instant?): CaptureDate = when (at) {
            null -> Undated
            else -> Dated(Timestamp.of(at))
        }
    }
}
