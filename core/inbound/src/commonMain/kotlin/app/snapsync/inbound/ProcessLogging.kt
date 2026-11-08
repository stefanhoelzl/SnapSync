package app.snapsync.inbound

/**
 * A process's logging global — [Log]'s writers — and whether the process owns it (`docs/architecture.md`, "The
 * composition layer"): **a process that supplies log sinks owns them**. One that supplies none ([sinks] `0`) leaves it
 * alone: a JVM hosts many "processes" in one VM, where the writers are VM-global, and a composition there must not take
 * them over.
 */
class ProcessLogging(private val writers: List<LogWriter>, private val sinks: Int) {
    /** Install [writers] — where this process owns the global, and nowhere else. */
    @CompositionOnly
    fun install() {
        when (sinks) {
            0 -> Unit
            else -> Log.install(writers)
        }
    }
}
