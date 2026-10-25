@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.scheduling

import app.snapsync.inbound.Log
import app.snapsync.inbound.of
import app.snapsync.ports.EntryEvent
import app.snapsync.ports.EntryEvents
import app.snapsync.ports.EntryOutcome
import kotlinx.coroutines.CancellationException
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.fetchAndIncrement
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.time.TimeSource

/**
 * One entry point's work, as its [EntryEvent.Done] waits for it (`docs/patterns.md`, "Entry events"): the delivery's
 * own run, and every run started inside it without being awaited — transitively. Each is [opened] before it starts and
 * [closed] when it ends; the last to close tells `done`.
 *
 * Its root is a delivery, which tells `delivered` as it opens, or work started inside the process for no delivery.
 */
internal class EntryWork private constructor(
    private val entry: String,
    private val events: EntryEvents,
    private val log: Log,
) {
    private val run: Long = runs.fetchAndIncrement()
    private val began = TimeSource.Monotonic.markNow()

    /** The delivery's own run is open from the start. */
    private val open = AtomicInt(1)
    private val expired = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)

    /** One more run of this work starts. Called before it starts, by a run of this work that is still open. */
    fun opened(): EntryWork = also { open.incrementAndFetch() }

    /** A run of this work ended with [outcome]; the last tells `done`. */
    fun closed(outcome: EntryOutcome) {
        when (outcome) {
            EntryOutcome.COMPLETED -> Unit
            EntryOutcome.EXPIRED -> expired.store(true)
            EntryOutcome.FAILED -> failed.store(true)
        }
        if (open.decrementAndFetch() > 0) return
        val after = began.elapsedNow()
        val ended = when {
            failed.load() -> EntryOutcome.FAILED
            expired.load() -> EntryOutcome.EXPIRED
            else -> EntryOutcome.COMPLETED
        }
        when (ended) {
            EntryOutcome.COMPLETED -> log.unlabelled.i("done", "entry" of entry, "after" of after, "outcome" of ended)
            else -> log.unlabelled.w("done", "entry" of entry, "after" of after, "outcome" of ended)
        }
        events.tell(EntryEvent.Done(entry, run, after, ended))
    }

    /** The operating system got its answer back. */
    fun answered() {
        val after = began.elapsedNow()
        log.unlabelled.i("answered", "entry" of entry, "after" of after)
        events.tell(EntryEvent.Answered(entry, run, after))
    }

    companion object {
        /** Every run of the process — of every runtime in it, as a JVM hosts two processes — told apart. */
        private val runs = AtomicLong(0)

        /** The operating system handed [entry] over — [about] which of its kind: its work begins, told `delivered`. */
        fun delivered(entry: String, about: String?, events: EntryEvents, log: Log): EntryWork =
            EntryWork(entry, events, log).also {
                when (about) {
                    null -> log.unlabelled.i("delivered", "entry" of entry)
                    else -> log.unlabelled.i("delivered", "entry" of entry, "about" of about)
                }
                events.tell(EntryEvent.Delivered(entry, it.run, about))
            }

        /** Work started inside the process for no delivery: told `done` only. */
        fun started(entry: String, events: EntryEvents, log: Log): EntryWork = EntryWork(entry, events, log)
    }
}

/** How a run ended, as its work's outcome counts it: a cancellation — the operating system's expiry — is [EXPIRED]. */
internal fun endedAs(failure: Throwable?): EntryOutcome = when (failure) {
    null -> EntryOutcome.COMPLETED
    is CancellationException -> EntryOutcome.EXPIRED
    else -> EntryOutcome.FAILED
}

/** The entry point's work a coroutine runs for, so the runs it starts belong to it. */
internal class WorkElement(val work: EntryWork) : AbstractCoroutineContextElement(WorkElement) {
    companion object Key : CoroutineContext.Key<WorkElement>
}
