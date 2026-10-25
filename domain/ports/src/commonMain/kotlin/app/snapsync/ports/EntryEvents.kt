package app.snapsync.ports

import kotlin.time.Duration

/**
 * What the process's runtime tells of each entry point's work as it happens (`docs/patterns.md`, "Entry events"): the
 * operating system handed a delivery over, the delivery was answered, and everything it caused has ended.
 *
 * The runtime writes a log line beside every event, which is the diagnostic a shipped build keeps; this port is for a
 * process that observes its own app — the control channel, whose event stream a test waits on. A shipped build's
 * adapter takes every event and keeps none. It never throws: it is told from inside the runtime's own bookkeeping.
 */
interface EntryEvents : Port {
    fun tell(event: EntryEvent)
}

/**
 * One thing that happened to an entry point's work. [entry] is the entry point's name — the same word as its log lines'
 * `[Trigger]` label — and [run] tells one delivery of it from another, within the process.
 */
sealed interface EntryEvent {
    val entry: String
    val run: Long

    /** The operating system handed the delivery over; [about] says which of its kind it is — a tap's intent — if told. */
    data class Delivered(override val entry: String, override val run: Long, val about: String? = null) : EntryEvent

    /** The operating system got its answer back — its completion, or the delivering call's return — [after] delivery. */
    data class Answered(override val entry: String, override val run: Long, val after: Duration) : EntryEvent

    /**
     * The delivery's work AND every run it started without awaiting have ended, [after] it began — or, for work started
     * inside the process for no delivery (a membership transition's arm), that work and what it started.
     */
    data class Done(
        override val entry: String,
        override val run: Long,
        val after: Duration,
        val outcome: EntryOutcome,
    ) : EntryEvent
}

/** How an entry point's work ended: the worst of its runs. */
enum class EntryOutcome {
    /** Every run ended on its own. */
    COMPLETED,

    /** A run's time was up: the operating system expired it. */
    EXPIRED,

    /** A run failed. */
    FAILED,
}
