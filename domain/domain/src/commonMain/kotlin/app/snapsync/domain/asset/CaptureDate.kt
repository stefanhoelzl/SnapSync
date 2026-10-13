package app.snapsync.domain.asset

import app.snapsync.domain.time.Timestamp
import kotlin.jvm.JvmInline

/**
 * When an asset was captured, as its library records it — or that it records none. A rule over capture dates matches
 * both cases by name, so "no date" is decided where it matters instead of by a null check each rule must remember.
 */
sealed interface CaptureDate {
    /** The library records the capture at [at]. */
    @JvmInline
    value class Dated(val at: Timestamp) : CaptureDate

    /** The library records no capture date — which no capture range holds. */
    data object Undated : CaptureDate
}
