package app.snapsync.mock

import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A mock read by an inspector while the app writes it — what every rig `/device` read is: the request runs on the
 * server's thread, the app writes from its own. [write] runs on an "app" thread, round after round, while [read] runs
 * here for [window]; the answer is every distinct way a read tore (an exception's class, or what [read] threw on
 * seeing), empty for a mock whose every read is of one whole state. Collected over the whole window rather than
 * stopping at the first, so a failure names each kind it found.
 */
internal fun tears(window: Duration = 1.seconds, write: (round: Int) -> Unit, read: () -> Unit): Set<String> {
    val running = AtomicBoolean(true)
    val writer = thread(name = "app") {
        var round = 0
        while (running.get()) write(round++)
    }
    val torn = sortedSetOf<String>()
    val deadline = TimeSource.Monotonic.markNow() + window
    try {
        while (deadline.hasNotPassedNow()) {
            try {
                read()
            } catch (failure: Throwable) {
                torn += failure::class.simpleName ?: failure.toString()
            }
        }
    } finally {
        running.set(false)
        writer.join()
    }
    return torn
}

/** A read that came back holding a `null` a non-null type cannot — a copy taken mid-write. */
internal class NullInRead : RuntimeException()

internal fun <T> Collection<T>.whole(): Collection<T> = also { if (any { (it as Any?) == null }) throw NullInRead() }
