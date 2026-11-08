package app.snapsync.domain.crash

/**
 * What one log line becomes on the channel: always a [crumb], and an [event] at `ERROR` — so every failure an entry
 * point, a step or a use case logs is reported without per-call-site instrumentation, and a line that reports nothing
 * still explains the next one that does.
 */
class LineReport private constructor(val crumb: Crumb, val event: CrashEvent?) {
    companion object {
        /**
         * The line [event] at [level] under [tag], written with [fields] — and [cause], and the [trigger] it ran for.
         *
         * - The breadcrumb is the whole line — `[trigger] event name=value …`, ids in full — so the event it rides
         *   keeps the line that explains it.
         * - An `ERROR` is an event titled by what happened: its [event], never the values it happened with, so one
         *   cause is one issue and the operator's overview carries no ids. A [cause] is captured as a [ReportedFailure]
         *   titled the same, the failure itself chained beneath it. The [fields] ride as the [CrashSections.LINE]
         *   section, the trigger as [CrashTags.ENTRY_POINT] and there too, and the event groups under `<tag>: <event>`.
         * - Below `ERROR`, a breadcrumb only: a breadcrumb attached to no event is never sent anywhere.
         */
        @Suppress("LongParameterList") // one line's parts, as the log hands them over
        fun of(
            level: CrashLevel,
            tag: String,
            event: String,
            fields: List<Pair<String, String>>,
            cause: Throwable?,
            trigger: String?,
        ): LineReport {
            val text = (listOf(event) + fields.map { (name, value) -> "$name=$value" }).joinToString(" ")
            val crumb = Crumb(level, if (trigger != null) "[$trigger] $text" else text, tag)
            return LineReport(
                crumb,
                if (level == CrashLevel.ERROR) failure(tag, event, fields, cause, trigger) else null,
            )
        }

        private fun failure(
            tag: String,
            event: String,
            fields: List<Pair<String, String>>,
            cause: Throwable?,
            trigger: String?,
        ): CrashEvent {
            val section = fields.toMap() + listOfNotNull(trigger?.let { "trigger" to it })
            return CrashEvent(
                message = event.takeIf { cause == null },
                throwable = cause?.let { ReportedFailure(event, it) },
                tags = mapOf(CrashTags.FINGERPRINT to "$tag: $event") +
                    listOfNotNull(trigger?.let { CrashTags.ENTRY_POINT to it }),
                contexts = mapOf(CrashSections.LINE to section).filterValues { it.isNotEmpty() },
            )
        }
    }
}
