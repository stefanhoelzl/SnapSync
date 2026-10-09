package app.snapsync.model

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
 * What became of a bug report the user confirmed, as the app briefly says it: handed
 * to the reporting service, kept on this device, or neither. [SENT] is a hand-off, never a delivery — the channel
 * may queue and retry, and nothing reports back.
 */
enum class ReportOutcome { SENT, SAVED, NOT_SENT }

/**
 * Where a bug report goes — a constant of the build: a distributed build sends it to
 * the developer's error-tracking service; a build that reports nowhere keeps it on this device.
 */
enum class ReportDestination { DEVELOPER, THIS_DEVICE }
