@file:OptIn(ExperimentalAtomicApi::class)

package app.snapsync.contracts

import app.snapsync.ports.DownloadTransport
import app.snapsync.ports.DownloadTransportHost
import app.snapsync.ports.TransferOutcome
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A download transport has no state a clause distinguishes: every clause opens a fresh one. */
enum class DownloadTransportState { READY }

/**
 * The files a transport stages into — the durable staging disk — as a clause reads and seeds them. An observation
 * handle over the system the binding built: the bytes at a path, which is the state a finished transfer reached.
 */
interface StagingDisk {
    fun read(path: String): ByteArray?

    /** Put [bytes] at [path] before the transfer, so a clause can see whether the transfer replaced them. */
    fun write(path: String, bytes: ByteArray)
}

/**
 * The download transport as a clause receives it. [open] builds one over the host the clause supplies — the
 * transport's owner, and the port's own output channel, so what the host is told IS the port's answer. [staging] is
 * a directory the clause may name destinations under.
 */
class DownloadUnderTest(
    val open: (DownloadTransportHost) -> DownloadTransport,
    val base: String,
    val staging: String,
    val disk: StagingDisk,
)

/** One thing the transport told its owner, in the order it was told. */
sealed interface HostEvent {
    /** The transport asked whether the bytes may be staged; [occupied] is whether the destination held a file then. */
    data class Judged(val outcome: TransferOutcome, val occupied: Boolean) : HostEvent

    data class Staged(val path: String) : HostEvent

    data class Completed(val error: String?) : HostEvent
}

/**
 * The owner a clause hands the transport: it answers [accepts] and [destinationFor] as the clause chose, and keeps
 * what it was told. Atomic, because a real transport tells it from the session's own queue while the clause reads.
 */
class ClauseHost(
    private val disk: StagingDisk,
    private val destination: String?,
    private val accept: Boolean = true,
) : DownloadTransportHost {
    private val log = AtomicReference<List<HostEvent>>(emptyList())

    val events: List<HostEvent> get() = log.load()

    private fun add(event: HostEvent) {
        while (true) {
            val now = log.load()
            if (log.compareAndSet(now, now + event)) return
        }
    }

    override fun accepts(description: String, outcome: TransferOutcome): Boolean {
        add(HostEvent.Judged(outcome, destination?.let { disk.read(it) } != null))
        return accept
    }

    override fun destinationFor(description: String): String? = destination

    override fun onStaged(description: String, stagedPath: String) = add(HostEvent.Staged(stagedPath))

    override fun onCompleted(description: String, error: String?) = add(HostEvent.Completed(error))

    override fun onInvalidated() = Unit

    override fun onBackgroundEventsFinished() = Unit
}

/**
 * What every [DownloadTransport] promises its owner (capability `port-contracts` — this list IS the specification;
 * capability `photo-download` states why each matters). The integrity judgement is the owner's; what is contracted
 * is that the transport reports the facts truthfully and stages only what the owner accepted, where it said.
 */
object DownloadTransportContract : Contract<DownloadTransportState, DownloadUnderTest>("DownloadTransport") {

    /** The route a clause fetches from. */
    fun path(clauseId: String, answer: FixtureAnswer): String = TransferFixture.path(name, clauseId, "download", answer)

    private fun DownloadUnderTest.destination(clauseId: String) = "$staging/$clauseId/primary.bin"

    /** Starts one transfer of [answer] and waits until it completes; returns what the host was told. */
    private suspend fun DownloadUnderTest.transfer(
        clauseId: String,
        answer: FixtureAnswer,
        destination: String?,
        accept: Boolean = true,
    ): List<HostEvent> {
        val host = ClauseHost(disk, destination, accept)
        assertNotNull(open(host).start(base + path(clauseId, answer), "d-$clauseId"), "a fetchable URL starts")
        awaitWithin { host.events.any { it is HostEvent.Completed } }
        return host.events
    }

