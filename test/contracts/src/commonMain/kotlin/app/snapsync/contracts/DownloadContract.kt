@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.contracts

import app.snapsync.model.StartResult
import app.snapsync.model.TransferOutcome
import app.snapsync.ports.Completion
import app.snapsync.ports.Download
import app.snapsync.ports.DownloadHandlers
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A download port has no state a clause distinguishes: every clause opens a fresh one. */
enum class DownloadState { READY }

/**
 * The port as a clause receives it. [open] builds a fresh [Download] the clause listens to — its handlers are the
 * port's own output channel, so what they are told IS the port's answer. [readTemp] reads the platform file a finish
 * hands over, **inline**, as an owner must (the platform deletes it when the callback returns): an observation handle
 * over the system the binding built.
 */
class DownloadUnderTest(
    val open: () -> Download,
    val base: String,
    val readTemp: (path: String) -> ByteArray?,
)

/** One thing the port told its owner, in the order it was told. */
sealed interface DownloadEvent {
    /** A finish, with the body read from the temporary file while the callback ran. */
    class Finished(val tag: String, val facts: TransferOutcome, val body: ByteArray?) : DownloadEvent

    data class Completed(val tag: String, val error: String?) : DownloadEvent
}

/**
 * The owner a clause registers: it keeps what it was told, reading a finished body from its temporary file before the
 * callback returns. Atomic, because a real session tells it from its own queue while the clause reads.
 */
class ClauseDownloadHandlers(private val readTemp: (String) -> ByteArray?) {
    private val log = AtomicReference<List<DownloadEvent>>(emptyList())

    val events: List<DownloadEvent> get() = log.load()

    private fun add(event: DownloadEvent) {
        while (true) {
            val now = log.load()
            if (log.compareAndSet(now, now + event)) return
        }
    }

    val handlers = DownloadHandlers(
        onFinished = { tag, facts, tempPath -> add(DownloadEvent.Finished(tag, facts, readTemp(tempPath))) },
        onCompleted = { tag, error -> add(DownloadEvent.Completed(tag, error)) },
        onInvalidated = {},
        onBackgroundEvents = { completion: Completion -> completion.complete() },
        onEventsDrained = {},
    )
}

/**
 * What every [Download] promises its owner (`docs/architecture.md` — this list IS the specification; capability
 * `receiving-photos` states why each matters). Whether a body may be staged, and where, are the owner's; what is
 * contracted is that the port reports the facts truthfully, hands over the body it received, completes every transfer
 * it started, and cancels what it holds.
 */
object DownloadContract : Contract<DownloadState, DownloadUnderTest>("Download") {

    /** The route a clause fetches from. */
    fun path(clauseId: String, answer: FixtureAnswer, n: Int = 1): String =
        TransferFixture.path(name, clauseId, "download-$n", answer)

    /** Opens a port, listens, starts one transfer of [answer] and waits until it completes; returns what was told. */
    private suspend fun DownloadUnderTest.transfer(clauseId: String, answer: FixtureAnswer): List<DownloadEvent> {
        val owner = ClauseDownloadHandlers(readTemp)
        val download = open()
        download.listen(owner.handlers)
        assertEquals(StartResult.Started, download.start(base + path(clauseId, answer), "d-$clauseId"), "a fetchable URL starts")
        awaitWithin { owner.events.any { it is DownloadEvent.Completed } }
        return owner.events
    }

