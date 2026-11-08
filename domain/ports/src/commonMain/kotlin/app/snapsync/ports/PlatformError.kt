package app.snapsync.ports

/** What the platform said went wrong, as it said it — a description to log, never a decision input. */
data class PlatformError(val description: String)