    override val clauses = clauses {

        clause("OK_STAGES_AT_DESTINATION", DownloadTransportState.READY) { subject ->
            val id = "OK_STAGES_AT_DESTINATION"
            val dest = subject.destination(id)
            val events = subject.transfer(id, FixtureAnswer.Respond(200, length = 1024), dest)
            assertEquals(
                listOf(
                    HostEvent.Judged(TransferOutcome(200, 1024, 1024), occupied = false),
                    HostEvent.Staged(dest),
                    HostEvent.Completed(null),
                ),
                events,
                "judged on the true facts, then staged where the owner said, then completed without an error",
            )
            assertContentEquals(TransferFixture.body(1024), subject.disk.read(dest), "the staged bytes are the body")
        }

        clause("JUDGED_BEFORE_MOVED", DownloadTransportState.READY) { subject ->
            val id = "JUDGED_BEFORE_MOVED"
            val events = subject.transfer(id, FixtureAnswer.Respond(200, length = 64), subject.destination(id))
            val judged = events.filterIsInstance<HostEvent.Judged>().single()
            assertEquals(
                false,
                judged.occupied,
                "the owner judges before the bytes are moved; staging is what makes them the store's truth",
            )
        }

        clause("REJECTED_STAYS_UNSTAGED", DownloadTransportState.READY) { subject ->
            val id = "REJECTED_STAYS_UNSTAGED"
            val dest = subject.destination(id)
            val prior = byteArrayOf(7, 7, 7)
            subject.disk.write(dest, prior)
            val events = subject.transfer(id, FixtureAnswer.Respond(404, length = 16), dest, accept = false)
            assertEquals(404, events.filterIsInstance<HostEvent.Judged>().single().outcome.statusCode)
            assertTrue(events.none { it is HostEvent.Staged }, "a rejected body is not staged")
            assertContentEquals(prior, subject.disk.read(dest), "an earlier good file survives a rejected body")
            assertEquals(HostEvent.Completed(null), events.last(), "the slot is freed either way")
        }

        clause("UNATTRIBUTABLE_STAYS_UNSTAGED", DownloadTransportState.READY) { subject ->
            val id = "UNATTRIBUTABLE_STAYS_UNSTAGED"
            val events = subject.transfer(id, FixtureAnswer.Respond(200, length = 16), destination = null)
            assertTrue(events.none { it is HostEvent.Staged }, "with nowhere to stage, nothing is staged")
            assertTrue(events.last() is HostEvent.Completed, "the slot is freed")
        }

        clause("RESTAGE_REPLACES", DownloadTransportState.READY) { subject ->
            val id = "RESTAGE_REPLACES"
            val dest = subject.destination(id)
            subject.disk.write(dest, byteArrayOf(1, 2, 3))
            subject.transfer(id, FixtureAnswer.Respond(200, length = 64), dest)
            assertContentEquals(TransferFixture.body(64), subject.disk.read(dest), "a re-download is last-write-wins")
        }

        clause("NO_LENGTH_IS_NEGATIVE", DownloadTransportState.READY) { subject ->
            val id = "NO_LENGTH_IS_NEGATIVE"
            val events = subject.transfer(
                id,
                FixtureAnswer.Respond(200, length = 32, declaresLength = false),
                subject.destination(id),
            )
            val outcome = events.filterIsInstance<HostEvent.Judged>().single().outcome
            assertTrue(outcome.expectedBytes < 0, "an undeclared length is 'unknown', not zero: $outcome")
            assertEquals(32, outcome.receivedBytes)
        }

        clause("SHORT_READ_IS_REPORTED_TRUTHFULLY", DownloadTransportState.READY) { subject ->
            val id = "SHORT_READ_IS_REPORTED_TRUTHFULLY"
            val host = ClauseHost(subject.disk, subject.destination(id))
            val route = path(id, FixtureAnswer.Respond(200, length = 64, short = true))
            assertNotNull(subject.open(host).start(subject.base + route, "d-$id"))
            awaitWithin { host.events.any { it is HostEvent.Completed } }
            // Either the transport fails a body cut short — no judgement asked, completed with an error — or it asks
            // the owner with the TRUE facts, so the owner's integrity check can refuse it. What it may never do is
            // report a cut transfer as whole: that is the one lie the integrity check cannot catch.
            val judged = host.events.filterIsInstance<HostEvent.Judged>()
            if (judged.isEmpty()) {
                assertNotNull(host.events.filterIsInstance<HostEvent.Completed>().single().error, "a cut transfer fails")
            } else {
                val outcome = judged.single().outcome
                assertTrue(outcome.receivedBytes < 64, "a cut transfer is not reported whole: $outcome")
            }
        }

        clause("CANCEL_COMPLETES_WITH_ERROR", DownloadTransportState.READY) { subject ->
            val id = "CANCEL_COMPLETES_WITH_ERROR"
            val dest = subject.destination(id)
            val host = ClauseHost(subject.disk, dest)
            val task = assertNotNull(subject.open(host).start(subject.base + path(id, FixtureAnswer.Hold), "d-$id"))
            task.cancel()
            awaitWithin { host.events.any { it is HostEvent.Completed } }
            val completed = host.events.filterIsInstance<HostEvent.Completed>().single()
            assertNotNull(completed.error, "a cancelled transfer completes with an error")
            assertTrue(host.events.none { it is HostEvent.Staged }, "and stages nothing")
            assertNull(subject.disk.read(dest))
        }

        clause("UNUSABLE_URL_NEVER_STAGES", DownloadTransportState.READY) { subject ->
            val id = "UNUSABLE_URL_NEVER_STAGES"
            val host = ClauseHost(subject.disk, subject.destination(id))
            // Never a throw. The port leaves filtering an unusable URL to its caller and collapses "not started"
            // with "started, then failed" because they converge — so either answer is honest, and what is
            // asserted is the convergence: nothing staged, and a transfer that did start completes with an error.
            // Measured 2026-09-23 (iOS 26.5 simulator): `NSURL.URLWithString("")` is NOT nil, so the real
            // transport starts one; the world's double answers null.
            val task = subject.open(host).start("", "d-$id")
            if (task == null) {
                transferSettle()
                assertEquals(emptyList(), host.events, "a transfer never started tells its owner nothing")
            } else {
                awaitWithin { host.events.any { it is HostEvent.Completed } }
                assertTrue(host.events.none { it is HostEvent.Staged }, "an unusable URL stages nothing")
                assertNotNull(
                    host.events.filterIsInstance<HostEvent.Completed>().single().error,
                    "a started transfer of an unusable URL completes with an error",
                )
            }
            assertNull(subject.disk.read(subject.destination(id)))
        }
    }
}
