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
 * Where a bug report goes — a constant of the build: a distributed build sends it to
 * the developer's error-tracking service; a build that reports nowhere keeps it on this device.
 */
enum class ReportDestination { DEVELOPER, THIS_DEVICE }
