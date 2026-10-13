package app.snapsync.ports

/**
 * The label the calling thread's log lines carry right now (`docs/patterns.md`, "Logging"): one slot per thread.
 *
 * The structured `Log` reads a run's `Trigger` from the coroutine context. A Kermit line — the old zones', the
 * adapters' — is a plain synchronous call that sees no context, so while a run executes, scheduling puts the run's
 * label in this slot on the thread it runs on, and the process's log writers read it. It is a port because a
 * per-thread slot is platform state the core may not hold.
 */
interface ThreadLabel : Port {
    /** Sets the calling thread's label to [label] — `null` for none — and answers the one it replaced. */
    fun swap(label: String?): String?

    /** The calling thread's label, or `null`. */
    fun current(): String?
}
