package app.snapsync.model

/**
 * The **app**'s device log: its own private area's `debug.log` (capability `privacy-security`), exactly where it
 * has always been — a process can always read its own container, and every
 * `pymobiledevice3 apps pull app.snapsync Documents/debug.log` in the runbook depends on it.
 *
 * One definition for the writer (`:adapter:ios:ext-safe`) and the reader (the log-tail service), so the two can
 * never name different files.
 */
const val APP_LOG_FILE_NAME: String = "debug.log"

/**
 * The **extension**'s device log, in the **shared** area — the one placement the app, which assembles a
 * diagnostic dump, can read. See [APP_LOG_FILE_NAME].
 */
const val EXTENSION_LOG_FILE_NAME: String = "ext-debug.log"
