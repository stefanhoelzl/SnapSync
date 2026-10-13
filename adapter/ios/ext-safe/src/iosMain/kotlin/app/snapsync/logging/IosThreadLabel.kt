package app.snapsync.logging

import app.snapsync.ports.ThreadLabel

/**
 * The iOS [ThreadLabel]: one slot per thread, read by the process's log writers to prefix each line with the run it is
 * working for. A `@ThreadLocal` object rather than a map keyed by `pthread_self()`: no lock on every log line, and
 * nothing to leak for a thread that dies labelled. Each line is labelled by the thread that wrote it, so concurrent work
 * never takes another's label — the global claim this replaced labelled every concurrent line with whichever entry
 * point arrived first (measured 2026-09-14: launch lines carrying a MetricKit delivery's prefix).
 */
object IosThreadLabel : ThreadLabel {
    override fun swap(label: String?): String? = Slot.label.also { Slot.label = label }

    override fun current(): String? = Slot.label
}

/** One copy per thread. */
@kotlin.native.concurrent.ThreadLocal
private object Slot {
    var label: String? = null
}
