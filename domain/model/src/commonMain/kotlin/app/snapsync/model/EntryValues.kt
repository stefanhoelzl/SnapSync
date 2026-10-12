package app.snapsync.model

/** What the platform said went wrong, as it said it — a description to log, never a decision input. */
data class PlatformError(val description: String)

/** What a log line says of a [PlatformError] the platform may not have given: its description, or `null`. */
fun PlatformError?.describe(): String = "${this?.description}"
