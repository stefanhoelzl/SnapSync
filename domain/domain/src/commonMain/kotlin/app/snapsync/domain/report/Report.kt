package app.snapsync.domain.report

/**
 * What the screen contributes to a bug report: the opaque label of the surface it was sent from, and [shown] — what
 * that surface displayed, keyed as the report keys it (`shown_shared`, `shown_received`). Both recorded verbatim: a
 * report exists to catch the screen disagreeing with the stores, so it carries what was rendered rather than a
 * re-derivation.
 */
data class ReportContext(
    val screen: String,
    val shown: Map<String, String> = emptyMap(),
    /**
     * The report was opened from "Report this" beside a refusal: only then does it carry the refused verification's
     * facts.
     */
    val verification: Boolean = false,
)

/**
 * What became of a bug report the user confirmed, as the app briefly says it: handed
 * to the reporting service, kept on this device, or neither. [SENT] is a hand-off, never a delivery — the channel
 * may queue and retry, and nothing reports back.
 */
enum class ReportOutcome { SENT, SAVED, NOT_SENT }

/**
 * The build's version and build number as the app menu shows them. Off-device
 * compositions know neither and say so ([UNKNOWN]) rather than showing a number nobody built.
 */
data class BuildLabel(val version: String, val buildNumber: String) {
    companion object {
        val UNKNOWN: BuildLabel = BuildLabel(version = "unknown", buildNumber = "unknown")
    }
}

/**
 * Where a bug report goes — a constant of the build: a distributed build sends it to
 * the developer's error-tracking service; a build that reports nowhere keeps it on this device.
 */
enum class ReportDestination { DEVELOPER, THIS_DEVICE }
