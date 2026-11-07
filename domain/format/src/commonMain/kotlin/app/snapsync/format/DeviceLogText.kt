package app.snapsync.format

/**
 * The device log's text — the store a process's lines are kept in on the phone, read back by a bug report: where each
 * process's log lives, and the body every sink writes. The stamp in front of each line is the file sinks' own
 * (`LogStamp`, in the shared adapter module).
 */
object DeviceLogText {
    /**
     * The **app**'s device log: its own private area's `debug.log`, exactly where it has always been — a process can
     * always read its own container, and every `pymobiledevice3 apps pull app.snapsync Documents/debug.log` in the
     * runbook depends on it. One definition for the writer (the file sinks) and the reader (the log tails), so the two
     * can never name different files.
     */
    const val APP_LOG: String = "debug.log"

    /**
     * The **extension**'s device log, in the **shared** area — the one placement the app, which assembles a bug report,
     * can read. See [APP_LOG].
     */
    const val EXTENSION_LOG: String = "ext-debug.log"

    /**
     * One device-log line, without its stamp: `[<trigger>] [<Level>/<tag>] <message>`, with ` | <stack trace>` when a
     * cause rides it. The `[<trigger>]` prefix — the entry point that triggered the line — is what lets a reader trace an
     * engine or HTTP line back to its trigger; it is absent when none is claimed. Every sink writes this same text.
     */
    fun body(trigger: String?, level: String, tag: String, message: String, cause: Throwable?): String = buildString {
        if (trigger != null) append('[').append(trigger).append("] ")
        append('[').append(level).append('/').append(tag).append("] ").append(message)
        if (cause != null) append(" | ").append(cause.stackTraceToString())
    }
}