    override val clauses = clauses {

        clause("FINISH_REPORTS_THE_TRUE_FACTS_AND_THE_BODY", DownloadState.READY) { subject ->
            val id = "FINISH_REPORTS_THE_TRUE_FACTS_AND_THE_BODY"
            val events = subject.transfer(id, FixtureAnswer.Respond(200, length = 1024))
            val finished = events.first() as? DownloadEvent.Finished
            assertNotNull(finished, "a finish comes first, then the completion: $events")
            assertEquals("d-$id", finished.tag, "reported under the tag it was started with")
            assertEquals(TransferOutcome(200, 1024, 1024), finished.facts, "judged on the true facts")
            assertContentEquals(TransferFixture.body(1024), finished.body, "the temporary file holds the body, inline")
            assertEquals(DownloadEvent.Completed("d-$id", null), events.last(), "then completed without an error")
        }

        clause("AN_ERROR_STATUS_IS_A_FINISHED_TRANSFER_OF_ITS_BODY", DownloadState.READY) { subject ->
            // A background `URLSession` reports an HTTP error as a SUCCESSFUL transfer of the error body, with a nil
            // completion error — the port reports the status, and the owner's integrity check is what refuses it.
            val id = "AN_ERROR_STATUS_IS_A_FINISHED_TRANSFER_OF_ITS_BODY"
            val events = subject.transfer(id, FixtureAnswer.Respond(404, length = 16))
            val finished = events.filterIsInstance<DownloadEvent.Finished>().single()
            assertEquals(404, finished.facts.statusCode)
            assertTrue(events.last() is DownloadEvent.Completed, "the slot is freed either way")
        }

        clause("NO_LENGTH_IS_NEGATIVE", DownloadState.READY) { subject ->
            val id = "NO_LENGTH_IS_NEGATIVE"
            val events = subject.transfer(id, FixtureAnswer.Respond(200, length = 32, declaresLength = false))
            val facts = events.filterIsInstance<DownloadEvent.Finished>().single().facts
            assertTrue(facts.expectedBytes < 0, "an undeclared length is 'unknown', not zero: $facts")
            assertEquals(32, facts.receivedBytes)
        }

        clause("SHORT_READ_IS_REPORTED_TRUTHFULLY", DownloadState.READY) { subject ->
            val id = "SHORT_READ_IS_REPORTED_TRUTHFULLY"
            val events = subject.transfer(id, FixtureAnswer.Respond(200, length = 64, short = true))
            // Either the port fails a body cut short — no finish, completed with an error — or it reports the TRUE
            // facts, so the owner's integrity check can refuse it. What it may never do is report a cut transfer as
            // whole: that is the one lie the integrity check cannot catch.
            val finished = events.filterIsInstance<DownloadEvent.Finished>()
            if (finished.isEmpty()) {
                assertNotNull(events.filterIsInstance<DownloadEvent.Completed>().single().error, "a cut transfer fails")
            } else {
                assertTrue(finished.single().facts.receivedBytes < 64, "a cut transfer is not reported whole")
            }
        }

        clause("CANCEL_ALL_COMPLETES_WHAT_IT_HOLDS_WITH_AN_ERROR", DownloadState.READY) { subject ->
            val id = "CANCEL_ALL_COMPLETES_WHAT_IT_HOLDS_WITH_AN_ERROR"
            val owner = ClauseDownloadHandlers(subject.readTemp)
            val download = subject.open()
            download.listen(owner.handlers)
            assertEquals(StartResult.Started, download.start(subject.base + path(id, FixtureAnswer.Hold), "d-$id"))
            download.cancelAll()
            awaitWithin { owner.events.any { it is DownloadEvent.Completed } }
            assertNotNull(owner.events.filterIsInstance<DownloadEvent.Completed>().single().error, "cancelled: an error")
            assertTrue(owner.events.none { it is DownloadEvent.Finished }, "and nothing finished")
        }

        clause("A_TRANSFER_STARTED_AFTER_CANCEL_ALL_RETURNS_RUNS", DownloadState.READY) { subject ->
            val id = "A_TRANSFER_STARTED_AFTER_CANCEL_ALL_RETURNS_RUNS"
            val owner = ClauseDownloadHandlers(subject.readTemp)
            val download = subject.open()
            download.listen(owner.handlers)
            download.cancelAll()
            val tag = "d-$id"
            assertEquals(StartResult.Started, download.start(subject.base + path(id, FixtureAnswer.Respond(200, length = 8)), tag))
            awaitWithin { owner.events.any { it is DownloadEvent.Completed } }
            assertEquals(DownloadEvent.Completed(tag, null), owner.events.last(), "a transfer after the cancel is untouched")
        }

        clause("UNUSABLE_URL_NEVER_FINISHES", DownloadState.READY) { subject ->
            val id = "UNUSABLE_URL_NEVER_FINISHES"
            val owner = ClauseDownloadHandlers(subject.readTemp)
            val download = subject.open()
            download.listen(owner.handlers)
            // Never a throw. The port leaves filtering an unusable URL to its caller and collapses "not started" with
            // "started, then failed" because they converge — so either answer is honest, and what is asserted is the
            // convergence: nothing finished, and a transfer that did start completes with an error. Measured
            // 2026-09-23 (iOS 26.5 simulator): `NSURL.URLWithString("")` is NOT nil, so the real session starts one.
            when (download.start("", "d-$id")) {
                StartResult.NotStarted -> {
                    transferSettle()
                    assertEquals(emptyList(), owner.events, "a transfer never started tells its owner nothing")
                }
                StartResult.Started -> {
                    awaitWithin { owner.events.any { it is DownloadEvent.Completed } }
                    assertTrue(owner.events.none { it is DownloadEvent.Finished }, "an unusable URL finishes nothing")
                    assertNotNull(owner.events.filterIsInstance<DownloadEvent.Completed>().single().error)
                }
            }
        }
    }
}
